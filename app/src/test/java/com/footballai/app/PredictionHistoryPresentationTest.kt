package com.footballai.app

import com.footballai.app.domain.model.PredictionResolution
import com.footballai.app.ui.screens.EvaluationDisplay
import com.footballai.app.ui.screens.evaluationDisplay
import com.footballai.app.ui.screens.scoreChangedSinceEvaluation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictionHistoryPresentationTest {
    private fun result(home: Int, away: Int, correct: Boolean?) = PredictionResolution(
        actualHomeGoals = home, actualAwayGoals = away, outcomeCorrect = correct,
        brier = 0.3, logLoss = 0.5, resolvedAtEpochMs = 100,
    )

    @Test fun `correct and incorrect use stored result and highest probability`() {
        assertEquals(EvaluationDisplay.CORRECT, evaluationDisplay(0.7, 0.2, 0.1, result(2, 1, true)))
        assertEquals(EvaluationDisplay.INCORRECT, evaluationDisplay(0.7, 0.2, 0.1, result(0, 1, false)))
        assertEquals(EvaluationDisplay.CORRECT, evaluationDisplay(0.2, 0.6, 0.2, result(1, 1, true)))
        assertEquals(EvaluationDisplay.CORRECT, evaluationDisplay(0.1, 0.2, 0.7, result(0, 2, true)))
    }

    @Test fun `ties missing and inconsistent saved evaluations cannot be called wrong or right`() {
        assertEquals(EvaluationDisplay.UNCLEAR, evaluationDisplay(0.4, 0.4, 0.2, result(2, 1, true)))
        assertEquals(EvaluationDisplay.UNCLEAR, evaluationDisplay(0.404, 0.403, 0.193, result(2, 1, true)))
        assertEquals(EvaluationDisplay.UNAVAILABLE, evaluationDisplay(0.7, 0.2, 0.1, result(2, 1, null)))
        assertEquals(EvaluationDisplay.UNAVAILABLE, evaluationDisplay(0.7, 0.2, 0.1, result(2, 1, false)))
        assertEquals(EvaluationDisplay.UNAVAILABLE, evaluationDisplay(Double.NaN, 0.2, 0.8, result(2, 1, false)))
        assertEquals(EvaluationDisplay.UNAVAILABLE, evaluationDisplay(0.7, 0.2, 0.1, result(-1, 0, false)))
    }

    @Test fun `changed current score is distinct from score used by saved evaluation`() {
        val saved = result(2, 1, true)
        assertFalse(scoreChangedSinceEvaluation(saved, 2, 1))
        assertFalse(scoreChangedSinceEvaluation(saved, null, 1))
        assertTrue(scoreChangedSinceEvaluation(saved, 2, 2))
    }
}
