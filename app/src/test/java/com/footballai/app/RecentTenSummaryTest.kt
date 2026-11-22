package com.footballai.app

import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.domain.model.TeamResult
import com.footballai.app.ui.viewmodel.RecentTenSnapshot
import com.footballai.app.ui.viewmodel.RecentTenSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ten-match tab draws its summary from the same rows it lists, so the summary has to
 * agree with them exactly: newest first, one count per row, and no ratio whose sample is
 * hidden. The tab itself is offered only when there is a history to show.
 */
class RecentTenSummaryTest {

    private val bayern = TeamRef(id = "espn-open:team:132", name = "FC Bayern München")
    private val koln = TeamRef(id = "espn-open:team:122", name = "1. FC Köln")

    @Test
    fun `the run of results is read from the newest match backwards`() {
        val summary = RecentTenSummary.of(
            listOf(
                result(day = 1, forGoals = 3, againstGoals = 1, outcome = ResultOutcome.WIN),
                result(day = 2, forGoals = 1, againstGoals = 1, outcome = ResultOutcome.DRAW),
                result(day = 3, forGoals = 2, againstGoals = 0, outcome = ResultOutcome.WIN),
                result(day = 4, forGoals = 4, againstGoals = 1, outcome = ResultOutcome.WIN),
            ),
        )
        // Newest (day 4) is a win and so is day 3; day 2 breaks the run.
        assertEquals(2, summary.streak?.length)
        assertEquals(ResultOutcome.WIN, summary.streak?.outcome)
        assertEquals(3, summary.wins)
        assertEquals(1, summary.draws)
    }

    @Test
    fun `every count is over the rows on screen`() {
        val summary = RecentTenSummary.of(
            listOf(
                result(day = 1, forGoals = 0, againstGoals = 0, outcome = ResultOutcome.DRAW),
                result(day = 2, forGoals = 2, againstGoals = 2, outcome = ResultOutcome.DRAW),
                result(day = 3, forGoals = 3, againstGoals = 0, outcome = ResultOutcome.WIN, home = false),
                result(day = 4, forGoals = 1, againstGoals = 2, outcome = ResultOutcome.LOSS),
            ),
        )
        assertEquals(4, summary.played)
        assertEquals(2, summary.cleanSheets)
        assertEquals(1, summary.failedToScore)
        // 0+0, 2+2, 3+0, 1+2 goals in total: three of the four went over 2.5.
        assertEquals(3, summary.overTwoAndHalf)
        assertEquals(2, summary.bothScored)
        assertEquals(3, summary.homePlayed)
        assertEquals(0, summary.homeWins)
        assertEquals(1, summary.awayPlayed)
        assertEquals(1, summary.awayWins)
        assertEquals(6, summary.goalsFor)
        assertEquals(4, summary.goalsAgainst)
    }

    @Test
    fun `the biggest win is the largest margin, not the most goals`() {
        val summary = RecentTenSummary.of(
            listOf(
                result(day = 1, forGoals = 5, againstGoals = 3, outcome = ResultOutcome.WIN),
                result(day = 2, forGoals = 3, againstGoals = 0, outcome = ResultOutcome.WIN),
            ),
        )
        assertEquals(3, summary.bestWin?.goalsFor)
        assertEquals(0, summary.bestWin?.goalsAgainst)
    }

    @Test
    fun `points per game counts a win as three`() {
        val summary = RecentTenSummary.of(
            listOf(
                result(day = 1, forGoals = 1, againstGoals = 0, outcome = ResultOutcome.WIN),
                result(day = 2, forGoals = 1, againstGoals = 1, outcome = ResultOutcome.DRAW),
                result(day = 3, forGoals = 0, againstGoals = 1, outcome = ResultOutcome.LOSS),
                result(day = 4, forGoals = 2, againstGoals = 0, outcome = ResultOutcome.WIN),
            ),
        )
        // 3 + 1 + 0 + 3 = 7 points over four matches.
        assertEquals(1.75, summary.pointsPerGame)
    }

    @Test
    fun `a list shorter than ten is never called ten`() {
        val snapshot = RecentTenSnapshot.of(
            home = bayern,
            away = koln,
            homeResults = List(7) { day -> result(day, 1, 0, ResultOutcome.WIN) },
            awayResults = List(2) { day -> result(day, 0, 1, ResultOutcome.LOSS) },
        )
        assertEquals(7, snapshot.home.results.size)
        assertEquals(2, snapshot.away.results.size)
        assertEquals(10, RecentTenSnapshot.WINDOW)
        assertTrue(snapshot.isWorthATab)
    }

    @Test
    fun `a list longer than the window is cut to the window`() {
        val snapshot = RecentTenSnapshot.of(
            home = bayern,
            away = koln,
            homeResults = List(14) { day -> result(day, 1, 0, ResultOutcome.WIN) },
            awayResults = emptyList(),
        )
        assertEquals(RecentTenSnapshot.WINDOW, snapshot.home.results.size)
    }

    @Test
    fun `two matches are not a tab`() {
        val snapshot = RecentTenSnapshot.of(
            home = bayern,
            away = koln,
            homeResults = List(2) { day -> result(day, 1, 0, ResultOutcome.WIN) },
            awayResults = List(2) { day -> result(day, 0, 1, ResultOutcome.LOSS) },
        )
        assertFalse(snapshot.isWorthATab)
    }

    private fun result(
        day: Int,
        forGoals: Int,
        againstGoals: Int,
        outcome: ResultOutcome,
        home: Boolean = true,
        halfTimeFor: Int? = null,
    ) = TeamResult(
        matchId = "m$day",
        opponent = koln,
        kickoffEpochMs = day.toLong() * 86_400_000L,
        goalsFor = forGoals,
        goalsAgainst = againstGoals,
        wasHome = home,
        outcome = outcome,
        competitionId = "espn-open:league:ger.1",
        halfTimeGoalsFor = halfTimeFor,
        halfTimeGoalsAgainst = halfTimeFor?.let { 0 },
    )
}
