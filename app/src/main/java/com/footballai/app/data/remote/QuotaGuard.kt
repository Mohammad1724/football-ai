package com.footballai.app.data.remote

import com.footballai.app.core.ErrorCode
import com.footballai.app.core.AppError
import com.footballai.app.core.AppErrorException
import com.footballai.app.core.TimeProvider
import com.footballai.app.core.TimeConstants
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence for the daily request counter, so restarts cannot bypass the quota. */
interface QuotaStore {
    suspend fun readCounter(providerId: String): Pair<Long, Int>
    suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int)
}

data class QuotaState(
    val providerId: String,
    val usedToday: Int,
    val dailyLimit: Int,
    val remaining: Int,
)

/**
 * Enforces both the per-minute rate limit and the daily request budget of a provider.
 *
 * The per-minute limit is a token bucket, not a fixed delay between requests. A fixed
 * delay made even a modest sync slow: fetching nineteen competitions at forty requests a
 * minute meant waiting 1.5 s before *every* request, and a sync that talks to a public
 * endpoint for a few seconds had to look like it was doing nothing for two minutes.
 *
 * A bucket keeps the same average rate — it can never exceed `requestsPerMinute` over
 * time — while allowing a short burst, which is exactly what a sync of independent
 * documents looks like. Waiting happens by delaying the caller, never by failing it.
 */
class QuotaGuard(
    private val store: QuotaStore,
    private val clock: TimeProvider,
) {
    private val mutex = Mutex()

    private class Bucket(var tokens: Double, var refilledAtMs: Long)

    /** Per-provider buckets; each provider has its own published rate. */
    private val buckets = HashMap<String, Bucket>()

    suspend fun acquire(providerId: String, policy: QuotaPolicy) {
        while (true) {
            val waitMs = mutex.withLock { tryConsume(providerId, policy) }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    /** Returns 0 when a request may go now, or how long to wait for the next token. */
    private suspend fun tryConsume(providerId: String, policy: QuotaPolicy): Long {
        val now = clock.nowEpochMs()
        val dayStart = now - (now % TimeConstants.DAY_MS)
        val (storedDay, storedCount) = store.readCounter(providerId)
        val used = if (storedDay == dayStart) storedCount else 0

        if (policy.requestsPerDay > 0 && used >= policy.requestsPerDay) {
            throw AppErrorException(
                AppError(
                    code = ErrorCode.QUOTA_EXHAUSTED,
                    detail = "Daily quota of ${policy.requestsPerDay} requests reached for $providerId",
                ),
            )
        }

        if (policy.requestsPerMinute <= 0) {
            store.writeCounter(providerId, dayStart, used + 1)
            return 0L
        }

        val burst = burstFor(policy.requestsPerMinute)
        val ratePerMs = policy.requestsPerMinute / TimeConstants.MINUTE_MS.toDouble()
        val bucket = buckets.getOrPut(providerId) { Bucket(burst.toDouble(), now) }
        val elapsed = (now - bucket.refilledAtMs).coerceAtLeast(0L)
        bucket.tokens = minOf(burst.toDouble(), bucket.tokens + elapsed * ratePerMs)
        bucket.refilledAtMs = now

        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            store.writeCounter(providerId, dayStart, used + 1)
            return 0L
        }
        // Not even one token: wait just long enough for it, rounded up a little so the
        // loop does not spin.
        return ((1.0 - bucket.tokens) / ratePerMs).toLong().coerceAtLeast(MIN_WAIT_MS)
    }

    suspend fun state(providerId: String, policy: QuotaPolicy): QuotaState {
        val now = clock.nowEpochMs()
        val dayStart = now - (now % TimeConstants.DAY_MS)
        val (storedDay, storedCount) = store.readCounter(providerId)
        val used = if (storedDay == dayStart) storedCount else 0
        return QuotaState(
            providerId = providerId,
            usedToday = used,
            dailyLimit = policy.requestsPerDay,
            remaining = (policy.requestsPerDay - used).coerceAtLeast(0),
        )
    }

    companion object {
        /**
         * A sixth of the minute budget may go out at once. Six small bursts a minute keep
         * the average exactly at the published rate while letting a sync start moving.
         */
        internal fun burstFor(requestsPerMinute: Int): Int =
            (requestsPerMinute / 6).coerceIn(1, 8)

        /** Smallest sleep used while waiting for a token, to avoid a busy loop. */
        private const val MIN_WAIT_MS = 25L
    }
}
