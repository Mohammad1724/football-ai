package com.footballai.engine.poisson

import com.footballai.engine.math.Poisson
import com.footballai.engine.model.ScoreProbability

/**
 * Joint distribution of the number of goals scored by both teams.
 *
 * The matrix uses independent Poisson distributions for the two teams and, when
 * [rho] is non-zero, applies the Dixon-Coles tau correction that lifts the
 * probability of the four low scorelines (0-0, 1-0, 0-1, 1-1) which independent
 * Poisson models systematically under/over predict.
 *
 * The visible support is truncated at [maxGoals] per team; the remaining mass is
 * renormalised back into the matrix so that every market derived from it sums to
 * exactly 1 (up to floating point rounding).
 */
class ScoreMatrix(
    val homeLambda: Double,
    val awayLambda: Double,
    val rho: Double = 0.0,
    val maxGoals: Int = DEFAULT_MAX_GOALS,
) {
    private val size = maxGoals + 1
    private val cells: DoubleArray = DoubleArray(size * size)

    init {
        require(homeLambda >= 0.0 && homeLambda.isFinite()) { "homeLambda must be non-negative and finite" }
        require(awayLambda >= 0.0 && awayLambda.isFinite()) { "awayLambda must be non-negative and finite" }
        require(maxGoals in 2..20) { "maxGoals must be within 2..20" }

        var sum = 0.0
        for (h in 0..maxGoals) {
            val pHome = Poisson.pmf(h, homeLambda)
            for (a in 0..maxGoals) {
                val value = pHome * Poisson.pmf(a, awayLambda) * tau(h, a)
                cells[index(h, a)] = value
                sum += value
            }
        }
        if (sum <= 0.0) {
            // Degenerate input: fall back to a single deterministic scoreline.
            cells.fill(0.0)
            cells[index(0, 0)] = 1.0
        } else {
            for (i in cells.indices) cells[i] /= sum
        }
    }

    /**
     * Dixon-Coles low-score dependency factor.
     * See Dixon & Coles (1997), "Modelling association football scores and
     * inefficiencies in the football betting market".
     */
    private fun tau(homeGoals: Int, awayGoals: Int): Double {
        if (rho == 0.0) return 1.0
        val factor = when {
            homeGoals == 0 && awayGoals == 0 -> 1.0 - homeLambda * awayLambda * rho
            homeGoals == 0 && awayGoals == 1 -> 1.0 + homeLambda * rho
            homeGoals == 1 && awayGoals == 0 -> 1.0 + awayLambda * rho
            homeGoals == 1 && awayGoals == 1 -> 1.0 - rho
            else -> 1.0
        }
        // A negative factor is undefined for a probability matrix; treat it as no correction.
        return if (factor > 0.0) factor else 1.0
    }

    private fun index(homeGoals: Int, awayGoals: Int) = homeGoals * size + awayGoals

    fun probability(homeGoals: Int, awayGoals: Int): Double {
        if (homeGoals !in 0..maxGoals || awayGoals !in 0..maxGoals) return 0.0
        return cells[index(homeGoals, awayGoals)]
    }

    fun totalMass(): Double = cells.sum()

    fun homeWin(): Double {
        var sum = 0.0
        for (h in 0..maxGoals) for (a in 0 until h) sum += cells[index(h, a)]
        return sum
    }

    fun draw(): Double {
        var sum = 0.0
        for (h in 0..maxGoals) sum += cells[index(h, h)]
        return sum
    }

    fun awayWin(): Double {
        var sum = 0.0
        for (h in 0..maxGoals) for (a in (h + 1)..maxGoals) sum += cells[index(h, a)]
        return sum
    }

    /** Probability that the number of goals exceeds [line] (0.5, 1.5, 2.5, ...). */
    fun over(line: Double): Double {
        var under = 0.0
        for (h in 0..maxGoals) for (a in 0..maxGoals) {
            if (h + a <= line) under += cells[index(h, a)]
        }
        return (1.0 - under).coerceIn(0.0, 1.0)
    }

    fun bttsYes(): Double {
        var sum = 0.0
        for (h in 1..maxGoals) for (a in 1..maxGoals) sum += cells[index(h, a)]
        return sum
    }

    /** All cells sorted by probability in descending order. */
    fun rankedScores(): List<ScoreProbability> {
        val list = ArrayList<ScoreProbability>(cells.size)
        for (h in 0..maxGoals) {
            for (a in 0..maxGoals) {
                list.add(ScoreProbability(h, a, cells[index(h, a)]))
            }
        }
        list.sortByDescending { it.probability }
        return list
    }

    /** Frequency distribution of total goals in the match. */
    fun totalGoalsDistribution(): Map<Int, Double> {
        val map = LinkedHashMap<Int, Double>()
        for (h in 0..maxGoals) for (a in 0..maxGoals) {
            val key = h + a
            map[key] = (map[key] ?: 0.0) + cells[index(h, a)]
        }
        return map
    }

    companion object {
        const val DEFAULT_MAX_GOALS = 10
    }
}
