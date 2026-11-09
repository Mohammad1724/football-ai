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
 * Requests are serialised through a mutex: the guard delays instead of failing so a
 * slow sync cannot silently break the API terms of service.
 */
class QuotaGuard(
    private val store: QuotaStore,
    private val clock: TimeProvider,
) {
    private val mutex = Mutex()
    private var lastRequestAtMs: Long = 0L

    suspend fun acquire(providerId: String, policy: QuotaPolicy) {
        mutex.withLock {
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

            if (policy.requestsPerMinute > 0) {
                val minimumSpacing = (TimeConstants.MINUTE_MS / policy.requestsPerMinute) + 50L
                val earliest = lastRequestAtMs + minimumSpacing
                if (now < earliest) {
                    delay(earliest - now)
                }
            }

            val timestamp = clock.nowEpochMs()
            lastRequestAtMs = timestamp
            store.writeCounter(providerId, dayStart, used + 1)
        }
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
}
