package com.footballai.app

import com.footballai.app.data.repository.AvailabilityFreshness
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityFreshnessTest {
    private val day = AvailabilityFreshness.MAX_AGE_MS
    private val now = 10 * day
    private val kickoff = 11 * day
    private fun usable(updated: Long = now, observed: Long? = now - 1000, savedKickoff: Long? = kickoff,
        currentKickoff: Long = kickoff, at: Long = now, home: Double = .1, away: Double = .2) =
        AvailabilityFreshness.usable(updated, observed, savedKickoff, currentKickoff, at, home, away)

    @Test fun `requires valid recent original observation before kickoff`() {
        assertTrue(usable())
        assertTrue(usable(observed = now - day))
        assertFalse(usable(observed = null)) // all current mirror rows lack provenance
        assertFalse(usable(observed = now - day - 1))
        assertFalse(usable(observed = now + 1))
        assertFalse(usable(updated = now + 1))
        assertFalse(usable(updated = kickoff, observed = kickoff, at = kickoff))
    }

    @Test fun `rejects rescheduled legacy and malformed rows`() {
        assertFalse(usable(savedKickoff = null))
        assertFalse(usable(currentKickoff = kickoff + day))
        assertFalse(usable(home = Double.NaN))
        assertFalse(usable(away = 1.1))
    }
}
