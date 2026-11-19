package com.footballai.app.ui.screens

import com.footballai.app.R
import com.footballai.app.domain.model.PredictionKind

/** Labels describe the displayed snapshot, never infer its kind from the match status. */
internal object MatchAnalysisPresentation {
    fun title(isFinished: Boolean): Int = if (isFinished) R.string.prediction_past_title else R.string.prediction_verdict_title
    fun context(kind: PredictionKind): Int = when (kind) {
        PredictionKind.PRE_MATCH -> R.string.prediction_context_prematch
        PredictionKind.LIVE -> R.string.prediction_context_live
    }
    fun goalsExplanation(kind: PredictionKind): Int = when (kind) {
        PredictionKind.PRE_MATCH -> R.string.why_expected_goals
        PredictionKind.LIVE -> R.string.why_expected_goals_live
    }
    fun verdict(isFinished: Boolean, outcome: VerdictOutcome): Int = when (outcome) {
        VerdictOutcome.HOME -> if (isFinished) R.string.verdict_past_win else R.string.verdict_home_win
        VerdictOutcome.AWAY -> if (isFinished) R.string.verdict_past_win else R.string.verdict_away_win
        VerdictOutcome.DRAW -> if (isFinished) R.string.verdict_past_draw else R.string.verdict_draw
    }
}

internal enum class VerdictOutcome { HOME, DRAW, AWAY }
