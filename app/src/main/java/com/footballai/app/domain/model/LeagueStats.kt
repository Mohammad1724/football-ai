package com.footballai.app.domain.model

/**
 * A league read as numbers, from results the app already holds.
 *
 * Home wins, draws and away wins come from finished scorelines — the shape a reader compares a
 * forecast against — while card figures come from per-match statistics, which are downloaded
 * for a handful of matches at most. That is why the sample size travels with them: an average
 * over four matches must never be presented as a season's figure.
 */
data class LeagueStats(
    val matches: Int,
    val homeWins: Int,
    val draws: Int,
    val awayWins: Int,
    val goalsHome: Int,
    val goalsAway: Int,
    val cardMatches: Int,
    val yellowCards: Int?,
    val redCards: Int?,
) {
    val goalsPerMatch: Double get() = perMatch(goalsHome + goalsAway)
    val goalsHomePerMatch: Double get() = perMatch(goalsHome)
    val goalsAwayPerMatch: Double get() = perMatch(goalsAway)
    val homeWinShare: Double get() = perMatch(homeWins)
    val drawShare: Double get() = perMatch(draws)
    val awayWinShare: Double get() = perMatch(awayWins)

    /** Cards per match, or null when no match published card figures to average over. */
    val yellowCardsPerMatch: Double? get() = cardMatches.takeIf { it > 0 }?.let { yellowCards?.toDouble()?.div(it) }
    val redCardsPerMatch: Double? get() = cardMatches.takeIf { it > 0 }?.let { redCards?.toDouble()?.div(it) }

    private fun perMatch(tally: Int): Double =
        if (matches == 0) 0.0 else tally.toDouble() / matches

    companion object {
        /**
         * Counts a league from its finished scorelines.
         *
         * @param scores final score of each finished match, home first.
         */
        fun fromResults(
            scores: List<Pair<Int, Int>>,
            cardMatches: Int = 0,
            yellowCards: Int? = null,
            redCards: Int? = null,
        ): LeagueStats {
            var homeWins = 0
            var draws = 0
            var awayWins = 0
            var goalsHome = 0
            var goalsAway = 0
            for ((home, away) in scores) {
                goalsHome += home
                goalsAway += away
                when {
                    home > away -> homeWins++
                    home < away -> awayWins++
                    else -> draws++
                }
            }
            // A card total means nothing without the match count it was summed over.
            val sample = cardMatches.takeIf { it > 0 }
            return LeagueStats(
                matches = scores.size,
                homeWins = homeWins,
                draws = draws,
                awayWins = awayWins,
                goalsHome = goalsHome,
                goalsAway = goalsAway,
                cardMatches = sample ?: 0,
                yellowCards = yellowCards.takeIf { sample != null },
                redCards = redCards.takeIf { sample != null },
            )
        }
    }
}
