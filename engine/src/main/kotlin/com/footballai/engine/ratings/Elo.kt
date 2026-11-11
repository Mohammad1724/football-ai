package com.footballai.engine.ratings

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class EloConfig(
    val kFactor: Double = 20.0,
    /** Rating points granted to the home team before the match is evaluated. */
    val homeAdvantageElo: Double = 65.0,
    val initialRating: Double = 1500.0,
    /** Half-life of the rating regression toward the mean, in days. */
    val regressionHalfLifeDays: Double = 420.0,
    /** Extra K-factor weight for matches decided by a large margin. */
    val applyGoalDifferenceMultiplier: Boolean = true,
)

/**
 * Sequential Elo rating system with time decay.
 *
 * Ratings are updated after every finished match. Because a rating describes a
 * *current* strength, stored values regress toward the initial mean as time
 * passes since the team's last match (see [rating]).
 */
class EloRatingSystem(val config: EloConfig = EloConfig()) {

    private val ratings = HashMap<String, Double>()
    private val lastUpdateEpochMs = HashMap<String, Long>()
    private val matchesPlayed = HashMap<String, Int>()

    fun storedRating(teamId: String): Double = ratings[teamId] ?: config.initialRating

    /** Rating decayed toward the mean for the time elapsed since the last match. */
    fun rating(teamId: String, asOfEpochMs: Long): Double {
        val stored = ratings[teamId] ?: return config.initialRating
        val lastUpdate = lastUpdateEpochMs[teamId] ?: return stored
        val elapsedDays = ((asOfEpochMs - lastUpdate).coerceAtLeast(0L)) / DAY_MS.toDouble()
        val halfLife = config.regressionHalfLifeDays.coerceAtLeast(1.0)
        val decay = 0.5.pow(elapsedDays / halfLife)
        return config.initialRating + (stored - config.initialRating) * decay
    }

    fun matchesPlayed(teamId: String): Int = matchesPlayed[teamId] ?: 0

    /** Probability that the home team wins, treating a draw as half a win. */
    fun expectedHomeScore(homeTeamId: String, awayTeamId: String, asOfEpochMs: Long): Double {
        val diff = rating(homeTeamId, asOfEpochMs) + config.homeAdvantageElo - rating(awayTeamId, asOfEpochMs)
        return 1.0 / (1.0 + 10.0.pow(-diff / 400.0))
    }

    fun update(
        homeTeamId: String,
        awayTeamId: String,
        homeGoals: Int,
        awayGoals: Int,
        kickoffEpochMs: Long,
        weight: Double = 1.0,
    ) {
        require(homeGoals >= 0 && awayGoals >= 0) { "Goals must be non-negative" }
        if (weight <= 0.0) return

        val homeRating = rating(homeTeamId, kickoffEpochMs)
        val awayRating = rating(awayTeamId, kickoffEpochMs)
        val expectedHome = 1.0 / (1.0 + 10.0.pow(-((homeRating + config.homeAdvantageElo - awayRating) / 400.0)))
        val actualHome = when {
            homeGoals > awayGoals -> 1.0
            homeGoals == awayGoals -> 0.5
            else -> 0.0
        }
        val goalDifference = abs(homeGoals - awayGoals)
        val marginMultiplier = if (!config.applyGoalDifferenceMultiplier) {
            1.0
        } else {
            when {
                goalDifference <= 1 -> 1.0
                goalDifference == 2 -> 1.5
                else -> (11.0 + goalDifference) / 8.0
            }
        }
        val delta = config.kFactor * marginMultiplier * weight * (actualHome - expectedHome)

        ratings[homeTeamId] = homeRating + delta
        ratings[awayTeamId] = awayRating - delta
        lastUpdateEpochMs[homeTeamId] = kickoffEpochMs
        lastUpdateEpochMs[awayTeamId] = kickoffEpochMs
        matchesPlayed[homeTeamId] = (matchesPlayed[homeTeamId] ?: 0) + 1
        matchesPlayed[awayTeamId] = (matchesPlayed[awayTeamId] ?: 0) + 1
    }

    /**
     * Maps a rating difference to 1X2 probabilities. The draw probability shrinks
     * as the two teams become more unequal, which mirrors observed football data.
     * This is a display/feature aid only; the goal model remains authoritative.
     */
    fun outcomeProbabilities(homeTeamId: String, awayTeamId: String, asOfEpochMs: Long): Triple<Double, Double, Double> {
        val diff = rating(homeTeamId, asOfEpochMs) + config.homeAdvantageElo - rating(awayTeamId, asOfEpochMs)
        val expectedHome = 1.0 / (1.0 + 10.0.pow(-diff / 400.0))
        val drawProbability = (0.30 - 0.00035 * abs(diff)).coerceIn(0.14, 0.32)
        val remaining = 1.0 - drawProbability
        val home = (remaining * expectedHome).coerceIn(0.01, 0.98)
        val away = (remaining - home).coerceIn(0.01, 0.98)
        val total = home + drawProbability + away
        return Triple(home / total, drawProbability / total, away / total)
    }

    fun snapshot(asOfEpochMs: Long): Map<String, Double> = ratings.keys.associateWith { rating(it, asOfEpochMs) }

    /** Copies the internal state of another instance; used when replaying history. */
    internal fun replaceWith(other: EloRatingSystem) {
        ratings.clear()
        ratings.putAll(other.ratings)
        lastUpdateEpochMs.clear()
        lastUpdateEpochMs.putAll(other.lastUpdateEpochMs)
        matchesPlayed.clear()
        matchesPlayed.putAll(other.matchesPlayed)
    }

    companion object {
        const val DAY_MS = 86_400_000L

        /** Converts an Elo difference into an expected-score probability. */
        fun expectedScore(ratingDifference: Double): Double = 1.0 / (1.0 + 10.0.pow(-ratingDifference / 400.0))

        /** Inverse of [expectedScore], useful for diagnostics. */
        fun ratingDifference(expectedScore: Double): Double {
            val clamped = expectedScore.coerceIn(0.001, 0.999)
            return 400.0 * ln(clamped / (1.0 - clamped)) / ln(10.0)
        }

        /** Exponential decay helper shared by the time-weighted models. */
        fun timeDecayWeight(ageDays: Double, halfLifeDays: Double): Double {
            if (halfLifeDays <= 0.0) return 1.0
            return exp(-ln(2.0) * (ageDays.coerceAtLeast(0.0)) / halfLifeDays)
        }
    }
}
