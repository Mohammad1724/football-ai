package com.footballai.engine

import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.eval.BacktestConfig
import com.footballai.engine.eval.Backtester
import com.footballai.engine.eval.HistoricalRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backtest must measure the model that actually runs: production
 * Dixon-Coles iterations and xG blend, and the production calibration
 * applied inside the walk-forward on resolved forecasts only.
 */
class BacktestAlignmentTest {

    @Test
    fun `backtest defaults match production`() {
        val config = BacktestConfig()
        assertEquals(400, config.dixonColesMaxIterations, "production fits with 400 iterations")
        assertEquals(0.7, config.xgBlendWeight, "production blends xG at 0.7")
        assertTrue(config.calibrationEnabled, "production serves calibrated probabilities")
    }

    @Test
    fun `small backtests are unchanged by calibration`() {
        val records = smallLeagueRecords()
        val on = Backtester(BacktestConfig(refitEveryMatches = 120)).samples(records)
        val off = Backtester(BacktestConfig(refitEveryMatches = 120, calibrationEnabled = false)).samples(records)
        assertTrue(on.size > 100, "expected a meaningful sample, got ${on.size}")
        assertEquals(off.size, on.size)
        // 224 matches in total, so the resolved pool never reaches the calibrator's
        // 400-sample minimum: it returns NONE and the two runs must agree exactly.
        // (An earlier version of this test used six seasons — 672 matches — and the
        // calibration legitimately switched on past 400 resolved forecasts.)
        for ((a, b) in on.zip(off)) {
            assertEquals(a.matchId, b.matchId)
            assertEquals(a.homeWin, b.homeWin, 1e-12)
            assertEquals(a.draw, b.draw, 1e-12)
            assertEquals(a.awayWin, b.awayWin, 1e-12)
        }
        // With no odds and no calibration, the headline triple is the raw triple.
        for (sample in on) {
            assertEquals(sample.homeWin, sample.rawHomeWin, 1e-12)
            assertEquals(sample.draw, sample.rawDraw, 1e-12)
            assertEquals(sample.awayWin, sample.rawAwayWin, 1e-12)
        }
    }

    @Test
    fun `raw triple is recorded on every sample`() {
        val samples = Backtester(BacktestConfig(refitEveryMatches = 120)).samples(smallLeagueRecords())
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            assertTrue(sample.rawHomeWin.isFinite() && sample.rawDraw.isFinite() && sample.rawAwayWin.isFinite())
            assertEquals(1.0, sample.rawHomeWin + sample.rawDraw + sample.rawAwayWin, 1e-9)
        }
    }

    @Test
    fun `calibration is applied once enough resolved forecasts exist`() {
        // Two teams, alternating 1-0 and 0-1, never a draw — while the model
        // keeps predicting a large draw probability. That is exactly the
        // systematic miscalibration the production calibrator must repair.
        val config = BacktestConfig(
            refitEveryMatches = 120,
            calibrator = Calibrator.Config(
                minSamples = 200,
                minHoldoutSamples = 50,
                minSelectionSamples = 20,
            ),
        )
        val samples = Backtester(config).samples(noDrawRecords(900))
        assertTrue(samples.size > 500, "expected a meaningful sample, got ${samples.size}")
        val late = samples.takeLast(samples.size / 3)
        assertTrue(
            late.any { it.draw != it.rawDraw },
            "calibration must change at least one late forecast",
        )
        assertTrue(
            late.sumOf { it.draw } < late.sumOf { it.rawDraw },
            "calibration must shrink the draw probability that never occurs",
        )
    }

    private fun smallLeagueRecords(): List<HistoricalRecord> {
        // Two seasons, eight teams: 224 matches — deliberately below the
        // calibrator's 400-sample minimum, so no calibration can switch on.
        val league = TestLeagueFactory.create(seed = 21L, teamCount = 8, seasons = 2)
        return league.matches.mapIndexed { index, match ->
            HistoricalRecord(
                matchId = "m$index",
                competitionId = "SYN",
                homeTeamId = match.homeTeamId,
                awayTeamId = match.awayTeamId,
                homeGoals = match.homeGoals,
                awayGoals = match.awayGoals,
                kickoffEpochMs = match.kickoffEpochMs,
            )
        }
    }

    private fun noDrawRecords(count: Int): List<HistoricalRecord> {
        val start = 1_700_000_000_000L
        val day = 86_400_000L
        return List(count) { i ->
            val homeWins = i % 2 == 0
            HistoricalRecord(
                matchId = "nd$i",
                competitionId = "SYN",
                homeTeamId = "A",
                awayTeamId = "B",
                homeGoals = if (homeWins) 1 else 0,
                awayGoals = if (homeWins) 0 else 1,
                kickoffEpochMs = start + i * day,
            )
        }
    }
}
