package com.footballai.app.domain.model

/** Competition as understood by the app, independent of any provider payload. */
data class Competition(
    val id: String,
    val name: String,
    val country: String?,
    val emblemUrl: String?,
    val season: Int?,
    val providerId: String,
    val hasFullCoverage: Boolean,
    val isFollowed: Boolean = false,
    val providerKey: String = "",
)

data class TeamRef(
    val id: String,
    val name: String,
    val shortName: String? = null,
    val crestUrl: String? = null,
)

enum class MatchStatus {
    SCHEDULED,
    LIVE,
    HALF_TIME,
    FINISHED,
    POSTPONED,
    SUSPENDED,
    CANCELLED,
    UNKNOWN;

    val isLive: Boolean get() = this == LIVE || this == HALF_TIME
    val isFinished: Boolean get() = this == FINISHED
    val hasScore: Boolean get() = isLive || isFinished
}

data class MatchSummary(
    val id: String,
    val competitionId: String,
    val competitionName: String,
    val competitionEmblemUrl: String?,
    val home: TeamRef,
    val away: TeamRef,
    val kickoffEpochMs: Long,
    val status: MatchStatus,
    val minute: Int?,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val lastSyncedAt: Long,
    val hasStatistics: Boolean,
    val hasLineups: Boolean,
    val hasEvents: Boolean,
    val hasOdds: Boolean,
)

data class MatchOdds(
    val home: Double,
    val draw: Double,
    val away: Double,
    val bookmaker: String? = null,
)

enum class StatisticType {
    POSSESSION,
    SHOTS,
    SHOTS_ON_TARGET,
    CORNERS,
    YELLOW_CARDS,
    RED_CARDS,
    EXPECTED_GOALS,
    FOULS,
    OFFSIDES,
    SAVES,
}

data class MatchStatisticPair(
    val type: StatisticType,
    val home: Double?,
    val away: Double?,
)

enum class MatchEventType {
    GOAL,
    OWN_GOAL,
    PENALTY_GOAL,
    PENALTY_MISSED,
    YELLOW_CARD,
    RED_CARD,
    SECOND_YELLOW,
    SUBSTITUTION,
    VAR,
    OTHER,
}

data class MatchEvent(
    val id: String,
    val minute: Int,
    val type: MatchEventType,
    val detail: String?,
    val teamId: String?,
    val playerName: String?,
    val assistName: String?,
)

data class LineupPlayer(
    val name: String,
    val number: Int?,
    val position: String?,
    val isStarter: Boolean,
)

data class TeamLineup(
    val teamId: String,
    val formation: String?,
    val players: List<LineupPlayer>,
)

data class MatchDetail(
    val summary: MatchSummary,
    val venue: String?,
    val matchday: Int?,
    val statistics: List<MatchStatisticPair>,
    val events: List<MatchEvent>,
    val lineups: List<TeamLineup>,
    val odds: MatchOdds?,
    val homeHalfTimeGoals: Int?,
    val awayHalfTimeGoals: Int?,
    val statisticsSyncedAt: Long?,
)

enum class ResultOutcome { WIN, DRAW, LOSS }

data class TeamResult(
    val matchId: String,
    val opponent: TeamRef,
    val kickoffEpochMs: Long,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val wasHome: Boolean,
    val outcome: ResultOutcome,
    val competitionId: String,
)

data class TeamFormSummary(
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val sequence: List<ResultOutcome>,
) {
    val matches: Int get() = wins + draws + losses
    val pointsPerGame: Double get() = if (matches == 0) 0.0 else (wins * 3 + draws).toDouble() / matches
}

data class TeamRating(
    val attack: Double,
    val defence: Double,
    val elo: Double,
    val matchesUsed: Int,
)

data class TeamProfile(
    val team: TeamRef,
    val competitionId: String,
    val competitionName: String,
    val rating: TeamRating,
    val form: TeamFormSummary,
    val recentMatches: List<TeamResult>,
    val ratingAsOfEpochMs: Long,
)

