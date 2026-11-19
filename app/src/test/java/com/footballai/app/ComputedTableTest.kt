package com.footballai.app

import com.footballai.app.domain.model.ComputedTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A table counted from results.
 *
 * It exists for leagues whose source publishes results but no table, so the rules that matter
 * are the ones a reader checks first: three for a win, one for a draw, and the order that puts
 * the better team above when points are level.
 */
class ComputedTableTest {

    private fun result(home: String, away: String, homeGoals: Int, awayGoals: Int) =
        ComputedTable.Result(home, away, homeGoals, awayGoals)

    @Test
    fun winsDrawsLossesAndGoalsAreCountedFromScorelines() {
        val rows = ComputedTable.rows(
            listOf(
                result("a", "b", 3, 1),
                result("b", "a", 0, 0),
            ),
        )
        val a = rows.single { it.teamId == "a" }
        assertEquals(2, a.played)
        assertEquals(1, a.wins)
        assertEquals(1, a.draws)
        assertEquals(0, a.losses)
        assertEquals(3, a.goalsFor)
        assertEquals(1, a.goalsAgainst)
        assertEquals(2, a.goalDifference)
        assertEquals(4, a.points)

        val b = rows.single { it.teamId == "b" }
        assertEquals(1, b.losses)
        assertEquals(1, b.points)
        assertEquals(-2, b.goalDifference)
    }

    @Test
    fun pointsThenGoalDifferenceThenGoalsScored() {
        val rows = ComputedTable.rows(
            listOf(
                // "wide" wins big once; "narrow" wins twice by one goal: both on 6 points,
                // and the goal difference decides.
                result("wide", "x", 5, 0),
                result("wide", "x", 0, 0),
                result("narrow", "y", 1, 0),
                result("narrow", "y", 1, 0),
            ),
        )
        assertEquals(listOf("wide", "narrow", "x", "y"), rows.map { it.teamId })
    }

    @Test
    fun teamsAreLevelOnlyWhenEverythingIsEqualAndThenTheOrderIsStable() {
        val rows = ComputedTable.rows(
            listOf(
                result("b", "z", 1, 0),
                result("a", "y", 1, 0),
            ),
        )
        // Same points, same goal difference, same goals scored: a fixed order, not a random one.
        assertEquals(listOf("a", "b", "y", "z"), rows.map { it.teamId })
    }

    @Test
    fun aTeamThatHasNotPlayedIsNotInTheTable() {
        val rows = ComputedTable.rows(listOf(result("a", "b", 1, 1)))
        assertEquals(setOf("a", "b"), rows.map { it.teamId }.toSet())
    }

    @Test
    fun noResultsIsNoTable() {
        assertTrue(ComputedTable.rows(emptyList()).isEmpty())
    }
}
