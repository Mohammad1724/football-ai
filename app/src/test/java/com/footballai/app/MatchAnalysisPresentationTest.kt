package com.footballai.app

import com.footballai.app.R
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.ui.screens.MatchAnalysisPresentation
import com.footballai.app.ui.screens.VerdictOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchAnalysisPresentationTest {
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
