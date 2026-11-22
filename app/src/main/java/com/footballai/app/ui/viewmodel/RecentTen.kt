package com.footballai.app.ui.viewmodel

import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.domain.model.TeamResult

/**
 * One team's ten-match history, as the «۱۰ بازی اخیر» tab reads it.
 *
 * The window size travels with the rows so a screen can say "۷ از ۱۰" instead of calling
 * a seven-match list "ten": that claim was a defect once, and the number now comes from
 * the data rather than from the heading.
 */
data class RecentTenTeam(
    val team: TeamRef,
    /** Finished matches only, newest first, at most [RecentTenSnapshot.WINDOW] of them. */
    val results: List<TeamResult>,
    /** Published competition name per id, so a row can name the league it was played in. */
    val competitionNames: Map<String, String> = emptyMap(),
)

data class RecentTenSnapshot(
    val home: RecentTenTeam,
    val away: RecentTenTeam,
) {
    val longest: Int get() = maxOf(home.results.size, away.results.size)

    /**
     * A tab is offered only when it has something to show.
     *
     * Two matches is a pair of rows under a "last ten" heading, not a history; an
     * almost-empty tab reads as a broken page rather than an honest one.
     */
    val isWorthATab: Boolean get() = longest >= MIN_ROWS

    companion object {
        const val WINDOW = 10

        /** Below this the panel is a heading plus an apology, so it is not offered. */
        const val MIN_ROWS = 3

        fun of(
            home: TeamRef,
            away: TeamRef,
            homeResults: List<TeamResult>,
            awayResults: List<TeamResult>,
            competitionNames: Map<String, String> = emptyMap(),
        ): RecentTenSnapshot = RecentTenSnapshot(
            home = RecentTenTeam(home, homeResults.take(WINDOW), competitionNames),
            away = RecentTenTeam(away, awayResults.take(WINDOW), competitionNames),
        )
    }
}

/**
 * The numbers behind the ten-match tab, computed once from the rows on screen.
 *
 * Every figure is a count over the same list the user can read underneath it, so the
 * summary can be checked against the rows. A statistic whose sample is smaller than the
 * window carries its sample size ([halfTimeKnown]) instead of hiding it, and the ordering
 * is newest-first because a run of results is read from the latest match backwards.
 */
data class RecentTenSummary(
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val cleanSheets: Int,
    val failedToScore: Int,
    val overTwoAndHalf: Int,
    val bothScored: Int,
    val homePlayed: Int,
    val homeWins: Int,
    val awayPlayed: Int,
    val awayWins: Int,
    /** How many of these matches published a half-time score. */
    val halfTimeKnown: Int,
    /** The current run of identical results, newest first; null for an empty list. */
    val streak: Streak?,
    /** The biggest win by margin, the newest of equals; null when the team never won. */
    val bestWin: TeamResult?,
) {
    data class Streak(val outcome: ResultOutcome, val length: Int)

    val pointsPerGame: Double
        get() = if (played == 0) 0.0 else (wins * 3 + draws).toDouble() / played

    val averageGoalsFor: Double get() = if (played == 0) 0.0 else goalsFor.toDouble() / played

    val averageGoalsAgainst: Double get() = if (played == 0) 0.0 else goalsAgainst.toDouble() / played

    companion object {
        fun of(results: List<TeamResult>): RecentTenSummary {
            val ordered = results.sortedByDescending { it.kickoffEpochMs }
            val best = ordered
                .filter { it.outcome == ResultOutcome.WIN }
                .maxWithOrNull(
                    compareBy<TeamResult> { it.goalsFor - it.goalsAgainst }.thenBy { it.goalsFor },
                )
            val streakOutcome = ordered.firstOrNull()?.outcome
            val streakLength = if (streakOutcome == null) 0 else {
                ordered.takeWhile { it.outcome == streakOutcome }.size
            }
            return RecentTenSummary(
                played = ordered.size,
                wins = ordered.count { it.outcome == ResultOutcome.WIN },
                draws = ordered.count { it.outcome == ResultOutcome.DRAW },
                losses = ordered.count { it.outcome == ResultOutcome.LOSS },
                goalsFor = ordered.sumOf { it.goalsFor },
                goalsAgainst = ordered.sumOf { it.goalsAgainst },
                cleanSheets = ordered.count { it.goalsAgainst == 0 },
                failedToScore = ordered.count { it.goalsFor == 0 },
                overTwoAndHalf = ordered.count { it.goalsFor + it.goalsAgainst >= 3 },
                bothScored = ordered.count { it.goalsFor > 0 && it.goalsAgainst > 0 },
                homePlayed = ordered.count { it.wasHome },
                homeWins = ordered.count { it.wasHome && it.outcome == ResultOutcome.WIN },
                awayPlayed = ordered.count { !it.wasHome },
                awayWins = ordered.count { !it.wasHome && it.outcome == ResultOutcome.WIN },
                halfTimeKnown = ordered.count { it.halfTimeGoalsFor != null },
                streak = streakOutcome?.let { Streak(it, streakLength) },
                bestWin = best,
            )
        }
    }
}
