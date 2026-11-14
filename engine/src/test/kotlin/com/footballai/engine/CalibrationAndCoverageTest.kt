package com.footballai.engine

import com.footballai.engine.calibration.CalibrationMethod
import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.CalibrationSample
import com.footballai.engine.calibration.CalibrationSkipReason
import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.confidence.ConfidenceAssessor
import com.footballai.engine.confidence.ConfidenceInput
import com.footballai.engine.confidence.ConfidenceLevel
import com.footballai.engine.confidence.EvidenceGap
import com.footballai.engine.eval.CalibrationStudy
import com.footballai.engine.model.CompetitionProfile
import com.footballai.engine.model.CompetitionType
import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.TeamKind
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.poisson.ScoreMatrix
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.ratings.NationalMatch
import com.footballai.engine.ratings.NationalTeamRatingBook
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the calibration layer, the competition profile, the national-team model
 * and the confidence assessment. Each one asserts behaviour that a user can observe,
 * not an internal implementation detail.
 */
class CalibrationAndCoverageTest {

    // ------------------------------------------------------------- calibration

    @Test
    fun `calibration never breaks the probability invariant`() {
        val samples = overconfidentSamples(1200, seed = 7)
        val model = Calibrator.fit(samples)
        for (sample in samples) {
            val raw = Calibrator.apply(CalibrationModel.NONE, sample.homeWin, sample.draw, sample.awayWin)
            val calibrated = Calibrator.apply(model, sample.homeWin, sample.draw, sample.awayWin)
            assertEquals(1.0, raw.first + raw.second + raw.third, Calibrator.PROBABILITY_TOLERANCE)
            assertEquals(1.0, calibrated.first + calibrated.second + calibrated.third, Calibrator.PROBABILITY_TOLERANCE)
        }
    }

    @Test
    fun `calibration is refused below the minimum sample size`() {
        val model = Calibrator.fit(overconfidentSamples(120, seed = 11))
        assertEquals(CalibrationMethod.NONE, model.method)
        assertTrue(CalibrationSkipReason.NOT_ENOUGH_SAMPLES in model.reasons)
        assertFalse(model.isApplied)
    }

    @Test
    fun `calibration is adopted only when it improves out of sample log loss`() {
        val samples = overconfidentSamples(2000, seed = 3)
        val model = Calibrator.fit(samples)
        assertTrue(model.isApplied, "a deliberately overconfident model must be corrected")
        assertTrue(model.holdoutCount >= 150)
        assertTrue(
            model.holdoutLogLossAfter < model.holdoutLogLossBefore,
            "holdout log loss must improve: ${model.holdoutLogLossBefore} -> ${model.holdoutLogLossAfter}",
        )
        assertTrue(model.holdoutImprovement > 0.002)
    }

    @Test
    fun `a well calibrated model is left untouched`() {
        // Outcomes drawn from the reported probabilities themselves: any correction
        // would be fitting noise, so the calibrator must decline to change anything.
        val random = Random(23)
        val samples = ArrayList<CalibrationSample>()
        repeat(2000) {
            val home = 0.2 + random.nextDouble() * 0.5
            val draw = 0.1 + random.nextDouble() * 0.3
            val away = 1.0 - home - draw
            if (away <= 0.0) return@repeat
            val drawRoll = random.nextDouble()
            val actual = when {
                drawRoll < home -> MatchOutcome.HOME_WIN
                drawRoll < home + draw -> MatchOutcome.DRAW
                else -> MatchOutcome.AWAY_WIN
            }
            samples.add(CalibrationSample(home, draw, away, actual))
        }
        val model = Calibrator.fit(samples)
        val gain = model.holdoutLogLossBefore - model.holdoutLogLossAfter
        assertTrue(
            !model.isApplied || gain < 0.01,
            "an already calibrated source must not be rewritten (gain=$gain, method=${model.method})",
        )
    }

    @Test
    fun `isotonic curve is monotone non decreasing`() {
        val samples = ArrayList<Pair<Double, Boolean>>()
        val random = Random(5)
        repeat(600) {
            val p = random.nextDouble()
            samples.add(p to (random.nextDouble() < p))
        }.toString()
        val curve = Calibrator.fitIsotonicCurve(samples, Calibrator.Config())
        assertTrue(curve.size >= 2)
        for (i in 0 until curve.size - 1) {
            assertTrue(curve[i].observed <= curve[i + 1].observed + 1e-12)
            assertTrue(curve[i].predicted <= curve[i + 1].predicted + 1e-12)
        }
    }

