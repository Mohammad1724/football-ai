package com.footballai.engine.model

import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.confidence.ConfidenceAssessment

/**
 * Pure domain types used by the prediction engine.
 *
 * The engine module is deliberately free of any Android, network or database
 * dependency: it can be unit tested on a plain JVM and executed offline on the
 * device using locally stored data only.
 */

/** League-relative team strength estimates produced by the rating modules. */
data class TeamStrength(
    val teamId: String,
    /** Multiplicative scoring strength, 1.0 = league average. */
    val attack: Double,
    /** Multiplicative conceding strength, 1.0 = league average (lower is better). */
    val defence: Double,
    /** Elo rating, 1500 = league average. */
    val elo: Double,
    /** Number of historical matches the estimate is based on. */
    val matchesUsed: Int,
) {
    init {
        require(attack > 0.0 && attack.isFinite()) { "attack must be positive and finite" }
        require(defence > 0.0 && defence.isFinite()) { "defence must be positive and finite" }
        require(elo.isFinite()) { "elo must be finite" }
        require(matchesUsed >= 0) { "matchesUsed must be non-negative" }
    }

    companion object {
        const val AVERAGE_ELO = 1500.0

        fun neutral(teamId: String, matchesUsed: Int = 0): TeamStrength = TeamStrength(
            teamId = teamId,
            attack = 1.0,
            defence = 1.0,
            elo = AVERAGE_ELO,
            matchesUsed = matchesUsed,
        )
    }
}

/** League scoring baseline, used as the multiplicative starting point of the goal model. */
data class LeagueBaseline(
    val averageHomeGoals: Double,
    val averageAwayGoals: Double,
    val sampleSize: Int,
) {
    init {
        require(averageHomeGoals > 0.0 && averageHomeGoals.isFinite()) { "averageHomeGoals must be positive" }
        require(averageAwayGoals > 0.0 && averageAwayGoals.isFinite()) { "averageAwayGoals must be positive" }
    }

    val averageTotalGoals: Double get() = averageHomeGoals + averageAwayGoals

    companion object {
        /** Conservative fallback used only when no historical data exists at all. */
        val FALLBACK = LeagueBaseline(averageHomeGoals = 1.45, averageAwayGoals = 1.15, sampleSize = 0)
    }
}

/** A single scoreline with its probability. */
data class ScoreProbability(
    val homeGoals: Int,
    val awayGoals: Int,
    val probability: Double,
) {
    val label: String get() = "$homeGoals-$awayGoals"
}

enum class DoubleChance {
    HOME_OR_DRAW,
    HOME_OR_AWAY,
    DRAW_OR_AWAY,
}

/** Aggregated probabilities for every market the engine can compute. */
data class MarketProbabilities(
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    /** Goal line (e.g. 2.5) to the probability that the total number of goals exceeds it. */
    val over: Map<Double, Double>,
    /** Goal line to the probability that the total number of goals stays below it. */
    val under: Map<Double, Double>,
    val bttsYes: Double,
    val bttsNo: Double,
    val doubleChance: Map<DoubleChance, Double>,
    /** Most likely exact scores, descending by probability. */
    val topScores: List<ScoreProbability>,
    /** Full-time match result matrix, normalised to sum to 1. */
    val exactScoreMatrix: List<ScoreProbability>,
    val firstHalf: FirstHalfProbabilities?,
)

data class FirstHalfProbabilities(
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val over05: Double,
    val over15: Double,
    val bttsYes: Double,
)

enum class MatchOutcome {
    HOME_WIN,
    DRAW,
    AWAY_WIN,
}

/** Describes which model produced a prediction, so results stay reproducible. */
data class ModelDescriptor(
    val name: String,
    val version: String,
    val dixonColesEnabled: Boolean,
    val rho: Double,
    val fittedMatches: Int,
    val eloBlendWeight: Double,
)

