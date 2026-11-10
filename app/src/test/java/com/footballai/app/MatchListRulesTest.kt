package com.footballai.app

import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.viewmodel.MatchListFilter
import com.footballai.app.ui.viewmodel.MatchListRules
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchListRulesTest {
    @Test fun `upcoming includes all of the tenth future calendar day`() {
        val now = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()
        val range = MatchListRules.range(MatchListFilter.WEEK, now, ZoneId.of("Asia/Tehran"))
        assertEquals(now, range.first)
        assertEquals(Instant.parse("2026-11-20T20:30:00Z").toEpochMilli() - 1, range.second)
    }

    private fun match(id: String, time: Long, status: MatchStatus = MatchStatus.FINISHED, league: String = "L") =
        MatchSummary(id, league, league, null, TeamRef("h", "Home"), TeamRef("a", "Away"),
            time, status, null, null, null, 0, false, false, false, false)

    @Test fun `recent results are newest first and exclude unfinished games`() {
        val rows = listOf(match("old", 1), match("future", 4, MatchStatus.SCHEDULED), match("new", 3), match("middle", 2))
        assertEquals(listOf("new", "middle", "old"), MatchListRules.select(rows, MatchListFilter.FINISHED, null).map { it.id })
    }

    @Test fun `upcoming games are nearest first without finished results`() {
        val rows = listOf(match("later", 4, MatchStatus.SCHEDULED), match("done", 2), match("next", 3, MatchStatus.SCHEDULED))
        assertEquals(listOf("next", "later"), MatchListRules.select(rows, MatchListFilter.WEEK, null).map { it.id })
    }

    @Test fun `league selection is preserved when ordering results`() {
        val rows = listOf(match("other", 4, league = "B"), match("old", 1), match("new", 3))
        assertEquals(listOf("new", "old"), MatchListRules.select(rows, MatchListFilter.FINISHED, "L").map { it.id })
    }

    @Test fun `today uses local midnight not utc midnight`() {
        val now = Instant.parse("2026-11-10T00:15:00Z").toEpochMilli()
        val range = MatchListRules.range(MatchListFilter.TODAY, now, ZoneId.of("Asia/Tehran"))
        assertEquals(Instant.parse("2026-11-09T20:30:00Z").toEpochMilli(), range.first)
        assertEquals(Instant.parse("2026-11-10T20:30:00Z").toEpochMilli() - 1, range.second)
    }

    @Test fun `day boundary handles daylight saving and excludes next midnight`() {
        val now = Instant.parse("2026-03-29T12:00:00Z").toEpochMilli()
        val range = MatchListRules.range(MatchListFilter.TODAY, now, ZoneId.of("Europe/Berlin"))
        assertEquals(23 * 3600000L, range.second - range.first + 1)
        assertEquals(range.second + 1, MatchListRules.range(MatchListFilter.TOMORROW, now, ZoneId.of("Europe/Berlin")).first)
    }
}