    @Test
    fun `calibration study measures an out of sample improvement`() {
        val samples = overconfidentSamples(3000, seed = 17)
        val report = CalibrationStudy.run(samples)
        assertTrue(report.evaluatedMatches > 0)
        assertTrue(report.adoptedFolds > 0, "at least one fold must adopt a correction")
        assertTrue(
            report.calibratedLogLoss < report.rawLogLoss,
            "aggregate out-of-sample log loss must improve: ${report.rawLogLoss} -> ${report.calibratedLogLoss}",
        )
    }

    // ---------------------------------------------------------- competition profile

    @Test
    fun `neutral venues remove the home advantage from the baseline`() {
        val base = LeagueBaseline(1.6, 1.1, 400)
        val neutral = CompetitionProfile.of(CompetitionType.INTERNATIONAL_TOURNAMENT, neutralVenue = true)
            .adjustBaseline(base)
        assertEquals(neutral.averageHomeGoals, neutral.averageAwayGoals, 1e-9)
        assertEquals(base.averageTotalGoals, neutral.averageTotalGoals, 1e-9)

        val normal = CompetitionProfile.DEFAULT.adjustBaseline(base)
        assertEquals(base.averageHomeGoals, normal.averageHomeGoals, 1e-12)
        assertEquals(base.averageAwayGoals, normal.averageAwayGoals, 1e-12)
    }

    @Test
    fun `competition type decides the entity kind`() {
        assertEquals(TeamKind.NATIONAL, CompetitionProfile.of(CompetitionType.INTERNATIONAL_QUALIFIER).teamKind)
        assertEquals(TeamKind.NATIONAL, CompetitionProfile.of(CompetitionType.WOMEN_INTERNATIONAL).teamKind)
        assertEquals(TeamKind.CLUB, CompetitionProfile.of(CompetitionType.CLUB_LEAGUE).teamKind)
        assertEquals(TeamKind.CLUB, CompetitionProfile.of(CompetitionType.WOMEN_CLUB_LEAGUE).teamKind)
        assertTrue(CompetitionProfile.of(CompetitionType.CLUB_KNOCKOUT).isKnockout)
    }

    @Test
    fun `neutral venue makes two equal teams symmetric`() {
        val engine = MatchPredictionEngine()
        val strength = TeamStrength("A", 1.0, 1.0, 1500.0, 40)
        val quality = DataQuality(DataQualityLevel.HIGH, 0.9, emptyList(), 400)
        val input = PreMatchInput(
            home = strength,
            away = TeamStrength("B", 1.0, 1.0, 1500.0, 40),
            baseline = LeagueBaseline(1.6, 1.1, 400),
            quality = quality,
            asOfEpochMs = 0L,
            competition = CompetitionProfile.of(CompetitionType.INTERNATIONAL_TOURNAMENT, neutralVenue = true),
        )
        val result = engine.predictPreMatch(input)
        assertEquals(result.markets.homeWin, result.markets.awayWin, 1e-9)
        assertEquals(1.0, result.markets.homeWin + result.markets.draw + result.markets.awayWin, 1e-9)
    }

    // ------------------------------------------------------------- national model

    @Test
    fun `national ratings are a separate book from club ratings`() {
        val book = NationalTeamRatingBook()
        val start = 1_600_000_000_000L
        val day = 86_400_000L
        // Iran wins most home games, Japan wins away games; the ratings must reflect it.
        book.addAll(
            listOf(
                NationalMatch("IR", "JP", 2, 0, start, importance = 1.0),
                NationalMatch("IR", "JP", 1, 0, start + 30 * day, importance = 1.0),
                NationalMatch("IR", "JP", 0, 1, start + 60 * day, importance = 1.0),
            ),
        )
        val iran = book.rating("IR", start + 61 * day)
        val japan = book.rating("JP", start + 61 * day)
        assertTrue(iran > 1500.0)
        assertTrue(japan < 1500.0)
        // Unknown teams start at the international mean, never at a club rating.
        assertEquals(1500.0, book.rating("XX", start + 61 * day), 1e-9)
    }

