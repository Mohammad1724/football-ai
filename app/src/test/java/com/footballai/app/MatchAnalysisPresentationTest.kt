package com.footballai.app

import com.footballai.app.R
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.ui.screens.MatchAnalysisPresentation
import com.footballai.app.ui.screens.VerdictOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchAnalysisPresentationTest {
    @Test
    fun `headline recognises each leader without inventing one for ties or invalid distributions`() {
        assertEquals(VerdictOutcome.HOME, MatchAnalysisPresentation.leadingOutcome(0.95, 0.04, 0.01))
        assertEquals(VerdictOutcome.DRAW, MatchAnalysisPresentation.leadingOutcome(0.20, 0.50, 0.30))
        assertEquals(VerdictOutcome.AWAY, MatchAnalysisPresentation.leadingOutcome(0.20, 0.30, 0.50))
        assertEquals(null, MatchAnalysisPresentation.leadingOutcome(0.40, 0.40, 0.20))
        assertEquals(null, MatchAnalysisPresentation.leadingOutcome(0.404, 0.403, 0.193))
        assertEquals(null, MatchAnalysisPresentation.leadingOutcome(Double.NaN, 0.5, 0.5))
        assertFalse(MatchAnalysisPresentation.validProbabilities(0.9, 0.9, 0.1))
        assertFalse(MatchAnalysisPresentation.validProbabilities(-0.1, 0.6, 0.5))
        assertTrue(MatchAnalysisPresentation.validProbabilities(0.95, 0.04, 0.01))
    }

    @Test
    fun `percentage point gap matches displayed rounded numbers`() {
        assertEquals(91, MatchAnalysisPresentation.roundedGap(0.95, 0.04))
        assertEquals(5, MatchAnalysisPresentation.roundedGap(0.394, 0.336))
        assertEquals(null, MatchAnalysisPresentation.roundedGap(Double.NaN, 0.2))
    }

    @Test
    fun `finished match labels previous prediction without assuming its kind`() {
        assertEquals(R.string.prediction_past_title, MatchAnalysisPresentation.title(true))
        assertEquals(R.string.verdict_past_win, MatchAnalysisPresentation.verdict(true, VerdictOutcome.HOME))
        assertEquals(R.string.verdict_past_win, MatchAnalysisPresentation.verdict(true, VerdictOutcome.AWAY))
        assertEquals(R.string.verdict_past_draw, MatchAnalysisPresentation.verdict(true, VerdictOutcome.DRAW))
        assertEquals(R.string.prediction_context_prematch, MatchAnalysisPresentation.context(PredictionKind.PRE_MATCH))
        assertEquals(R.string.prediction_context_live, MatchAnalysisPresentation.context(PredictionKind.LIVE))
    }

    @Test
    fun `live expected goals describe the remaining time not full match`() {
        assertEquals(R.string.why_expected_goals_live, MatchAnalysisPresentation.goalsExplanation(PredictionKind.LIVE))
        assertEquals(R.string.why_expected_goals, MatchAnalysisPresentation.goalsExplanation(PredictionKind.PRE_MATCH))
        assertEquals(R.string.verdict_home_win, MatchAnalysisPresentation.verdict(false, VerdictOutcome.HOME))
        assertEquals(R.string.verdict_draw, MatchAnalysisPresentation.verdict(false, VerdictOutcome.DRAW))
    }
}
