package com.footballai.engine

import com.footballai.engine.eval.BacktestConfig
import com.footballai.engine.eval.Backtester
import com.footballai.engine.eval.HistoricalRecord
import com.footballai.engine.model.ExplanationCode
import com.footballai.engine.model.LambdaAdjustmentLabel
import com.footballai.engine.ratings.AvailabilityImpact
import com.footballai.engine.ratings.AvailabilityImpactConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilityImpactTest {

    @Test
    fun `zero strength disables the feature`() {
        assertNull(
            AvailabilityImpact.adjustment(0.30, 0.0, AvailabilityImpactConfig(strength = 0.0)),
        )
    }

    @Test
    fun `missing data for either side produces no adjustment`() {
        assertNull(AvailabilityImpact.adjustment(null, 0.10))
        assertNull(AvailabilityImpact.adjustment(0.10, null))
        assertNull(AvailabilityImpact.adjustment(null, null))
    }

    @Test
    fun `equal missing shares produce no adjustment`() {
        assertNull(AvailabilityImpact.adjustment(0.12, 0.12))
    }

    @Test
    fun `a negligible difference produces no adjustment`() {
        // 0.3 * 0.01 = 0.003, below the 0.005 visibility floor.
        assertNull(AvailabilityImpact.adjustment(0.05, 0.06))
    }

    @Test
    fun `home side missing more lowers home and raises away symmetrically`() {
        val adjustment = assertNotNull(AvailabilityImpact.adjustment(0.20, 0.0))
        assertEquals(LambdaAdjustmentLabel.INJURIES, adjustment.label)
        assertEquals(ExplanationCode.INJURY_ABSENCE, adjustment.reason)
        // delta = 0.3 * (0.0 - 0.20) = -0.06
        assertEquals(0.94, adjustment.homeFactor, 1e-9)
        assertEquals(1.06, adjustment.awayFactor, 1e-9)
    }

    @Test
    fun `away side missing more favours the home team`() {
        val adjustment = assertNotNull(AvailabilityImpact.adjustment(0.0, 0.20))
        assertEquals(1.06, adjustment.homeFactor, 1e-9)
        assertEquals(0.94, adjustment.awayFactor, 1e-9)
    }

    @Test
    fun `the tilt is capped however large the difference`() {
        val adjustment = assertNotNull(AvailabilityImpact.adjustment(1.0, 0.0))
        assertEquals(0.90, adjustment.homeFactor, 1e-9)
        assertEquals(1.10, adjustment.awayFactor, 1e-9)
    }

    @Test
    fun `shares outside zero to one are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AvailabilityImpact.adjustment(1.2, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            AvailabilityImpact.adjustment(0.0, -0.1)
        }
    }

    private fun records(withShares: Boolean): List<HistoricalRecord> {
        val league = TestLeagueFactory.create(seed = 21L, teamCount = 8, seasons = 6)
        return league.matches.mapIndexed { index, match ->
            HistoricalRecord(
                matchId = "m$index",
                competitionId = "SYN",
                homeTeamId = match.homeTeamId,
                awayTeamId = match.awayTeamId,
                homeGoals = match.homeGoals,
                awayGoals = match.awayGoals,
                kickoffEpochMs = match.kickoffEpochMs,
                homeMissingShare = if (withShares) 0.30 else null,
                awayMissingShare = if (withShares) 0.0 else null,
            )
        }
    }

    @Test
    fun `backtester ignores the feature when no shares are recorded`() {
        val off = Backtester(BacktestConfig(refitEveryMatches = 120)).samples(records(false))
        val onNoData = Backtester(
            BacktestConfig(refitEveryMatches = 120, availabilityStrength = 0.3),
        ).samples(records(false))
        assertEquals(off.size, onNoData.size)
        assertTrue(off.zip(onNoData).all { (a, b) -> a.homeWin == b.homeWin && a.awayWin == b.awayWin })
    }

    @Test
    fun `backtester applies the feature when shares are recorded`() {
        val off = Backtester(BacktestConfig(refitEveryMatches = 120)).samples(records(true))
        val on = Backtester(
            BacktestConfig(refitEveryMatches = 120, availabilityStrength = 0.3),
        ).samples(records(true))
        assertEquals(off.size, on.size)
        assertTrue(on.isNotEmpty())
        // Every forecast has the home side missing 30% of its minutes, so the
        // home win probability must fall on average once the feature is on.
        val meanOff = off.map { it.unalignedHome }.average()
        val meanOn = on.map { it.unalignedHome }.average()
        assertTrue(meanOn < meanOff, "expected home probability to drop: $meanOn vs $meanOff")
    }
}
