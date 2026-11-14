package com.footballai.engine.confidence

import kotlin.math.abs
import kotlin.math.ln

/**
 * How much evidence stands behind a probability triple.
 *
 * This is *not* a restatement of the probabilities. A 40/30/30 forecast from a
 * well-calibrated model fitted on 900 matches is more trustworthy than a 70/20/10
 * forecast from a model with no history, even though the second distribution looks
 * more decisive. The level is therefore derived from sample size, calibration
 * quality, data completeness and freshness - and the spread of the probabilities is
 * reported separately as [ConfidenceAssessment.uncertainty].
 */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW, INSUFFICIENT }

/** Machine-readable reason why the evidence is not stronger; the UI localises it. */
enum class EvidenceGap {
    INVALID_PROBABILITIES,
    NO_COMPETITION_HISTORY,
    SMALL_COMPETITION_SAMPLE,
    SMALL_TEAM_SAMPLE,
    NOT_CALIBRATED,
    CALIBRATION_ERROR_HIGH,
    SMALL_CALIBRATION_SAMPLE,
    STALE_DATA,
    UNKNOWN_FRESHNESS,
    PARTIAL_DATA,
    EXTREME_UNCERTAINTY,
    UNSUPPORTED_COMPETITION,
}

data class ConfidenceInput(
    val competitionHistoricalMatches: Int,
    val homeMatchesUsed: Int,
    val awayMatchesUsed: Int,
    val probabilities: Triple<Double, Double, Double>,
    /** 0..1 from [com.footballai.engine.quality.DataQualityAssessor]. */
    val dataCompleteness: Double = 0.0,
    /** Resolved predictions the active calibration was fitted on; 0 = uncalibrated. */
    val calibrationSamples: Int = 0,
    /** Holdout ECE of the adopted calibration; NaN when no calibration is applied. */
    val calibrationError: Double = Double.NaN,
    /** Age of the newest input used for this prediction, in minutes. */
    val freshnessMinutes: Long? = null,
    val isLive: Boolean = false,
    /** True when the competition is known to be outside the developer's coverage set. */
    val unsupportedCompetition: Boolean = false,
)

data class ConfidenceAssessment(
    val level: ConfidenceLevel,
    val gaps: List<EvidenceGap>,
    /** Normalised Shannon entropy of the 1X2 distribution: 0 = certain, 1 = uniform. */
    val uncertainty: Double,
    /** Difference between the two most likely outcomes. Reported, never the driver. */
    val topGap: Double,
    val effectiveSample: Int,
    val calibrationSamples: Int,
    val calibrationError: Double,
    val dataCompleteness: Double,
    val freshnessMinutes: Long?,
) {
    val hasInsufficientEvidence: Boolean get() = level == ConfidenceLevel.INSUFFICIENT

    companion object {
        /** Placeholder used when a caller cannot supply evidence at all. */
        val UNKNOWN = ConfidenceAssessment(
            level = ConfidenceLevel.INSUFFICIENT,
            gaps = listOf(EvidenceGap.NO_COMPETITION_HISTORY),
            uncertainty = 1.0,
            topGap = 0.0,
            effectiveSample = 0,
            calibrationSamples = 0,
            calibrationError = Double.NaN,
            dataCompleteness = 0.0,
            freshnessMinutes = null,
        )
    }
}

object ConfidenceAssessor {

    /** Matches below which the engine refuses to present a prediction as usable. */
    const val MIN_COMPETITION_MATCHES = 40
    const val MIN_TEAM_MATCHES = 5
    /** Calibration is only trusted above this many resolved predictions. */
    const val MIN_CALIBRATION_SAMPLES = 400
    const val MAX_ACCEPTABLE_ECE = 0.05
    /** Freshness limits, in minutes, for a live and a pre-match prediction. */
    const val LIVE_FRESHNESS_LIMIT_MINUTES = 3L
    const val PRE_MATCH_FRESHNESS_LIMIT_MINUTES = 24L * 60L

