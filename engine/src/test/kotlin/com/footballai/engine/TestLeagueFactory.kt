package com.footballai.engine

import com.footballai.engine.ratings.RatingMatch
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/**
 * Deterministic synthetic leagues used by the unit tests.
 *
 * Data is generated from a known Dixon-Coles style process so that the estimators
 * can be checked against the parameters that produced the data.
 */
object TestLeagueFactory {

    data class SyntheticLeague(
        val matches: List<RatingMatch>,
        val attack: Map<String, Double>,
        val defence: Map<String, Double>,
        val homeBase: Double,
        val awayBase: Double,
    )

    fun create(
        seed: Long = 42L,
        teamCount: Int = 8,
        seasons: Int = 5,
        matchesPerPairPerSeason: Int = 2,
        homeBase: Double = 1.45,
        awayBase: Double = 1.15,
        startEpochMs: Long = 1_600_000_000_000L,
    ): SyntheticLeague {
        val random = Random(seed)
        val teams = (1..teamCount).map { "T%02d".format(it) }
        val attack = HashMap<String, Double>()
        val defence = HashMap<String, Double>()
        teams.forEachIndexed { index, team ->
            // Spread of strengths around 1.0, deterministic and reasonably wide.
            val offset = (index - (teamCount - 1) / 2.0) * 0.12
            attack[team] = exp(offset + random.nextGaussian()/3.0 * 0.08)
            defence[team] = exp(-offset * 0.6 + random.nextGaussian()/3.0 * 0.08)
        }

        val matches = ArrayList<RatingMatch>()
        var kickoff = startEpochMs
        for (season in 0 until seasons) {
            for (homeIndex in teams.indices) {
                for (awayIndex in teams.indices) {
                    if (homeIndex == awayIndex) continue
                    repeat(matchesPerPairPerSeason) {
                        val homeTeam = teams[homeIndex]
                        val awayTeam = teams[awayIndex]
                        val lambdaHome = homeBase * attack.getValue(homeTeam) * defence.getValue(awayTeam)
                        val lambdaAway = awayBase * attack.getValue(awayTeam) * defence.getValue(homeTeam)
                        val homeGoals = samplePoisson(random, lambdaHome)
                        val awayGoals = samplePoisson(random, lambdaAway)
                        kickoff += 3L * 86_400_000L
                        matches.add(
                            RatingMatch(
                                homeTeamId = homeTeam,
                                awayTeamId = awayTeam,
                                homeGoals = homeGoals,
                                awayGoals = awayGoals,
                                kickoffEpochMs = kickoff,
                            ),
                        )
                    }
                }
            }
        }
        return SyntheticLeague(matches.sortedBy { it.kickoffEpochMs }, attack, defence, homeBase, awayBase)
    }

    /** Knuth's algorithm; adequate for the small lambdas used in football models. */
    fun samplePoisson(random: Random, lambda: Double): Int {
        val limit = exp(-lambda)
        var product = 1.0
        var count = 0
        do {
            count++
            product *= random.nextDouble()
        } while (product > limit)
        return (count - 1).coerceAtLeast(0)
    }

    fun correlation(xs: List<Double>, ys: List<Double>): Double {
        require(xs.size == ys.size && xs.isNotEmpty()) { "samples must be non-empty and of equal size" }
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX <= 0.0 || varianceY <= 0.0) return 0.0
        return covariance / kotlin.math.sqrt(varianceX * varianceY)
    }

    fun logAttack(attack: Map<String, Double>): List<Double> = attack.values.map { ln(it) }
}
