package com.footballai.engine

import com.footballai.engine.calibration.CalibrationMethod
import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.confidence.ConfidenceAssessor
import com.footballai.engine.confidence.ConfidenceInput
import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.QualityNoteCode
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.poisson.ScoreMatrix
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import kotlin.math.abs
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
        // with the same correction applied to the reduced scoring rates.
        val expected = ScoreMatrix(lambdaHome * share, lambdaAway * share, rho)
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
}
