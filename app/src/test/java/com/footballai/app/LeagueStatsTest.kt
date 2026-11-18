package com.footballai.app

import com.footballai.app.domain.model.LeagueStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The league page's arithmetic: counted from scorelines, honest about its sample. */
class LeagueStatsTest {

    @Test
    fun countsHomeDrawAwayFromScorelines() {
        val stats = LeagueStats.fromResults(
            listOf(2 to 1, 0 to 0, 1 to 3, 2 to 0),
        )
        assertEquals(4, stats.matches)
        assertEquals(2, stats.homeWins)
        assertEquals(1, stats.draws)
        assertEquals(1, stats.awayWins)
        assertEquals(5, stats.goalsHome)
        assertEquals(4, stats.goalsAway)
        // 9 goals in 4 matches.
        assertEquals(2.25, stats.goalsPerMatch)
        assertEquals(0.5, stats.homeWinShare)
        assertEquals(0.25, stats.drawShare)
        assertEquals(0.25, stats.awayWinShare)
    }

    @Test
    fun emptyLeagueHasNoSharesAndDoesNotDivideByZero() {
        val stats = LeagueStats.fromResults(emptyList())
        assertEquals(0, stats.matches)
        assertEquals(0.0, stats.goalsPerMatch)
        assertEquals(0.0, stats.homeWinShare)
        assertNull(stats.yellowCardsPerMatch)
    }

    @Test
    fun cardAverageNeedsItsSampleSize() {
        val stats = LeagueStats.fromResults(
            scores = listOf(1 to 1, 1 to 0),
            cardMatches = 2,
            yellowCards = 7,
            redCards = 1,
        )
        assertEquals(3.5, stats.yellowCardsPerMatch)
        assertEquals(0.5, stats.redCardsPerMatch)

        // Totals without a match count are dropped, not averaged against zero.
        val unsized = LeagueStats.fromResults(scores = listOf(1 to 1), yellowCards = 7)
        assertNull(unsized.yellowCards)
        assertNull(unsized.yellowCardsPerMatch)
        assertEquals(0, unsized.cardMatches)
    }
}
