package com.footballai.app

import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.PredictionResolution
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.PredictionWithMatch
import com.footballai.app.ui.viewmodel.PredictionListFilter
import com.footballai.app.ui.viewmodel.PredictionsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class PredictionListFilterTest {
    private fun item(id: String, kickoff: Long, resolved: Boolean): PredictionWithMatch {
        val prediction = mockk<PredictionView>()
        val match = mockk<MatchSummary>()
        every { prediction.id } returns id
        every { prediction.resolution } returns if (resolved) mockk<PredictionResolution>() else null
        every { match.kickoffEpochMs } returns kickoff
        return PredictionWithMatch(prediction, match)
    }

    @Test
    fun `upcoming predictions are chronological and exclude resolved matches`() {
        val late = item("late", 30, false)
        val early = item("early", 10, false)
        val resolved = item("resolved", 20, true)

        val result = PredictionsViewModel.filterItems(
            listOf(late, resolved, early), PredictionListFilter.UPCOMING,
        )

        assertEquals(listOf("early", "late"), result.map { it.prediction.id })
    }

    @Test
    fun `resolved history is newest first and all keeps every item`() {
        val old = item("old", 10, true)
        val new = item("new", 30, true)
        val pending = item("pending", 20, false)

        assertEquals(
            listOf("new", "old"),
            PredictionsViewModel.filterItems(listOf(old, new, pending), PredictionListFilter.RESOLVED)
                .map { it.prediction.id },
        )
        assertEquals(3, PredictionsViewModel.filterItems(listOf(old, new, pending), PredictionListFilter.ALL).size)
    }
}
