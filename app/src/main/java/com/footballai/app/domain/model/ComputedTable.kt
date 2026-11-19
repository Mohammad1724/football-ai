package com.footballai.app.domain.model

/**
 * Which matches a table counts.
 *
 * A published table gives one combined set of numbers, so a source that publishes nothing can
 * still be read the way a reader asks about it: at home, away, or altogether.
 */
enum class TableVenue { ALL, HOME, AWAY }

/** One line of a table built from results, rather than read from a published table. */
data class ComputedTableRow(
    val teamId: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
) {
    val points: Int get() = wins * 3 + draws
    val goalDifference: Int get() = goalsFor - goalsAgainst
}

/**
 * A league table counted from the results the app already holds.
 *
 * Some competitions are covered by a source that publishes results but no table — the Polish,
 * Czech, Ukrainian and Croatian first tiers on the feed this app reads, for instance — and a
 * reader who opens one of those should still see who is above whom. Nothing here is invented:
 * every number is the sum of scorelines in the local database, and the caller says how many
 * matches it was counted from, because a table over eight matches is not the league's table.
 *
 * Ordering is the ordinary one — points, then goal difference, then goals scored. Teams are
 * never separated by a rule the app cannot support (head-to-head, fair play), so two clubs
 * level on all three keep their published order by id and the reader sees why they are level.
 */
object ComputedTable {

    /** A finished match, as the builder needs it. */
    data class Result(
        val homeTeamId: String,
        val awayTeamId: String,
        val homeGoals: Int,
        val awayGoals: Int,
    )

    fun rows(results: List<Result>, venue: TableVenue = TableVenue.ALL): List<ComputedTableRow> {
        if (results.isEmpty()) return emptyList()
        val tallies = LinkedHashMap<String, MutableTally>()
        fun tally(teamId: String) = tallies.getOrPut(teamId) { MutableTally() }
        for (result in results) {
            // A home table counts only the matches a club played at home, an away table only
            // the ones it played away: a match is not one row for both sides in those views.
            val home = if (venue != TableVenue.AWAY) tally(result.homeTeamId) else null
            val away = if (venue != TableVenue.HOME) tally(result.awayTeamId) else null
            if (home != null) {
                home.played++
                home.goalsFor += result.homeGoals
                home.goalsAgainst += result.awayGoals
            }
            if (away != null) {
                away.played++
                away.goalsFor += result.awayGoals
                away.goalsAgainst += result.homeGoals
            }
            when {
                result.homeGoals > result.awayGoals -> if (home != null) home.wins++
                result.homeGoals < result.awayGoals -> if (away != null) away.wins++
                else -> {
                    if (home != null) home.draws++
                    if (away != null) away.draws++
                }
            }
        }
        return tallies.map { (teamId, tally) ->
            ComputedTableRow(
                teamId = teamId,
                played = tally.played,
                wins = tally.wins,
                draws = tally.draws,
                losses = tally.played - tally.wins - tally.draws,
                goalsFor = tally.goalsFor,
                goalsAgainst = tally.goalsAgainst,
            )
        }.sortedWith(
            compareByDescending<ComputedTableRow> { it.points }
                .thenByDescending { it.goalDifference }
                .thenByDescending { it.goalsFor }
                .thenBy { it.teamId },
        )
    }

    private class MutableTally {
        var played = 0
        var wins = 0
        var draws = 0
        var goalsFor = 0
        var goalsAgainst = 0
    }
}
