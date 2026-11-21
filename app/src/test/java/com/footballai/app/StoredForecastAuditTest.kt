package com.footballai.app

import com.footballai.app.data.repository.ForecastLeadTime
import com.footballai.app.data.repository.ForecastProbabilityBand
import com.footballai.app.data.repository.StoredForecastAudit
import com.footballai.app.data.repository.StoredForecastObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StoredForecastAuditTest {
    private val day = 86_400_000L
    private fun sample(p: Triple<Double, Double, Double>, goals: Pair<Int, Int>, correct: Boolean?, lead: Long, league: String = "l") =
        StoredForecastObservation(p.first, p.second, p.third, goals.first, goals.second, correct,
            10 * day - lead, 10 * day, league, league)

    @Test fun `groups compare predicted average to actual outcomes and omit invalid rows`() {
        val rows = listOf(
            sample(Triple(0.4, 0.3, 0.3), 1 to 0, true, 100),
            sample(Triple(0.6, 0.2, 0.2), 1 to 1, false, day),
            sample(Triple(0.8, 0.1, 0.1), 2 to 0, true, 4 * day, "other"),
            sample(Triple(0.9, 0.05, 0.05), 2 to 0, null, 100),
        )
        val audit = StoredForecastAudit.from(rows)
        assertEquals(1, audit.bands.getValue(ForecastProbabilityBand.BELOW_50).correct)
        assertEquals(0, audit.bands.getValue(ForecastProbabilityBand.FROM_50_TO_70).correct)
        assertEquals(0.8, audit.bands.getValue(ForecastProbabilityBand.AT_LEAST_70).averageTopProbability)
        assertEquals(1, audit.leadTimes.getValue(ForecastLeadTime.UNDER_DAY).count)
        assertEquals(1, audit.leadTimes.getValue(ForecastLeadTime.ONE_TO_THREE_DAYS).count)
        assertEquals(1, audit.leadTimes.getValue(ForecastLeadTime.OVER_THREE_DAYS).count)
        assertEquals(2, audit.leagues.first().summary.count)
    }

    @Test fun `invalid or contradictory snapshots never become measured wrong answers`() {
        assertFalse(sample(Triple(Double.NaN, .2, .8), 1 to 0, false, 100).valid())
        assertFalse(sample(Triple(.8, .1, .1), 2 to 0, false, 100).valid())
        assertFalse(sample(Triple(.8, .1, .1), -1 to 0, false, 100).valid())
        assertFalse(sample(Triple(.8, .1, .1), 2 to 0, true, 0).valid())
    }
}
