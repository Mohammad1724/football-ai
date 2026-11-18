package com.footballai.app

import com.footballai.app.domain.model.MatchEvent
import com.footballai.app.domain.model.MatchEventType
import com.footballai.app.domain.model.MatchTimeline
import com.footballai.app.domain.model.TimelineEntry
import com.footballai.app.domain.model.TimelineSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The timeline's ordering rules, which the screen draws but does not decide. */
class MatchTimelineTest {

    private fun event(
        id: String,
        minute: Int,
        type: MatchEventType,
        team: String? = HOME,
        player: String? = null,
        assist: String? = null,
    ) = MatchEvent(
        id = id,
        minute = minute,
        type = type,
        detail = null,
        teamId = team,
        playerName = player,
        assistName = assist,
    )

    @Test
    fun halfTimeBreakIsPlacedWhereItHappened() {
        val entries = MatchTimeline.build(
            events = listOf(
                event("1", 12, MatchEventType.GOAL, player = "Saka"),
                event("2", 58, MatchEventType.GOAL, team = AWAY, player = "Isak"),
            ),
            homeTeamId = HOME,
            awayTeamId = AWAY,
            includeHalfTime = true,
        )
        assertEquals(listOf(12, 45, 58), entries.map { it.minute })
        assertTrue(entries[1].isHalfTime)
        assertEquals(TimelineSide.HOME, entries[0].side)
        assertEquals(TimelineSide.AWAY, entries[2].side)
    }

    @Test
    fun aFirstHalfStillInPlayHasNoBreakYet() {
        val entries = MatchTimeline.build(
            events = listOf(event("1", 30, MatchEventType.YELLOW_CARD, player = "Rice")),
            homeTeamId = HOME,
            awayTeamId = AWAY,
            includeHalfTime = false,
        )
        assertEquals(listOf(30), entries.map { it.minute })
    }

    @Test
    fun anAssistThatIsTheScorerIsNotAnAssist() {
        // Some sources fill the assist with the scorer himself for a penalty.
        val entries = MatchTimeline.build(
            events = listOf(
                event("1", 22, MatchEventType.PENALTY_GOAL, player = "Kane", assist = " kane "),
            ),
            homeTeamId = HOME,
            awayTeamId = AWAY,
            includeHalfTime = false,
        )
        assertEquals("Kane", entries.single().playerName)
        assertNull(entries.single().assistName)
    }

    @Test
    fun aRealAssistSurvivesAndASubstitutionKeepsBothNames() {
        val entries = MatchTimeline.build(
            events = listOf(
                event("1", 9, MatchEventType.GOAL, player = "Haaland", assist = "De Bruyne"),
                event("2", 64, MatchEventType.SUBSTITUTION, player = "Alvarez", assist = "Haaland"),
            ),
            homeTeamId = HOME,
            awayTeamId = AWAY,
            includeHalfTime = true,
        )
        assertEquals("De Bruyne", entries[0].assistName)
        // The break sits between them: 45' falls after the goal and before the change.
        val substitution = entries.single { it.kind == TimelineEntry.Kind.SUBSTITUTION }
        assertEquals(TimelineEntry.Kind.SUBSTITUTION, substitution.kind)
        assertEquals("Alvarez", substitution.playerName)
        assertEquals("Haaland", substitution.assistName)
    }

    @Test
    fun eventsWithoutATeamAreNotGivenASide() {
        val entries = MatchTimeline.build(
            events = listOf(event("1", 70, MatchEventType.VAR, team = null)),
            homeTeamId = HOME,
            awayTeamId = AWAY,
            includeHalfTime = true,
        )
        // The break was already reached, so the VAR call of the second half follows it.
        assertEquals(2, entries.size)
        assertTrue(entries[0].isHalfTime)
        assertEquals(TimelineSide.UNKNOWN, entries[1].side)
    }

    private companion object {
        const val HOME = "team-home"
        const val AWAY = "team-away"
    }
}
