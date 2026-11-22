package com.footballai.app

import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.domain.model.TeamResult
import com.footballai.app.ui.viewmodel.TeamFormSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The form panel counts what the rows say and nothing else. The second-half figure has its
 * own sample: a match without a published half-time score cannot contribute to it, and the
 * ratio must never be built from two different sets of matches.
 */
class TeamFormSnapshotTest {

    private fun result(
        goalsFor: Int,
        goalsAgainst: Int,
        wasHome: Boolean = true,
        halfTimeFor: Int? = null,
        halfTimeAgainst: Int? = null,
    ) = TeamResult(
        matchId = "m$goalsFor$goalsAgainst${halfTimeFor ?: "x"}",
        opponent = TeamRef(id = "opp", name = "Opponent"),
        kickoffEpochMs = 1_700_000_000_000L,
        goalsFor = goalsFor,
        goalsAgainst = goalsAgainst,
        wasHome = wasHome,
        outcome = when {
            goalsFor > goalsAgainst -> ResultOutcome.WIN
            goalsFor == goalsAgainst -> ResultOutcome.DRAW
            else -> ResultOutcome.LOSS
        },
        competitionId = "c",
        halfTimeGoalsFor = halfTimeFor,
        halfTimeGoalsAgainst = halfTimeAgainst,
    )

    @Test
    fun `counts come from the rows themselves`() {
        val snapshot = TeamFormSnapshot.of(
            listOf(
                result(2, 1),
                result(0, 0),
                result(1, 3, wasHome = false),
            ),
        )
        assertEquals(3, snapshot.played)
        assertEquals(1, snapshot.wins)
        assertEquals(1, snapshot.draws)
        assertEquals(1, snapshot.losses)
        assertEquals(3, snapshot.goalsFor)
        assertEquals(4, snapshot.goalsAgainst)
        assertEquals(1, snapshot.homeWins)
        assertEquals(1, snapshot.cleanSheets)
    }

    @Test
    fun `a match without a half-time score stays out of the second-half figure`() {
        val snapshot = TeamFormSnapshot.of(
            listOf(
                // 1-0 at the break, 2-1 at the end: one goal after it.
                result(2, 1, halfTimeFor = 1, halfTimeAgainst = 0),
                // 0-0 at the break, 0-0 at the end.
                result(0, 0, halfTimeFor = 0, halfTimeAgainst = 0),
                // No break score published: this row cannot say anything about halves.
                result(3, 2),
            ),
        )
        assertEquals(1, snapshot.secondHalfGoalsFor)
        assertEquals(1, snapshot.secondHalfGoalsAgainst)
        assertEquals(2, snapshot.halfTimeKnown)
        assertEquals(2, snapshot.halfTimeGoalsTotal)
        assertFalse(snapshot.halfTimeComplete)
    }

    @Test
    fun `the half-time figure is complete only when every match has one`() {
        val snapshot = TeamFormSnapshot.of(
            listOf(
                result(1, 0, halfTimeFor = 1, halfTimeAgainst = 0),
                result(0, 2, halfTimeFor = 0, halfTimeAgainst = 1),
            ),
        )
        assertEquals(2, snapshot.halfTimeKnown)
        // 1-0 at the break and 1-0 at the end: nothing scored after it.
        assertEquals(0, snapshot.secondHalfGoalsFor)
        // 0-1 at the break and 0-2 at the end: one conceded after it.
        assertEquals(1, snapshot.secondHalfGoalsAgainst)
        assertEquals(1, snapshot.halfTimeGoalsTotal)
        assertTrue(snapshot.halfTimeComplete)
    }
}