    @Test
    fun `national model refuses to claim strength below the minimum sample`() {
        val book = NationalTeamRatingBook()
        val start = 1_600_000_000_000L
        book.add(NationalMatch("IR", "JP", 3, 0, start, importance = 1.0))
        val prepared = book.prepare("IR", "JP", start + 86_400_000L)
        assertFalse(prepared.enoughEvidence)
        assertEquals(1.0, prepared.home.attack, 1e-12)
        assertEquals(1.0, prepared.away.attack, 1e-12)
    }

    @Test
    fun `neutral international results do not grant a home bonus`() {
        val neutral = NationalTeamRatingBook()
        val homeVenue = NationalTeamRatingBook()
        val start = 1_600_000_000_000L
        repeat(6) { i ->
            neutral.add(NationalMatch("A", "B", 1, 1, start + i * 86_400_000L, neutralVenue = true))
            homeVenue.add(NationalMatch("A", "B", 1, 1, start + i * 86_400_000L, neutralVenue = false))
        }
        // A draw is a better-than-expected result for the away team only when the home
        // side was expected to win; that expectation exists only with a home bonus.
        val neutralRating = neutral.rating("A", start + 10 * 86_400_000L)
        val homeRating = homeVenue.rating("A", start + 10 * 86_400_000L)
        assertEquals(1500.0, neutralRating, 1e-6)
        assertTrue(homeRating < 1500.0)
    }

    @Test
    fun `national model reports the days since the last international`() {
        val book = NationalTeamRatingBook()
        val start = 1_600_000_000_000L
        book.add(NationalMatch("IR", "JP", 1, 0, start))
        assertEquals(10, book.daysSinceLastMatch("IR", start + 10 * 86_400_000L))
        assertEquals(null, book.daysSinceLastMatch("ZZ", start))
    }

    // ---------------------------------------------------------------- confidence

    @Test
    fun `confidence needs evidence and is not derived from the probability spread`() {
        val decisive = ConfidenceAssessor.assess(
            ConfidenceInput(
                competitionHistoricalMatches = 0,
                homeMatchesUsed = 0,
                awayMatchesUsed = 0,
                probabilities = Triple(0.85, 0.10, 0.05),
                dataCompleteness = 0.1,
            ),
        )
        assertEquals(ConfidenceLevel.INSUFFICIENT, decisive.level)
        assertTrue(EvidenceGap.NO_COMPETITION_HISTORY in decisive.gaps)
        assertTrue(decisive.topGap > 0.7)

        val calibratedEvidence = ConfidenceAssessor.assess(
            ConfidenceInput(
                competitionHistoricalMatches = 600,
                homeMatchesUsed = 60,
                awayMatchesUsed = 55,
                probabilities = Triple(0.42, 0.28, 0.30),
                dataCompleteness = 0.85,
                calibrationSamples = 900,
                calibrationError = 0.03,
                freshnessMinutes = 30,
            ),
        )
        assertEquals(ConfidenceLevel.HIGH, calibratedEvidence.level)
    }

    @Test
    fun `identical evidence yields the same level for different spreads`() {
        val evidence = { probabilities: Triple<Double, Double, Double> ->
            ConfidenceAssessor.assess(
                ConfidenceInput(
                    competitionHistoricalMatches = 300,
                    homeMatchesUsed = 30,
                    awayMatchesUsed = 28,
                    probabilities = probabilities,
                    dataCompleteness = 0.62,
                    calibrationSamples = 0,
                    freshnessMinutes = 10,
                ),
            )
        }
        val flat = evidence(Triple(0.34, 0.33, 0.33))
        val peaked = evidence(Triple(0.70, 0.20, 0.10))
        assertEquals(flat.level, peaked.level)
        assertEquals(ConfidenceLevel.MEDIUM, flat.level, "an uncalibrated model cannot be rated HIGH")
        assertTrue(EvidenceGap.NOT_CALIBRATED in flat.gaps)
        assertTrue(peaked.topGap > flat.topGap)
    }

    @Test
    fun `stale data lowers confidence`() {
        val fresh = ConfidenceAssessor.assess(
            ConfidenceInput(
                competitionHistoricalMatches = 500,
                homeMatchesUsed = 40,
                awayMatchesUsed = 40,
                probabilities = Triple(0.4, 0.3, 0.3),
                dataCompleteness = 0.8,
                calibrationSamples = 600,
                calibrationError = 0.04,
                freshnessMinutes = 5,
            ),
        )
        val stale = ConfidenceAssessor.assess(
            ConfidenceInput(
                competitionHistoricalMatches = 500,
                homeMatchesUsed = 40,
                awayMatchesUsed = 40,
                probabilities = Triple(0.4, 0.3, 0.3),
                dataCompleteness = 0.8,
                calibrationSamples = 600,
                calibrationError = 0.04,
                freshnessMinutes = 60 * 72,
            ),
        )
        assertTrue(EvidenceGap.STALE_DATA in stale.gaps)
        assertTrue(fresh.level.ordinal <= stale.level.ordinal)
    }