enum class DataQualityLevel {
    HIGH,
    MEDIUM,
    LOW,
    INSUFFICIENT,
}

enum class MissingDataItem {
    HISTORICAL_MATCHES,
    RECENT_FORM,
    LINEUPS,
    INJURIES,
    LIVE_STATISTICS,
    BOOKMAKER_ODDS,
    EXPECTED_GOALS,
    STANDINGS,
    XG_BASED_FEATURES,
}

/** Transparent, evidence-based assessment of how much the data can be trusted. */
data class DataQuality(
    val level: DataQualityLevel,
    /** 0..1 completeness indicator derived from the weighted availability of inputs. */
    val completeness: Double,
    val missing: List<MissingDataItem>,
    val historicalMatchesUsed: Int,
    val notes: List<QualityNote> = emptyList(),
)

/** Notes are codes so the UI can localise them; [params] carry numbers only. */
data class QualityNote(val code: QualityNoteCode, val params: Map<String, String> = emptyMap())

enum class QualityNoteCode {
    NO_HISTORY_FOR_COMPETITION,
    SMALL_SAMPLE,
    FALLBACK_LEAGUE_BASELINE,
    LIVE_STATS_PARTIAL,
    LIVE_ADJUSTMENT_HEURISTIC,
    ODDS_UNAVAILABLE,
    LINEUPS_UNAVAILABLE,
    INJURIES_UNAVAILABLE,
    MODEL_NOT_CALIBRATED,
    CALIBRATION_APPLIED,
    CALIBRATION_UNAVAILABLE,
    NATIONAL_TEAM_MODEL,
    NEUTRAL_VENUE,
    INSUFFICIENT_EVIDENCE,
}

/** Witch factor explains the prediction most; the UI localises [code]. */
data class Explanation(
    val code: ExplanationCode,
    /** Rough relative importance in 0..1, used only for ordering in the UI. */
    val weight: Double,
    val params: Map<String, String> = emptyMap(),
)

enum class ExplanationCode {
    FAVOURITE_HOME,
    FAVOURITE_AWAY,
    EVENLY_MATCHED,
    STRONG_HOME_ATTACK,
    WEAK_HOME_ATTACK,
    STRONG_AWAY_ATTACK,
    WEAK_AWAY_ATTACK,
    SOLID_HOME_DEFENCE,
    LEAKY_HOME_DEFENCE,
    SOLID_AWAY_DEFENCE,
    LEAKY_AWAY_DEFENCE,
    HIGH_SCORING_LEAGUE,
    LOW_SCORING_LEAGUE,
    HOME_ADVANTAGE,
    ELO_EDGE_HOME,
    ELO_EDGE_AWAY,
    RECENT_FORM,
    VENUE_FORM,
    FIXTURE_CONGESTION,
    NEUTRAL_VENUE_ADJUSTMENT,
    NATIONAL_TEAM_ELO,
    CALIBRATION_ADJUSTMENT,
    DIXON_COLES_LOW_SCORE_ADJUSTMENT,
    LIMITED_HISTORY,
    LARGE_SAMPLE,
    LIVE_TIME_REMAINING,
    LIVE_CURRENT_SCORE,
    LIVE_RED_CARD_HOME,
    LIVE_RED_CARD_AWAY,
    LIVE_INTENSITY_ADJUSTMENT,
    NO_MARKET_EDGE_CLAIMED,
}

/**
 * A bounded multiplicative adjustment applied to the expected goals of a match.
 * Every adjustment must be traceable to a data source and be explained in the UI.
 */
data class LambdaAdjustment(
    val label: LambdaAdjustmentLabel,
    /** Multiplier applied to the home expected goals, clamped to [0.5, 2.0]. */
    val homeFactor: Double,
    /** Multiplier applied to the away expected goals, clamped to [0.5, 2.0]. */
    val awayFactor: Double,
    val reason: ExplanationCode,
)

