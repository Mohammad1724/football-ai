package com.footballai.app

import com.footballai.app.data.local.LateForecastPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LateForecastPolicyTest {
    private val day = 86_400_000L
    private val kickoff = 10 * day
    private fun eligible(firstId: String = "first", expectedId: String = "first", status: String = "SCHEDULED",
        start: Long = kickoff, capturedKickoff: Long = kickoff, early: Long = 8 * day,
        late: Long = 9 * day, finish: Long = 9 * day + 1) =
        LateForecastPolicy.eligible(firstId, expectedId, status, start, capturedKickoff, early, late, finish)

    @Test fun `only pre kickoff final day captures after an earlier first forecast qualify`() {
        assertTrue(eligible())
        assertFalse(eligible(early = 9 * day))
        assertFalse(eligible(late = 9 * day - 1))
        assertFalse(eligible(late = kickoff, finish = kickoff))
        assertFalse(eligible(finish = kickoff))
        assertFalse(eligible(finish = 9 * day - 1))
    }

    @Test fun `rejects changed kickoff live status or different first forecast`() {
        assertFalse(eligible(status = "LIVE"))
        assertFalse(eligible(start = kickoff + day))
        assertFalse(eligible(expectedId = "other"))
    }
}
