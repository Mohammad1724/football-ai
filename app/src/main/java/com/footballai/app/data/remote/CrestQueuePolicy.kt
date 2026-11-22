package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome

/**
 * When a crest request has failed, what that failure says about the rest of the queue.
 *
 * There are two kinds. One is about a single request — the connection dropped, the body was
 * cut off, an answer could not be read — and it says nothing about the club behind it or the
 * next club in line. The other is about the source itself: a rate limit, a spent quota, a
 * refused key, a plan that does not include the endpoint. Asking again in that second case
 * only spends what is left of the day's allowance against a door that is already closed.
 *
 * Kept here, outside the sweep, because this is the rule that decides whether a club keeps
 * its badge today, and it should be readable and testable on its own.
 */
object CrestQueuePolicy {

    /** True when every further request in this sweep would fail the same way. */
    fun stopsBatch(error: AppError): Boolean = when (error.code) {
        ErrorCode.RATE_LIMITED,
        ErrorCode.QUOTA_EXHAUSTED,
        ErrorCode.MISSING_API_KEY,
        ErrorCode.INVALID_API_KEY,
        ErrorCode.ACCESS_DENIED,
        ErrorCode.PLAN_RESTRICTED,
        ErrorCode.SUBSCRIPTION_INACTIVE -> true

        else -> false
    }

    /** True when the failure is about one request and is worth one more attempt. */
    fun isRetryable(error: AppError): Boolean = !stopsBatch(error) && error.httpStatus != 429

    /**
     * Runs one lookup, and one more if the only problem was the request itself.
     *
     * A club whose lookups were lost to a dropped connection used to be left on its initials;
     * one extra request is cheaper than a badge that never arrives. A closed source is not
     * retried — the caller stops the sweep instead.
     */
    suspend fun <T> attempt(call: suspend () -> Outcome<T>): Outcome<T> {
        val first = call()
        if (first is Outcome.Success) return first
        val error = (first as Outcome.Failure).error
        return if (isRetryable(error)) call() else first
    }
}
