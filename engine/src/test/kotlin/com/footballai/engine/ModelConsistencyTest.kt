package com.footballai.engine

import com.footballai.engine.calibration.CalibrationMethod
import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.CalibrationSample
import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.confidence.ConfidenceAssessor
import com.footballai.engine.confidence.ConfidenceInput
import com.footballai.engine.eval.BinarySample
import com.footballai.engine.eval.Metrics
import com.footballai.engine.eval.OutcomeSample
import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.QualityNoteCode
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.poisson.ScoreMatrix
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Three statements the engine made about itself and did not keep.
 *
 * Each test names one of them, so a regression fails with a sentence a reader can
 * act on rather than a number that moved.
 */
class ModelConsistencyTest {

    /**
     * Half-time markets are derived from the same match as the full-time ones, so they
     * must come from the same model. They used to be rebuilt with rho = 0, which is the
     * one place the correction matters most: a half-time grid is mostly 0-0, 1-0, 0-1, 1-1.
     */
    @Test
    fun `half time keeps the dixon coles correction`() {
        val lambdaHome = 1.62
        val lambdaAway = 1.08
        val rho = 0.07
        val share = 0.44

        val markets = MarketCalculator.fromMatrix(
            matrix = ScoreMatrix(lambdaHome, lambdaAway, rho),
            firstHalfShare = share,
            topScoreCount = 6,
        )
        val firstHalf = assertNotNull(markets.firstHalf, "first-half markets must be published")

        // The expectation is built the long way on purpose: a half is a match of its own
        // with the same correction applied to the reduced scoring rates, and those rates
        // are read back from the matrix that produced the full-time numbers.
        val base = ScoreMatrix(lambdaHome, lambdaAway, rho)
        val expected = ScoreMatrix(base.impliedHomeGoals() * share, base.impliedAwayGoals() * share, rho)
        assertEquals(expected.homeWin(), firstHalf.homeWin, 1e-12)
        assertEquals(expected.draw(), firstHalf.draw, 1e-12)
        assertEquals(expected.awayWin(), firstHalf.awayWin, 1e-12)
        assertEquals(expected.bttsYes(), firstHalf.bttsYes, 1e-12)
        assertEquals(expected.over(0.5), firstHalf.over05, 1e-12)

        val independent = MarketCalculator.fromMatrix(
            matrix = ScoreMatrix(lambdaHome, lambdaAway, 0.0),
            firstHalfShare = share,
            topScoreCount = 6,
        )
        val plain = assertNotNull(independent.firstHalf)
        assertTrue(
            abs(plain.homeWin - firstHalf.homeWin) > 1e-9,
            "the correction must change the half-time numbers, not just be present in the code",
        )
    }

    /**
     * A calibrated or market-aligned table moves probability between full-time outcomes.
     * The half-time row above it has to move with it, or one table is describing two
     * different matches.
     */
    @Test
    fun `half time follows the matrix the full time numbers came from`() {
        val plain = ScoreMatrix(1.62, 1.08, 0.07)
        val (home, draw, away) = Triple(plain.homeWin(), plain.draw(), plain.awayWin())
        val aligned = plain.rescaledToOutcomes(home * 0.85, draw * 1.45, away * 0.85)

        val plainHalf = assertNotNull(
            MarketCalculator.fromMatrix(plain, firstHalfShare = 0.44, topScoreCount = 6).firstHalf,
        )
        val alignedHalf = assertNotNull(
            MarketCalculator.fromMatrix(aligned, firstHalfShare = 0.44, topScoreCount = 6).firstHalf,
        )

        assertTrue(
            alignedHalf.draw > plainHalf.draw + 1e-6,
            "a lifted full-time draw must lift the half-time draw, got ${alignedHalf.draw} vs ${plainHalf.draw}",
        )
        assertTrue(
            alignedHalf.homeWin < plainHalf.homeWin - 1e-6,
            "and the home win has to come down with it, got ${alignedHalf.homeWin} vs ${plainHalf.homeWin}",
        )
    }

    /**
     * A forecast about two teams is only as well evidenced as its thinner side. The
     * opponent's long history never makes up for our own short one, so the reported
     * sample must follow the smaller of the two.
     */
    @Test
    fun `reported sample follows the thinner team`() {
        val assessment = ConfidenceAssessor.assess(
            ConfidenceInput(
                competitionHistoricalMatches = 500,
                homeMatchesUsed = 34,
                awayMatchesUsed = 4,
                probabilities = Triple(0.5, 0.27, 0.23),
                dataCompleteness = 0.7,
                calibrationSamples = 600,
                calibrationError = 0.02,
                freshnessMinutes = 60,
            ),
        )
        assertEquals(4, assessment.effectiveSample, "the number on screen must be the weaker side, not the stronger")
        assertTrue(
            assessment.gaps.contains(com.footballai.engine.confidence.EvidenceGap.SMALL_TEAM_SAMPLE),
            "thin evidence must stay visible",
        )
    }