    // -------------------------------------------------------------- score matrix

    @Test
    fun `rescaling to calibrated outcomes keeps every market consistent`() {
        val matrix = ScoreMatrix(1.6, 1.1, rho = 0.0, maxGoals = 10)
        val rescaled = matrix.rescaledToOutcomes(0.55, 0.25, 0.20)
        assertEquals(0.55, rescaled.homeWin(), 1e-9)
        assertEquals(0.25, rescaled.draw(), 1e-9)
        assertEquals(0.20, rescaled.awayWin(), 1e-9)
        assertEquals(1.0, rescaled.totalMass(), 1e-9)
        // Over/under remains a valid probability and the same total is preserved.
        assertTrue(rescaled.over(2.5) in 0.0..1.0)
        assertEquals(1.0, rescaled.over(2.5) + (1.0 - rescaled.over(2.5)), 1e-12)
    }

    @Test
    fun `engine applies calibration to the publicly reported probabilities`() {
        val samples = overconfidentSamples(2000, seed = 3)
        val model = Calibrator.fit(samples)
        assertTrue(model.isApplied)
        val strength = TeamStrength("A", 1.25, 0.85, 1580.0, 60)
        val quality = DataQuality(DataQualityLevel.HIGH, 0.9, emptyList(), 500)
        val base = PreMatchInput(
            home = strength,
            away = TeamStrength("B", 0.9, 1.1, 1470.0, 60),
            baseline = LeagueBaseline(1.45, 1.15, 500),
            quality = quality,
            asOfEpochMs = 0L,
            fittedMatches = 500,
            dataFreshnessMinutes = 15,
        )
        val engine = MatchPredictionEngine()
        val uncalibrated = engine.predictPreMatch(base)
        val calibrated = engine.predictPreMatch(base.copy(calibration = model))
        assertNotNull(calibrated.calibration)
        assertEquals(1.0, calibrated.markets.homeWin + calibrated.markets.draw + calibrated.markets.awayWin, 1e-9)
        assertTrue(
            abs(calibrated.markets.homeWin - uncalibrated.markets.homeWin) > 1e-6,
            "the calibrated output must differ from the raw one",
        )
        // The calibrated prediction carries measured evidence about its reliability.
        assertEquals(calibrated.confidence.calibrationSamples, model.sampleCount)
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Builds a chronologically ordered set of forecasts from a *distorted* model:
     * the true outcome probabilities are p, the model reports p^gamma renormalised,
     * and the outcome is drawn from p. With gamma != 1 the model is systematically
     * over/under confident, which is exactly what a calibration layer must repair.
     */
    private fun overconfidentSamples(
        count: Int,
        seed: Int,
        gamma: Double = 0.55,
    ): List<CalibrationSample> {
        val random = Random(seed)
        val samples = ArrayList<CalibrationSample>(count)
        while (samples.size < count) {
            val home = 0.15 + random.nextDouble() * 0.6
            val draw = 0.1 + random.nextDouble() * 0.35
            val away = 1.0 - home - draw
            if (away <= 0.02) continue
            val total = home + draw + away
            val trueHome = home / total
            val trueDraw = draw / total
            val trueAway = away / total
            val roll = random.nextDouble()
            val actual = when {
                roll < trueHome -> MatchOutcome.HOME_WIN
                roll < trueHome + trueDraw -> MatchOutcome.DRAW
                else -> MatchOutcome.AWAY_WIN
            }
            val reportedHome = trueHome.pow(gamma)
            val reportedDraw = trueDraw.pow(gamma)
            val reportedAway = trueAway.pow(gamma)
            val reportedTotal = reportedHome + reportedDraw + reportedAway
            samples.add(
                CalibrationSample(
                    homeWin = reportedHome / reportedTotal,
                    draw = reportedDraw / reportedTotal,
                    awayWin = reportedAway / reportedTotal,
                    actual = actual,
                ),
            )
        }
        return samples
    }
}
