package com.footballai.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.footballai.app.R
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.RelativeLabel
import com.footballai.app.core.RelativeUnit
import com.footballai.app.domain.model.MarketType
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.QualityLevel

/** The active UI language tag, resolved from settings with the system locale as default. */
val LocalLanguageTag = staticCompositionLocalOf { "en" }

@Composable
private fun text(resId: Int): String = stringResource(resId)

@Composable
fun matchStatusLabel(status: MatchStatus): String = text(
    when (status) {
        MatchStatus.SCHEDULED -> R.string.status_scheduled
        MatchStatus.LIVE -> R.string.status_live
        MatchStatus.HALF_TIME -> R.string.status_half_time
        MatchStatus.FINISHED -> R.string.status_finished
        MatchStatus.POSTPONED -> R.string.status_postponed
        MatchStatus.SUSPENDED -> R.string.status_suspended
        MatchStatus.CANCELLED -> R.string.status_cancelled
        MatchStatus.UNKNOWN -> R.string.status_unknown
    },
)

@Composable
fun qualityLevelLabel(level: QualityLevel): String = text(
    when (level) {
        QualityLevel.HIGH -> R.string.quality_high
        QualityLevel.MEDIUM -> R.string.quality_medium
        QualityLevel.LOW -> R.string.quality_low
        QualityLevel.INSUFFICIENT -> R.string.quality_insufficient
    },
)

@Composable
fun marketTypeLabel(type: MarketType): String = text(
    when (type) {
        MarketType.ONE_X_TWO -> R.string.market_1x2
        MarketType.DOUBLE_CHANCE -> R.string.market_double_chance
        MarketType.OVER_UNDER -> R.string.market_over_under
        MarketType.BOTH_TEAMS_TO_SCORE -> R.string.market_btts
        MarketType.EXACT_SCORE -> R.string.market_exact_score
        MarketType.FIRST_HALF_RESULT -> R.string.market_first_half_result
        MarketType.FIRST_HALF_OVER -> R.string.market_first_half_over
        MarketType.NEXT_GOAL -> R.string.market_next_goal
    },
)

/** Localised label for a market selection, including its goal line when relevant. */
@Composable
fun marketSelectionLabel(type: MarketType, selectionKey: String, line: Double?): String {
    val lineText = line?.let { formatGoalLine(it) }
    return when (type) {
        MarketType.ONE_X_TWO, MarketType.FIRST_HALF_RESULT -> text(
            when (selectionKey) {
                "HOME" -> R.string.selection_home
                "AWAY" -> R.string.selection_away
                else -> R.string.selection_draw
            },
        )
        MarketType.DOUBLE_CHANCE -> text(
            when (selectionKey) {
                "HOME_OR_DRAW" -> R.string.selection_home_or_draw
                "DRAW_OR_AWAY" -> R.string.selection_draw_or_away
                else -> R.string.selection_home_or_away
            },
        )
        MarketType.OVER_UNDER -> {
            val base = if (selectionKey == "OVER") text(R.string.selection_over) else text(R.string.selection_under)
            "$base ${lineText.orEmpty()}".trim()
        }
        MarketType.FIRST_HALF_OVER -> "${text(R.string.selection_over)} ${lineText.orEmpty()}".trim()
        MarketType.BOTH_TEAMS_TO_SCORE -> text(
            if (selectionKey == "YES") R.string.selection_yes else R.string.selection_no,
        )
        MarketType.EXACT_SCORE -> selectionKey
        MarketType.NEXT_GOAL -> text(
            when (selectionKey) {
                "HOME" -> R.string.selection_next_goal_home
                "AWAY" -> R.string.selection_next_goal_away
                else -> R.string.selection_next_goal_none
            },
        )
    }
}

private fun formatGoalLine(line: Double): String =
    if (line == line.toInt().toDouble()) line.toInt().toString() else line.toString()

