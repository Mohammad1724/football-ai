package com.footballai.app.ui.screens

import com.footballai.app.domain.model.PredictionResolution

internal enum class EvaluationDisplay { CORRECT, INCORRECT, UNCLEAR, UNAVAILABLE }

/** Reflect the saved evaluation, but never turn absent, tied or inconsistent data into a verdict. */
internal fun evaluationDisplay(
    home: Double, draw: Double, away: Double, resolution: PredictionResolution,
): EvaluationDisplay {
    if (!MatchAnalysisPresentation.validProbabilities(home, draw, away) ||
        resolution.actualHomeGoals < 0 || resolution.actualAwayGoals < 0 || resolution.outcomeCorrect == null
    ) return EvaluationDisplay.UNAVAILABLE
    val leading = MatchAnalysisPresentation.leadingOutcome(home, draw, away) ?: return EvaluationDisplay.UNCLEAR
    val actual = when {
        resolution.actualHomeGoals > resolution.actualAwayGoals -> VerdictOutcome.HOME
        resolution.actualHomeGoals < resolution.actualAwayGoals -> VerdictOutcome.AWAY
        else -> VerdictOutcome.DRAW
    }
    val matches = leading == actual
    if (matches != resolution.outcomeCorrect) return EvaluationDisplay.UNAVAILABLE
    return if (matches) EvaluationDisplay.CORRECT else EvaluationDisplay.INCORRECT
}

internal fun scoreChangedSinceEvaluation(
    resolution: PredictionResolution, currentHome: Int?, currentAway: Int?,
): Boolean = currentHome != null && currentAway != null &&
    (currentHome != resolution.actualHomeGoals || currentAway != resolution.actualAwayGoals)
