package com.footballai.app

import com.footballai.app.ui.components.TableRow
import com.footballai.app.ui.viewmodel.LeagueTableUiState
import com.footballai.app.ui.viewmodel.shouldRefreshCompetitionTable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompetitionTableRefreshTest {
    @Test
    fun `explicit retry refreshes a previously downloaded table`() {
        val row = TableRow(rank = 1, teamId = "a", teamName = "A", played = 1,
            crestUrl = null, goalDifference = 0, points = 1, form = null)
        val published = LeagueTableUiState(rows = listOf(row))
        assertFalse(shouldRefreshCompetitionTable(refresh = true, force = false, table = published))
        assertTrue(shouldRefreshCompetitionTable(refresh = true, force = true, table = published))
        assertFalse(shouldRefreshCompetitionTable(refresh = false, force = true, table = published))
        assertTrue(shouldRefreshCompetitionTable(refresh = true, force = false, table = LeagueTableUiState()))
        assertTrue(shouldRefreshCompetitionTable(refresh = true, force = false, table = published.copy(isComputed = true)))
    }
}