    fun assess(input: ConfidenceInput): ConfidenceAssessment {
        val gaps = mutableListOf<EvidenceGap>()
        val (home, draw, away) = input.probabilities
        val valid = home.isFinite() && draw.isFinite() && away.isFinite() &&
            home >= 0.0 && draw >= 0.0 && away >= 0.0 &&
            abs(home + draw + away - 1.0) <= 1e-6

        val effectiveSample = minOf(
            input.competitionHistoricalMatches.coerceAtLeast(0),
            maxOf(input.homeMatchesUsed, input.awayMatchesUsed).coerceAtLeast(0),
        )
        val uncertainty = entropy(home, draw, away)
        val topGap = topGap(home, draw, away)

        if (!valid) {
            gaps += EvidenceGap.INVALID_PROBABILITIES
            return ConfidenceAssessment(
                level = ConfidenceLevel.INSUFFICIENT,
                gaps = gaps,
                uncertainty = uncertainty,
                topGap = topGap,
                effectiveSample = effectiveSample,
                calibrationSamples = input.calibrationSamples,
                calibrationError = input.calibrationError,
                dataCompleteness = input.dataCompleteness.coerceIn(0.0, 1.0),
                freshnessMinutes = input.freshnessMinutes,
            )
        }

        if (input.unsupportedCompetition) gaps += EvidenceGap.UNSUPPORTED_COMPETITION
        if (input.competitionHistoricalMatches <= 0) gaps += EvidenceGap.NO_COMPETITION_HISTORY
        else if (input.competitionHistoricalMatches < MIN_COMPETITION_MATCHES) {
            gaps += EvidenceGap.SMALL_COMPETITION_SAMPLE
        }
        val minTeamSample = minOf(input.homeMatchesUsed, input.awayMatchesUsed)
        if (minTeamSample < MIN_TEAM_MATCHES) gaps += EvidenceGap.SMALL_TEAM_SAMPLE

        if (input.calibrationSamples < MIN_CALIBRATION_SAMPLES) {
            gaps += EvidenceGap.NOT_CALIBRATED
        } else if (!input.calibrationError.isNaN() && input.calibrationError > MAX_ACCEPTABLE_ECE) {
            gaps += EvidenceGap.CALIBRATION_ERROR_HIGH
        }

        val freshnessLimit = if (input.isLive) LIVE_FRESHNESS_LIMIT_MINUTES else PRE_MATCH_FRESHNESS_LIMIT_MINUTES
        when {
            input.freshnessMinutes == null -> gaps += EvidenceGap.UNKNOWN_FRESHNESS
            input.freshnessMinutes < 0 -> gaps += EvidenceGap.STALE_DATA
            input.freshnessMinutes > freshnessLimit -> gaps += EvidenceGap.STALE_DATA
        }

        val completeness = input.dataCompleteness.coerceIn(0.0, 1.0)
        if (completeness < 0.5) gaps += EvidenceGap.PARTIAL_DATA
        if (uncertainty > 0.995) gaps += EvidenceGap.EXTREME_UNCERTAINTY

        val insufficient = input.competitionHistoricalMatches < MIN_COMPETITION_MATCHES ||
            minTeamSample < MIN_TEAM_MATCHES ||
            input.unsupportedCompetition

        // Sample evidence has two independent parts: how much history the competition
        // has, and how much each of the two teams has. A league with 600 matches but a
        // team with three appearances is not strong evidence.
        val competitionScore = (input.competitionHistoricalMatches / COMPETITION_SATURATION.toDouble())
            .coerceIn(0.0, 1.0)
        val teamScore = (minTeamSample / TEAM_SATURATION.toDouble()).coerceIn(0.0, 1.0)
        val sampleScore = 0.5 * competitionScore + 0.5 * teamScore
        val calibrationScore = when {
            input.calibrationSamples < MIN_CALIBRATION_SAMPLES -> 0.0
            input.calibrationError.isNaN() -> 0.5
            else -> (1.0 - input.calibrationError / CALIBRATION_ERROR_SATURATION).coerceIn(0.0, 1.0)
        }
        val freshnessScore = when {
            input.freshnessMinutes == null -> 0.4
            input.freshnessMinutes > freshnessLimit -> 0.0
            else -> 1.0
        }
        val score = WEIGHT_SAMPLE * sampleScore +
            WEIGHT_COMPLETENESS * completeness +
            WEIGHT_CALIBRATION * calibrationScore +
            WEIGHT_FRESHNESS * freshnessScore

        val calibrationAcceptable = input.calibrationSamples >= MIN_CALIBRATION_SAMPLES &&
            (input.calibrationError.isNaN() || input.calibrationError <= MAX_ACCEPTABLE_ECE)

        val level = when {
            insufficient -> ConfidenceLevel.INSUFFICIENT
            // A calibrated curve is a precondition for the highest label: without it the
            // reported probabilities have no measured reliability at all.
            score >= HIGH_SCORE_THRESHOLD && calibrationAcceptable &&
                completeness >= 0.6 && minTeamSample >= HIGH_MIN_TEAM_SAMPLE -> ConfidenceLevel.HIGH
            score >= MEDIUM_SCORE_THRESHOLD -> ConfidenceLevel.MEDIUM
            score >= LOW_SCORE_THRESHOLD -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.INSUFFICIENT
        }

        return ConfidenceAssessment(
            level = level,
            gaps = gaps.distinct(),
            uncertainty = uncertainty,
            topGap = topGap,
            effectiveSample = effectiveSample,
            calibrationSamples = input.calibrationSamples,
            calibrationError = input.calibrationError,
            dataCompleteness = completeness,
            freshnessMinutes = input.freshnessMinutes,
        )
    }

    /** Normalised Shannon entropy of the 1X2 distribution. */
    fun entropy(home: Double, draw: Double, away: Double): Double {
        val values = listOf(home, draw, away)
        if (values.any { !it.isFinite() || it < 0.0 }) return 1.0
        val total = home + draw + away
        if (total <= 0.0) return 1.0
        var entropy = 0.0
        for (value in values) {
            val p = value / total
            if (p > 0.0) entropy -= p * ln(p)
        }
        return (entropy / ln(3.0)).coerceIn(0.0, 1.0)
    }

    fun topGap(home: Double, draw: Double, away: Double): Double {
        val sorted = listOf(home, draw, away).sortedDescending()
        return (sorted[0] - sorted[1]).coerceAtLeast(0.0)
    }

    /** Competition sample size at which the history component is considered saturated. */
    private const val COMPETITION_SATURATION = 300.0
    /** Per-team sample size at which the team component is considered saturated. */
    private const val TEAM_SATURATION = 40.0
    /** An ECE at or above this contributes no calibration credit. */
    private const val CALIBRATION_ERROR_SATURATION = 0.10
    private const val WEIGHT_SAMPLE = 0.30
    private const val WEIGHT_COMPLETENESS = 0.25
    private const val WEIGHT_CALIBRATION = 0.35
    private const val WEIGHT_FRESHNESS = 0.10
    private const val HIGH_SCORE_THRESHOLD = 0.72
    private const val HIGH_MIN_TEAM_SAMPLE = 20
    private const val MEDIUM_SCORE_THRESHOLD = 0.50
    private const val LOW_SCORE_THRESHOLD = 0.28
}
