package com.footballai.app

import com.footballai.app.data.local.entity.*
import com.footballai.app.data.repository.HistoryBridge
import org.junit.Test
import kotlin.test.*

class HistoryBridgeTest {
    private fun team(id: String, name: String, short: String? = null) =
        TeamEntity(id, "football-data-org", id, name, short, null, 0)
    private val row = HistoricalMatchEntity("m", "csv", "league", "league", "h", "a", "Arsenal", "Chelsea",
        1, 0, 1, null, null, null, 0)
    @Test fun `exact names or exact short names map both endpoints`() {
        val mapped = HistoryBridge.map(listOf(row), listOf(team("1", "Arsenal FC", "Arsenal"), team("2", "Chelsea")))
        assertEquals("1", mapped.single().homeTeamId)
        assertEquals("2", mapped.single().awayTeamId)
    }
    @Test fun `similar names are not guessed`() {
        assertTrue(HistoryBridge.map(listOf(row), listOf(team("1", "Arsenal FC"), team("2", "Chelsea"))).isEmpty())
    }
    @Test fun `ambiguous target names are rejected`() {
        assertTrue(HistoryBridge.map(listOf(row), listOf(team("1", "Arsenal"), team("3", "Arsenal"), team("2", "Chelsea"))).isEmpty())
    }
    @Test fun `ambiguous source names are rejected`() {
        assertTrue(HistoryBridge.map(listOf(row, row.copy(homeTeamId = "other")), listOf(team("1", "Arsenal"), team("2", "Chelsea"))).isEmpty())
    }
    @Test fun `Brazil is not assigned European history`() {
        assertNull(HistoryBridge.leagues["BSA"])
    }
}
