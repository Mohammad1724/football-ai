package com.footballai.app

import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchTableHighlight
import com.footballai.app.domain.model.MatchTableMark
import com.footballai.app.domain.model.TableSeasonComparison
import com.footballai.app.domain.model.compareTableSeason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchTableHighlightTest {
    @Test
    fun `season ids must not imply that the provider season is verified`() {
        assertEquals(TableSeasonComparison.SAME_UNVERIFIED, compareTableSeason(2026, 2026))
        assertEquals(TableSeasonComparison.DIFFERENT, compareTableSeason(2026, 2025))
        assertEquals(TableSeasonComparison.UNKNOWN, compareTableSeason(null, 2026))
        assertEquals(TableSeasonComparison.UNKNOWN, compareTableSeason(2026, 0))
    }
    @Test
    fun `completed scores mark both teams without changing the table`() {
        fun marks(home: Int, away: Int) = MatchTableHighlight.forMatch("h", "a", MatchStatus.FINISHED, home, away)
        assertEquals(mapOf("h" to MatchTableMark.WIN, "a" to MatchTableMark.LOSS), marks(2, 1))
        assertEquals(mapOf("h" to MatchTableMark.LOSS, "a" to MatchTableMark.WIN), marks(0, 1))
        assertEquals(mapOf("h" to MatchTableMark.DRAW, "a" to MatchTableMark.DRAW), marks(1, 1))
    }
    @Test
    fun `live scores are provisional and missing scores never invent an outcome`() {
        assertEquals(mapOf("h" to MatchTableMark.LEADING, "a" to MatchTableMark.TRAILING),
            MatchTableHighlight.forMatch("h", "a", MatchStatus.LIVE, 2, 1))
        assertEquals(mapOf("h" to MatchTableMark.LEVEL, "a" to MatchTableMark.LEVEL),
            MatchTableHighlight.forMatch("h", "a", MatchStatus.HALF_TIME, 1, 1))
        for (status in listOf(MatchStatus.SCHEDULED, MatchStatus.CANCELLED, MatchStatus.POSTPONED, MatchStatus.UNKNOWN)) {
            assertEquals(mapOf("h" to MatchTableMark.TEAM, "a" to MatchTableMark.TEAM),
                MatchTableHighlight.forMatch("h", "a", status, 3, 0))
        }
        assertEquals(MatchTableMark.TEAM, MatchTableHighlight.forMatch("h", "a", MatchStatus.FINISHED, null, 0)["h"])
        assertEquals(MatchTableMark.TEAM, MatchTableHighlight.forMatch("h", "a", MatchStatus.LIVE, -1, 0)["h"])
        assertTrue(MatchTableHighlight.forMatch("h", "h", MatchStatus.FINISHED, 1, 0).isEmpty())
    }
}
