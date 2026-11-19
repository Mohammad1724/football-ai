package com.footballai.app

import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.MatchTableMark
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.components.TableRow
import com.footballai.app.ui.screens.MatchTableTeamMatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchTableTeamMatchingTest {
    private fun row(id: String, name: String) = TableRow(
        rank = 1, teamId = id, teamName = name, crestUrl = null,
        played = 9, goalDifference = 0, points = 12, form = null,
    )
    private fun match(status: MatchStatus = MatchStatus.SCHEDULED, home: Int? = null, away: Int? = null) = MatchSummary(
        id = "fixture", competitionId = "competition", competitionName = "Bundesliga", competitionEmblemUrl = null,
        home = TeamRef("fixture-bayern", "FC Bayern München"), away = TeamRef("fixture-koln", "1. FC Köln"),
        kickoffEpochMs = 1, status = status, minute = null, homeGoals = home, awayGoals = away,
        lastSyncedAt = 1, hasStatistics = false, hasLineups = false, hasEvents = false, hasOdds = false,
    )
    @Test
    fun `published aliases identify Bayern and Cologne even when ids differ`() {
        val rows = listOf(row("espn-bayern", "Bayern Munich"), row("espn-cologne", "FC Cologne"))
        assertEquals(mapOf("espn-bayern" to MatchTableMark.TEAM, "espn-cologne" to MatchTableMark.TEAM),
            MatchTableTeamMatching.marks(match(), rows))
        assertEquals(mapOf("espn-bayern" to MatchTableMark.WIN, "espn-cologne" to MatchTableMark.LOSS),
            MatchTableTeamMatching.marks(match(MatchStatus.FINISHED, 2, 0), rows))
        assertEquals("کلن", NameLocalization.displayName("FC Cologne", "fa"))
        assertEquals("FC Cologne", NameLocalization.displayName("FC Cologne", "en"))
    }
    @Test
    fun `ids take priority and ambiguous aliases do not choose a row`() {
        val rows = listOf(row("fixture-bayern", "Bayern Munich"), row("other-bayern", "Bayern Munich"),
            row("cologne-1", "FC Cologne"), row("cologne-2", "Cologne"))
        assertEquals(mapOf("fixture-bayern" to MatchTableMark.TEAM), MatchTableTeamMatching.marks(match(), rows))
    }
    @Test
    fun `reserves womens and similar clubs are not considered the first team`() {
        val rows = listOf(row("reserve", "Bayern Munich II"), row("womens", "Bayern Munich Women"),
            row("other", "Bayer Leverkusen"), row("reserve-cologne", "FC Cologne II"))
        assertTrue(MatchTableTeamMatching.marks(match(), rows).isEmpty())
        assertEquals(null, NameLocalization.curatedClubIdentity("FC Cologne II"))
    }
    @Test
    fun `unique identical names match but duplicate row ids are not safe`() {
        val m = match().copy(home = TeamRef("h", "Unknown Athletic"), away = TeamRef("a", "Unknown City"))
        assertEquals(mapOf("x" to MatchTableMark.TEAM, "y" to MatchTableMark.TEAM),
            MatchTableTeamMatching.marks(m, listOf(row("x", "UNKNOWN ATHLETIC"), row("y", "Unknown City"))))
        assertTrue(MatchTableTeamMatching.marks(m, listOf(row("x", "Unknown Athletic"), row("x", "Unknown City"))).isEmpty())
    }
}
