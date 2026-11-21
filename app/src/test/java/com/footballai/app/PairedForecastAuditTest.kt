package com.footballai.app

import com.footballai.app.data.repository.PairedForecastAudit
import com.footballai.app.data.repository.StoredForecastObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairedForecastAuditTest {
    private val day = 86_400_000L
    private fun observation(time: Long, home: Double, draw: Double, away: Double, correct: Boolean?, kickoff: Long = 10 * day) =
        StoredForecastObservation(home, draw, away, 1, 0, correct, time, kickoff, "l", "League")

    @Test fun `paired metrics use the same results for both forecasts`() {
        val early = observation(8 * day, .4, .4, .2, true)
        val late = observation(9 * day, .2, .3, .5, false)
        val result = PairedForecastAudit.from(listOf(early to late))
        assertEquals(1, result.count)
        assertEquals(1, result.earlyCorrect)
        assertEquals(0, result.lateCorrect)
        assertEquals(.56, result.earlyBrier, 1e-10)
        assertEquals(.98, result.lateBrier, 1e-10)
        assertTrue(result.earlyLogLoss < result.lateLogLoss)
    }

    @Test fun `invalid times probabilities results and reschedules are excluded`() {
        val early = observation(8 * day, .6, .2, .2, true)
        val late = observation(9 * day, .6, .2, .2, true)
        val invalid = listOf(
            late to late,
            early to late.copy(createdAt = 10 * day),
            early to late.copy(createdAt = 9 * day - 1),
            early to late.copy(kickoff = 11 * day),
            early to late.copy(homeGoals = 0, awayGoals = 1, savedCorrect = false),
            early to late.copy(home = Double.NaN),
            early.copy(savedCorrect = false) to late,
        )
        assertEquals(0, PairedForecastAudit.from(invalid).count)
        assertEquals(1, PairedForecastAudit.from(listOf(early to late)).count)
    }
}