    /**
     * The quality notes used to say "uncalibrated" on every prediction, including the
     * calibrated ones, because the assessor stamped it without knowing. It must say it
     * only when it is true, and never twice.
     */
    @Test
    fun `calibration is stated once and truthfully`() {
        val plainNotes = DataQualityAssessor.assess(
            QualityInputs(competitionHistoricalMatches = 200, homeMatchesUsed = 20, awayMatchesUsed = 20),
        ).notes.map { it.code }
        assertFalse(
            plainNotes.contains(QualityNoteCode.MODEL_NOT_CALIBRATED),
            "availability alone cannot tell whether a calibration was applied",
        )

        val quality = DataQuality(
            level = DataQualityLevel.MEDIUM,
            completeness = 0.6,
            missing = emptyList(),
            historicalMatchesUsed = 200,
        )
        val input = PreMatchInput(
            home = TeamStrength("H", 1.1, 0.95, 1600.0, 30),
            away = TeamStrength("A", 0.98, 1.05, 1400.0, 28),
            baseline = LeagueBaseline(1.5, 1.15, 200),
            quality = quality.copy(notes = emptyList()),
            asOfEpochMs = 1_700_000_000_000L,
            calibration = CalibrationModel(
                method = CalibrationMethod.TEMPERATURE,
                temperature = 1.05,
                sampleCount = 820,
            ),
        )
        val result = MatchPredictionEngine().predictPreMatch(input)
        val codes = result.quality.notes.map { it.code }
        assertEquals(1, codes.count { it == QualityNoteCode.CALIBRATION_APPLIED }, "exactly one calibration statement")
        assertFalse(codes.contains(QualityNoteCode.CALIBRATION_UNAVAILABLE))
        assertFalse(codes.contains(QualityNoteCode.MODEL_NOT_CALIBRATED), "a calibrated forecast must not deny it")
        assertNotNull(result.calibration)
    }

    /**
     * The screen shows a home-win reliability chart and one calibration-error figure.
     * That figure used to be the chart's own column; the calibrator, meanwhile, accepts or
     * rejects a correction on an error pooled over all three outcomes. Two measures under
     * one label is how a screen ends up explaining the model with one number and grading it
     * with another.
     */
    @Test
    fun `the reported calibration error covers all three outcomes`() {
        val samples = listOf(
            OutcomeSample(0.9, 0.05, 0.05, MatchOutcome.HOME_WIN),
            OutcomeSample(0.9, 0.05, 0.05, MatchOutcome.AWAY_WIN),
        )
        val homeColumn = Metrics.expectedCalibrationError(
            Metrics.calibration(samples.map { BinarySample(it.homeWin, it.actual == MatchOutcome.HOME_WIN) }, 10),
        )
        val pooled = Metrics.expectedCalibrationErrorMultiClass(samples, 10)

        // Home column: two forecasts at 0.9, one home win happened, error 0.4.
        assertEquals(0.4, homeColumn, 1e-9)
        // Pooled over three outcomes: (0.8 + 0.1 + 0.9) / 6 observations = 0.3.
        assertEquals(0.3, pooled, 1e-9)
        assertTrue(abs(homeColumn - pooled) > 1e-6, "the two measures are not interchangeable")
    }

    /**
     * Choosing a correction and judging it on the same rows turns the reported improvement
     * into the best of a few noisy guesses. The chosen method must be picked on one slice
     * and scored on another that nothing has looked at.
     */
    @Test
    fun `a correction is chosen on one slice and judged on another`() {
        val random = Random(19)
        val samples = (0 until 2000).map { _ ->
            val roll = random.nextDouble()
            val actual = when {
                roll < 0.45 -> MatchOutcome.HOME_WIN
                roll < 0.72 -> MatchOutcome.DRAW
                else -> MatchOutcome.AWAY_WIN
            }
            // A model that always claims 0.70 for the home side: it needs flattening, and
            // the need is visible on any slice of this source.
            CalibrationSample(0.70, 0.18, 0.12, actual)
        }
        val model = Calibrator.fit(samples)
        assertTrue(model.isApplied, "an overconfident source must be corrected")
        // 2000 rows: 500 withheld for judging, 375 reserved for choosing, 1125 fitted on.
        assertEquals(2000, model.sampleCount)
        assertEquals(500, model.holdoutCount)
        assertEquals(375, model.selectionCount)
        assertTrue(model.holdoutLogLossAfter < model.holdoutLogLossBefore)
    }
}
