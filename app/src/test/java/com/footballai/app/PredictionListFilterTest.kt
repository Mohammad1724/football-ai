package com.footballai.app

import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.domain.model.PredictionResolution
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.PredictionWithMatch
import com.footballai.app.ui.viewmodel.PredictionListFilter
import com.footballai.app.ui.viewmodel.PredictionRecordStatus
import com.footballai.app.ui.viewmodel.filterPredictionGroups
import com.footballai.app.ui.viewmodel.predictionGroups
import com.footballai.app.ui.viewmodel.predictionRecordStatus
import com.footballai.app.ui.viewmodel.isLatePreMatch
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictionListFilterTest {
    private fun item(id: String, matchId: String, status: MatchStatus, kickoff: Long = 200,
        created: Long = 100, resolved: Boolean = false, kind: PredictionKind = PredictionKind.PRE_MATCH): PredictionWithMatch {
        val prediction = mockk<PredictionView>()
        val match = mockk<MatchSummary>()
        every { prediction.id } returns id
        every { prediction.createdAtEpochMs } returns created
        every { prediction.kind } returns kind
        every { prediction.resolution } returns if (resolved) mockk<PredictionResolution>() else null
        every { match.id } returns matchId
        every { match.status } returns status
        every { match.kickoffEpochMs } returns kickoff
        return PredictionWithMatch(prediction, match)
    }

    @Test fun `status distinguishes upcoming live and finished without grading`() {
        val future = item("1", "future", MatchStatus.SCHEDULED)
        val stale = item("2", "stale", MatchStatus.SCHEDULED, kickoff = 90)
        val live = item("3", "live", MatchStatus.HALF_TIME)
        val finished = item("4", "finished", MatchStatus.FINISHED)
        val scored = item("5", "scored", MatchStatus.FINISHED, resolved = true)
        val postponed = item("6", "postponed", MatchStatus.POSTPONED)
        assertEquals(PredictionRecordStatus.UPCOMING, predictionRecordStatus(future, 150))
        assertEquals(PredictionRecordStatus.WAITING, predictionRecordStatus(stale, 150))
        assertEquals(PredictionRecordStatus.LIVE, predictionRecordStatus(live, 150))
        assertEquals(PredictionRecordStatus.WAITING, predictionRecordStatus(finished, 150))
        assertEquals(PredictionRecordStatus.RESOLVED, predictionRecordStatus(scored, 150))
        assertEquals(PredictionRecordStatus.WAITING, predictionRecordStatus(postponed, 150))
    }

    @Test fun `only identical match ids are grouped and every version is retained`() {
        val old = item("a", "provider1:match", MatchStatus.FINISHED, created = 100, resolved = true)
        val new = item("b", "provider1:match", MatchStatus.FINISHED, created = 200)
        val other = item("c", "provider2:match", MatchStatus.FINISHED)
        val groups = predictionGroups(listOf(old, other, new))
        assertEquals(2, groups.size)
        assertEquals(listOf("b", "a"), groups.first { it.matchId == "provider1:match" }.versions.map { it.prediction.id })
        assertEquals(listOf("provider1:match"), filterPredictionGroups(groups, PredictionListFilter.RESOLVED, 300).map { it.matchId })
        assertEquals(2, filterPredictionGroups(groups, PredictionListFilter.WAITING, 300).size)
    }

    @Test fun `late pre match is not presented as valid pre match and live is separate`() {
        assertTrue(isLatePreMatch(item("a", "m", MatchStatus.FINISHED, kickoff = 100, created = 100)))
        assertFalse(isLatePreMatch(item("b", "m", MatchStatus.FINISHED, kickoff = 100, created = 99)))
        assertFalse(isLatePreMatch(item("c", "m", MatchStatus.LIVE, kickoff = 100, created = 101, kind = PredictionKind.LIVE)))
    }
}