enum class LambdaAdjustmentLabel {
    INJURIES,
    RECENT_FORM,
    FIXTURE_CONGESTION,
    CONFIRMED_LINEUPS,
    VENUE,
    MANUAL,
}

data class PreMatchInput(
    val home: TeamStrength,
    val away: TeamStrength,
    val baseline: LeagueBaseline,
    val adjustments: List<LambdaAdjustment> = emptyList(),
    val eloBlendWeight: Double = 0.15,
    val dixonColesRho: Double? = null,
    val quality: DataQuality,
    val asOfEpochMs: Long,
    val modelVersion: String = "football-ai/engine-1.4.0",
    val fittedMatches: Int = 0,
    /** Competition context: league / knockout / international, neutral ground, gender. */
    val competition: CompetitionProfile = CompetitionProfile.DEFAULT,
    /** Calibration fitted on device; [com.footballai.engine.calibration.CalibrationModel.NONE] by default. */
    val calibration: CalibrationModel = CalibrationModel.NONE,
    /** Age of the freshest input used, in minutes; null when the caller cannot tell. */
    val dataFreshnessMinutes: Long? = null,
)

data class LiveInput(
    val preMatch: PreMatchInput,
    /** Elapsed match minute at the moment of the prediction. */
    val minute: Int,
    val homeGoals: Int,
    val awayGoals: Int,
    val homeRedCards: Int = 0,
    val awayRedCards: Int = 0,
    val homeShots: Int? = null,
    val awayShots: Int? = null,
    val homeShotsOnTarget: Int? = null,
    val awayShotsOnTarget: Int? = null,
    val addedTimeMinutes: Int = 0,
)

/** Result object returned for both pre-match and live predictions. */
data class PredictionResult(
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
    val markets: MarketProbabilities,
    val mostLikelyScores: List<ScoreProbability>,
    val model: ModelDescriptor,
    val explanations: List<Explanation>,
    val quality: DataQuality,
    val asOfEpochMs: Long,
    val live: LiveContext? = null,
    /** Evidence-based confidence; never derived from the spread of the probabilities. */
    val confidence: ConfidenceAssessment = ConfidenceAssessment.UNKNOWN,
    /** Which calibration (if any) produced the displayed probabilities. */
    val calibration: CalibrationInfo? = null,
) {
    val expectedTotalGoals: Double get() = expectedHomeGoals + expectedAwayGoals

    fun outcomeProbability(outcome: MatchOutcome): Double = when (outcome) {
        MatchOutcome.HOME_WIN -> markets.homeWin
        MatchOutcome.DRAW -> markets.draw
        MatchOutcome.AWAY_WIN -> markets.awayWin
    }

    fun mostLikelyOutcome(): MatchOutcome {
        val p = listOf(markets.homeWin, markets.draw, markets.awayWin)
        val maxIndex = p.indices.maxByOrNull { p[it] } ?: 0
        return MatchOutcome.entries[maxIndex]
    }
}

/** Summary of the calibration that was actually applied, with its measured effect. */
data class CalibrationInfo(
    val method: String,
    val temperature: Double,
    val sampleCount: Int,
    val holdoutCount: Int,
    val holdoutLogLossBefore: Double,
    val holdoutLogLossAfter: Double,
    val rawHomeWin: Double,
    val rawDraw: Double,
    val rawAwayWin: Double,
) {
    val improvement: Double get() = holdoutLogLossBefore - holdoutLogLossAfter
}

/** Extra context present only for live predictions. */
data class LiveContext(
    val minute: Int,
    val homeGoals: Int,
    val awayGoals: Int,
    val remainingMinutes: Int,
    val remainingHomeExpectedGoals: Double,
    val remainingAwayExpectedGoals: Double,
    val nextGoalHome: Double,
    val nextGoalAway: Double,
    val noMoreGoals: Double,
    val remainingOver: Map<Double, Double>,
    val adjustmentsApplied: List<String> = emptyList(),
)
