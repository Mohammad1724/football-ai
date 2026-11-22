package com.footballai.app

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.remote.CrestQueuePolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line between "this request failed" and "this source is closed".
 *
 * It decides whether one bad minute costs a club its badge for a week, and whether a spent
 * quota is hammered for the rest of the afternoon.
 */
class CrestQueuePolicyTest {

    @Test
    fun aDroppedConnectionDoesNotCloseTheQueue() {
        val error = AppError(ErrorCode.NETWORK_UNAVAILABLE)
        assertFalse(CrestQueuePolicy.stopsBatch(error))
        assertTrue(CrestQueuePolicy.isRetryable(error))
    }

    @Test
    fun quotaRateAndRefusedKeysCloseTheQueue() {
        val closing = listOf(
            ErrorCode.RATE_LIMITED,
            ErrorCode.QUOTA_EXHAUSTED,
            ErrorCode.MISSING_API_KEY,
            ErrorCode.INVALID_API_KEY,
            ErrorCode.ACCESS_DENIED,
            ErrorCode.PLAN_RESTRICTED,
            ErrorCode.SUBSCRIPTION_INACTIVE,
        )
        for (code in closing) {
            val error = AppError(code)
            assertTrue(CrestQueuePolicy.stopsBatch(error), "$code should stop the batch")
            assertFalse(CrestQueuePolicy.isRetryable(error), "$code should not be retried")
        }
    }

    @Test
    fun anHttpTooManyRequestsClosesTheQueueEvenWhenTheCodeIsGeneric() {
        // Some endpoints answer 429 with a code that is not the rate-limit one; the status is
        // the fact, so it is trusted on its own.
        assertFalse(CrestQueuePolicy.isRetryable(AppError(ErrorCode.REQUEST_FAILED, httpStatus = 429)))
    }

    @Test
    fun aTransientFailureIsTriedExactlyOnceMore() = runBlocking {
        var calls = 0
        val result = CrestQueuePolicy.attempt {
            calls++
            Outcome.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE))
        }
        assertEquals(2, calls)
        assertTrue(result is Outcome.Failure)
    }

    @Test
    fun aClosedSourceIsNotAskedAgain() = runBlocking {
        var calls = 0
        CrestQueuePolicy.attempt {
            calls++
            Outcome.Failure(AppError(ErrorCode.QUOTA_EXHAUSTED))
        }
        assertEquals(1, calls)
    }

    @Test
    fun anAnswerOnTheSecondAttemptIsKept() = runBlocking {
        var calls = 0
        val result = CrestQueuePolicy.attempt {
            calls++
            if (calls == 1) {
                Outcome.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE))
            } else {
                Outcome.Success("https://crest/example.png")
            }
        }
        assertEquals(2, calls)
        assertEquals("https://crest/example.png", (result as Outcome.Success).value)
    }

    @Test
    fun aFirstAnswerIsNeverRepeated() = runBlocking {
        var calls = 0
        CrestQueuePolicy.attempt {
            calls++
            Outcome.Success<String?>(null)
        }
        assertEquals(1, calls)
    }
}
