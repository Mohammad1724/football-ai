package com.footballai.engine

import com.footballai.engine.eval.BacktestConfig
import com.footballai.engine.eval.Backtester
import com.footballai.engine.eval.BinarySample
import com.footballai.engine.eval.HistoricalRecord
import com.footballai.engine.eval.Metrics
import com.footballai.engine.eval.OutcomeSample
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluationTest {

    @Test
    fun `perfect predictions have zero brier and log loss`() {
        val samples = List(10) { OutcomeSample(1.0, 0.0, 0.0, MatchOutcome.HOME_WIN) }
        assertEquals(0.0, Metrics.brier1x2(samples), 1e-12)
        assertEquals(0.0, Metrics.logLoss1x2(samples), 1e-12)
        assertEquals(1.0, Metrics.accuracy1x2(samples), 1e-12)
    }

    @Test
    fun `brier score matches a hand computed example`() {
        val samples = listOf(
            OutcomeSample(0.5, 0.3, 0.2, MatchOutcome.HOME_WIN),
            OutcomeSample(0.2, 0.3, 0.5, MatchOutcome.AWAY_WIN),
        )
        // First: (0.5-1)^2 + (0.3)^2 + (0.2)^2 = 0.25 + 0.09 + 0.04 = 0.38
        // Second: (0.2)^2 + (0.3)^2 + (0.5-1)^2 = 0.04 + 0.09 + 0.25 = 0.38
        assertEquals(0.38, Metrics.brier1x2(samples), 1e-12)
    }

    @Test
    fun `log loss penalises confident mistakes more than uncertainty`() {
        val confident = listOf(OutcomeSample(0.95, 0.03, 0.02, MatchOutcome.AWAY_WIN))
        val unsure = listOf(OutcomeSample(0.4, 0.3, 0.3, MatchOutcome.AWAY_WIN))
        assertTrue(Metrics.logLoss1x2(confident) > Metrics.logLoss1x2(unsure))
    }

    @Test
    fun `base rate brier is worse than a sharp model`() {
        val samples = listOf(
            OutcomeSample(0.6, 0.25, 0.15, MatchOutcome.HOME_WIN),
            OutcomeSample(0.2, 0.3, 0.5, MatchOutcome.AWAY_WIN),
            OutcomeSample(0.5, 0.3, 0.2, MatchOutcome.HOME_WIN),
            OutcomeSample(0.25, 0.35, 0.4, MatchOutcome.DRAW),
        )
        assertTrue(Metrics.baseRateBrier(samples) > Metrics.brier1x2(samples))
    }

    @Test
    fun `binary metrics are consistent`() {
        val samples = listOf(
            BinarySample(0.8, true),
            BinarySample(0.7, true),
            BinarySample(0.3, false),
            BinarySample(0.2, false),
        )
        assertEquals(0.065, Metrics.brierBinary(samples), 1e-12)
        assertEquals(1.0, Metrics.accuracyBinary(samples), 1e-12)
        assertTrue(Metrics.logLossBinary(samples) > 0.0)
    }

    @Test
    fun `calibration bins are populated and ordered`() {
        val samples = (0 until 100).map { i ->
            val p = (i + 0.5) / 100.0
            BinarySample(p, i % 2 == 0)
        }
        val bins = Metrics.calibration(samples, bins = 10)
        assertEquals(10, bins.size)
        assertEquals(100, bins.sumOf { it.count })
        assertTrue(bins.all { it.upperBound > it.lowerBound })
        assertTrue(Metrics.expectedCalibrationError(bins).isFinite())
    }

    @Test
    fun `empty samples return NaN instead of crashing`() {
        assertTrue(Metrics.brier1x2(emptyList()).isNaN())
        assertTrue(Metrics.logLoss1x2(emptyList()).isNaN())
        assertTrue(Metrics.brierBinary(emptyList()).isNaN())
    }

    @Test
    fun `quality grade reflects missing history`() {
        val nothing = DataQualityAssessor.assess(QualityInputs())
        assertEquals(DataQualityLevel.INSUFFICIENT, nothing.level)
        assertTrue(nothing.missing.isNotEmpty())
        assertTrue(nothing.notes.isNotEmpty())

        val rich = DataQualityAssessor.assess(
            QualityInputs(
                competitionHistoricalMatches = 400,
                homeMatchesUsed = 25,
                awayMatchesUsed = 22,
                hasLineups = true,
                hasInjuries = true,
                hasOdds = true,
                hasLiveStatistics = true,
                hasExpectedGoalsData = true,
                hasStandings = true,
                isLive = true,
            ),
        )
        assertEquals(DataQualityLevel.HIGH, rich.level)
        assertTrue(rich.completeness > 0.9)
    }

    @Test
    fun `backtest never uses future matches`() {
        val league = TestLeagueFactory.create(seed = 21L, teamCount = 8, seasons = 6)
        val records = league.matches.mapIndexed { index, match ->
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
        val report = Backtester(BacktestConfig(refitEveryMatches = 120)).run(records)

        assertTrue(report.matchesEvaluated > 100, "expected a meaningful sample, got ${report.matchesEvaluated}")
        assertTrue(report.model.brier.isFinite())
        assertTrue(report.model.brier > 0.0)
        assertTrue(report.model.brier < report.baseRate, "the model must beat the base rate on its own synthetic data")
        assertTrue(report.calibration.isNotEmpty())
        assertTrue(report.matchesEvaluated <= records.size)
        assertTrue(report.byCompetition.containsKey("SYN"))
        assertTrue(report.matchesEvaluated == report.model.count)
    }

    @Test
    fun `backtest handles an empty input gracefully`() {
        val report = Backtester().run(emptyList())
        assertEquals(0, report.matchesEvaluated)
        assertTrue(report.model.brier.isNaN())
    }
}