@Composable
fun explanationLabel(code: String): String = text(
    when (code) {
        "FAVOURITE_HOME" -> R.string.explanation_favourite_home
        "FAVOURITE_AWAY" -> R.string.explanation_favourite_away
        "EVENLY_MATCHED" -> R.string.explanation_evenly_matched
        "STRONG_HOME_ATTACK" -> R.string.explanation_strong_home_attack
        "WEAK_HOME_ATTACK" -> R.string.explanation_weak_home_attack
        "STRONG_AWAY_ATTACK" -> R.string.explanation_strong_away_attack
        "WEAK_AWAY_ATTACK" -> R.string.explanation_weak_away_attack
        "SOLID_HOME_DEFENCE" -> R.string.explanation_solid_home_defence
        "LEAKY_HOME_DEFENCE" -> R.string.explanation_leaky_home_defence
        "SOLID_AWAY_DEFENCE" -> R.string.explanation_solid_away_defence
        "LEAKY_AWAY_DEFENCE" -> R.string.explanation_leaky_away_defence
        "HIGH_SCORING_LEAGUE" -> R.string.explanation_high_scoring_league
        "LOW_SCORING_LEAGUE" -> R.string.explanation_low_scoring_league
        "HOME_ADVANTAGE" -> R.string.explanation_home_advantage
        "ELO_EDGE_HOME" -> R.string.explanation_elo_edge_home
        "ELO_EDGE_AWAY" -> R.string.explanation_elo_edge_away
        "DIXON_COLES_LOW_SCORE_ADJUSTMENT" -> R.string.explanation_dixon_coles
        "LIMITED_HISTORY" -> R.string.explanation_limited_history
        "LARGE_SAMPLE" -> R.string.explanation_large_sample
        "LIVE_TIME_REMAINING" -> R.string.explanation_live_time
        "LIVE_CURRENT_SCORE" -> R.string.explanation_live_score
        "LIVE_RED_CARD_HOME" -> R.string.explanation_live_red_home
        "LIVE_RED_CARD_AWAY" -> R.string.explanation_live_red_away
        "LIVE_INTENSITY_ADJUSTMENT" -> R.string.explanation_live_intensity
        "NO_MARKET_EDGE_CLAIMED" -> R.string.explanation_no_edge
        else -> R.string.explanation_no_edge
    },
)

@Composable
fun qualityNoteLabel(code: String): String = text(
    when (code) {
        "NO_HISTORY_FOR_COMPETITION" -> R.string.note_no_history
        "SMALL_SAMPLE" -> R.string.note_small_sample
        "FALLBACK_LEAGUE_BASELINE" -> R.string.note_fallback_baseline
        "LIVE_STATS_PARTIAL" -> R.string.note_live_stats_partial
        "LIVE_ADJUSTMENT_HEURISTIC" -> R.string.note_live_adjustment
        "ODDS_UNAVAILABLE" -> R.string.note_odds_unavailable
        "LINEUPS_UNAVAILABLE" -> R.string.note_lineups_unavailable
        "INJURIES_UNAVAILABLE" -> R.string.note_injuries_unavailable
        "MODEL_NOT_CALIBRATED" -> R.string.note_model_not_calibrated
        else -> R.string.note_model_not_calibrated
    },
)

@Composable
fun missingDataLabel(code: String): String = text(
    when (code) {
        "HISTORICAL_MATCHES" -> R.string.missing_history
        "RECENT_FORM" -> R.string.missing_form
        "LINEUPS" -> R.string.missing_lineups
        "INJURIES" -> R.string.missing_injuries
        "LIVE_STATISTICS" -> R.string.missing_live_stats
        "BOOKMAKER_ODDS" -> R.string.missing_odds
        "EXPECTED_GOALS" -> R.string.missing_xg
        "STANDINGS" -> R.string.missing_standings
        "XG_BASED_FEATURES" -> R.string.missing_xg
        else -> R.string.missing_other
    },
)

@Composable
fun errorLabel(error: AppError): String = text(
    when (error.code) {
        ErrorCode.INSUFFICIENT_HISTORY -> R.string.error_insufficient_history
        ErrorCode.STALE_DATA -> R.string.error_stale_data
        ErrorCode.INVALID_MATCH_STATE -> R.string.error_match_state
        ErrorCode.UNKNOWN_MATCH_TIME -> R.string.error_match_time
        ErrorCode.NETWORK_UNAVAILABLE -> R.string.error_network
        ErrorCode.REQUEST_FAILED -> R.string.error_request
        ErrorCode.RATE_LIMITED -> R.string.error_rate_limited
        ErrorCode.QUOTA_EXHAUSTED -> R.string.error_quota
        ErrorCode.MISSING_API_KEY -> R.string.error_missing_key
        ErrorCode.ACCESS_DENIED -> R.string.error_access_denied
        ErrorCode.PLAN_RESTRICTED -> R.string.error_plan_restricted
        ErrorCode.SUBSCRIPTION_INACTIVE -> R.string.error_subscription_inactive
        ErrorCode.INVALID_API_KEY -> R.string.error_invalid_key
        ErrorCode.PARSE_ERROR -> R.string.error_parse
        ErrorCode.EMPTY_RESPONSE -> R.string.error_empty
        ErrorCode.NOT_FOUND -> R.string.error_not_found
        ErrorCode.STORAGE_ERROR -> R.string.error_storage
        ErrorCode.UNKNOWN -> R.string.error_unknown
    },
)

@Composable
fun relativeLabel(label: RelativeLabel, isPast: Boolean): String = when (label.unit) {
    RelativeUnit.NOW -> text(R.string.relative_now)
    RelativeUnit.MINUTES -> stringResource(
        if (isPast) R.string.relative_minutes_ago else R.string.relative_in_minutes,
        label.value,
    )
    RelativeUnit.HOURS -> stringResource(
        if (isPast) R.string.relative_hours_ago else R.string.relative_in_hours,
        label.value,
    )
    RelativeUnit.DAYS -> stringResource(
        if (isPast) R.string.relative_days_ago else R.string.relative_in_days,
        label.value,
    )
}