data class FixturePredictionInput(
    val matchId: String,
    val competitionId: String,
    val homeTeamId: String,
    val awayTeamId: String,
    val kickoffEpochMs: Long,
)

enum class PredictionKind { PRE_MATCH, LIVE }

enum class MarketType {
    ONE_X_TWO,
    DOUBLE_CHANCE,
    OVER_UNDER,
    BOTH_TEAMS_TO_SCORE,
    EXACT_SCORE,
    FIRST_HALF_RESULT,
    FIRST_HALF_OVER,
    NEXT_GOAL,
}

/**
 * A single market selection. [selectionKey] is a stable machine key (e.g. "HOME",
 * "OVER", "YES") that the UI maps to a localised label; [line] carries the goal
 * line for over/under style markets.
 */
data class MarketRow(
    val type: MarketType,
    val selectionKey: String,
    val line: Double?,
    val probability: Double,
    val outcome: Boolean? = null,
)

data class ScoreLine(val homeGoals: Int, val awayGoals: Int, val probability: Double)

data class QualityView(
    val level: QualityLevel,
    val completeness: Double,
    val missing: List<String>,
    val notes: List<String>,
    val historicalMatchesUsed: Int,
)

enum class QualityLevel { HIGH, MEDIUM, LOW, INSUFFICIENT }

data class ExplanationView(
    val code: String,
    val weight: Double,
    val params: Map<String, String>,
)

data class LiveSnapshot(
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
    val adjustments: List<String>,
)

data class PredictionResolution(
    val actualHomeGoals: Int,
    val actualAwayGoals: Int,
    val outcomeCorrect: Boolean,
    val brier: Double,
    val logLoss: Double,
    val resolvedAtEpochMs: Long,
)

data class PredictionView(
    val id: String,
    val matchId: String,
    val kind: PredictionKind,
    val createdAtEpochMs: Long,
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val markets: List<MarketRow>,
    val topScores: List<ScoreLine>,
    val modelName: String,
    val modelVersion: String,
    val fittedMatches: Int,
    val rho: Double,
    val quality: QualityView,
    val explanations: List<ExplanationView>,
    val live: LiveSnapshot?,
    val odds: MatchOdds?,
    val isStale: Boolean,
    val resolution: PredictionResolution?,
)

data class PredictionWithMatch(
    val prediction: PredictionView,
    val match: MatchSummary,
)

enum class DataFreshness { FRESH, AGING, STALE, UNKNOWN }

data class SyncStatus(
    val lastSuccessfulSyncEpochMs: Long?,
    val lastAttemptEpochMs: Long?,
    val isSyncing: Boolean,
    val lastError: String?,
    val fixturesCount: Int,
    val historicalCount: Int,
    val predictionsCount: Int,
)

data class PerformanceReport(
    val matchesEvaluated: Int,
    val brier: Double,
    val logLoss: Double,
    val accuracy: Double,
    val baseRateBrier: Double,
    val marketBrier: Double?,
    val pairedModelBrier: Double? = null,
    val marketSampleCount: Int = 0,
    val over25Brier: Double,
    val bttsBrier: Double,
    val calibration: List<CalibrationPoint>,
    val expectedCalibrationError: Double,
    val byCompetition: Map<String, CompetitionPerformance>,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val generatedAtEpochMs: Long,
    /** Same fixtures and timestamps with contextual features disabled. */
    val baselineMatchesEvaluated: Int = 0,
    val baselineBrier: Double = Double.NaN,
    val baselineLogLoss: Double = Double.NaN,
    val baselineAccuracy: Double = Double.NaN,
)

data class CalibrationPoint(
    val lowerBound: Double,
    val upperBound: Double,
    val count: Int,
    val meanPredicted: Double,
    val observedFrequency: Double,
)

data class CompetitionPerformance(
    val competitionId: String,
    val competitionName: String,
    val matches: Int,
    val brier: Double,
    val logLoss: Double,
    val accuracy: Double,
)
