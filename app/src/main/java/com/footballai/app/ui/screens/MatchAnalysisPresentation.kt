package com.footballai.app.ui.screens

import com.footballai.app.R
import com.footballai.app.domain.model.PredictionKind
import kotlin.math.roundToInt

/** Labels describe the displayed snapshot, never infer its kind from the match status. */
internal object MatchAnalysisPresentation {
    /** A headline must never invent a winner for invalid or equally rounded numbers. */
    fun leadingOutcome(home: Double, draw: Double, away: Double): VerdictOutcome? {
        val values = listOf(home, draw, away)
        if (values.any { !it.isFinite() || it !in 0.0..1.0 } || values.sum() !in 0.98..1.02) return null
        val ranked = listOf(VerdictOutcome.HOME to home, VerdictOutcome.DRAW to draw, VerdictOutcome.AWAY to away)
            .sortedByDescending { it.second }
        if ((ranked[0].second * 100).roundToInt() == (ranked[1].second * 100).roundToInt()) return null
        return ranked[0].first
    }
    fun validProbabilities(home: Double, draw: Double, away: Double): Boolean =
        listOf(home, draw, away).all { it.isFinite() && it in 0.0..1.0 } && home + draw + away in 0.98..1.02

    fun roundedGap(top: Double, next: Double): Int? =
        if (top.isFinite() && next.isFinite() && top in 0.0..1.0 && next in 0.0..1.0)
            ((top * 100).roundToInt() - (next * 100).roundToInt()).coerceAtLeast(0)
        else null
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
