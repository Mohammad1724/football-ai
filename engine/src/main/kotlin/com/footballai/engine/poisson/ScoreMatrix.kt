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
class ScoreMatrix private constructor(
    val homeLambda: Double,
    val awayLambda: Double,
    val rho: Double,
    val maxGoals: Int,
    private val cells: DoubleArray,
) {
    private val size = maxGoals + 1

    constructor(
        homeLambda: Double,
        awayLambda: Double,
        rho: Double = 0.0,
        maxGoals: Int = DEFAULT_MAX_GOALS,
    ) : this(homeLambda, awayLambda, rho, maxGoals, build(homeLambda, awayLambda, rho, maxGoals))

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

    /**
     * Reweights the matrix so that the three 1X2 outcomes carry exactly the
     * requested masses while the *shape* inside each outcome region is preserved.
     *
     * This is how a calibrated 1X2 distribution is propagated to every other market:
     * over/under, BTTS and exact-score probabilities stay mutually consistent with
     * the calibrated result instead of being silently inconsistent with it.
     *
     * The input triple is normalised internally, so a caller that passes 1/3, 1/3,
     * 1/3 always receives a uniform outcome split. Negative or non-finite inputs and
     * degenerate matrices leave the matrix unchanged.
     */
    fun rescaledToOutcomes(homeWin: Double, draw: Double, awayWin: Double): ScoreMatrix {
        if (!homeWin.isFinite() || !draw.isFinite() || !awayWin.isFinite()) return this
        if (homeWin < 0.0 || draw < 0.0 || awayWin < 0.0) return this
        val total = homeWin + draw + awayWin
        if (total <= 0.0) return this
        val targetHome = homeWin / total
        val targetDraw = draw / total
        val targetAway = awayWin / total

        val currentHome = homeWin()
        val currentDraw = draw()
        val currentAway = awayWin()
        if (currentHome <= MIN_REGION_MASS || currentDraw <= MIN_REGION_MASS || currentAway <= MIN_REGION_MASS) {
            return this
        }
        val scaleHome = targetHome / currentHome
        val scaleDraw = targetDraw / currentDraw
        val scaleAway = targetAway / currentAway

        val scaled = DoubleArray(cells.size)
        var sum = 0.0
        for (h in 0..maxGoals) {
            for (a in 0..maxGoals) {
                val factor = when {
                    h > a -> scaleHome
                    h == a -> scaleDraw
                    else -> scaleAway
                }
                val value = cells[index(h, a)] * factor
                scaled[index(h, a)] = value
                sum += value
            }
        }
        if (sum <= 0.0) return this
        for (i in scaled.indices) scaled[i] /= sum
        return ScoreMatrix(homeLambda, awayLambda, rho, maxGoals, scaled)
    }

    companion object {
        const val DEFAULT_MAX_GOALS = 10

        /** Region masses below this are treated as degenerate and never rescaled. */
        private const val MIN_REGION_MASS = 1e-9

        private fun build(homeLambda: Double, awayLambda: Double, rho: Double, maxGoals: Int): DoubleArray {
            require(homeLambda >= 0.0 && homeLambda.isFinite()) { "homeLambda must be non-negative and finite" }
            require(awayLambda >= 0.0 && awayLambda.isFinite()) { "awayLambda must be non-negative and finite" }
            require(maxGoals in 2..20) { "maxGoals must be within 2..20" }
            require(rho.isFinite()) { "rho must be finite" }

            val size = maxGoals + 1
            val cells = DoubleArray(size * size)
            var sum = 0.0
            for (h in 0..maxGoals) {
                val pHome = Poisson.pmf(h, homeLambda)
                for (a in 0..maxGoals) {
                    val factor = when {
                        rho == 0.0 -> 1.0
                        h == 0 && a == 0 -> 1.0 - homeLambda * awayLambda * rho
                        h == 0 && a == 1 -> 1.0 + homeLambda * rho
                        h == 1 && a == 0 -> 1.0 + awayLambda * rho
                        h == 1 && a == 1 -> 1.0 - rho
                        else -> 1.0
                    }
                    val tau = if (factor > 0.0) factor else 1.0
                    val value = pHome * Poisson.pmf(a, awayLambda) * tau
                    cells[h * size + a] = value
                    sum += value
                }
            }
            if (sum <= 0.0) {
                // Degenerate input: fall back to a single deterministic scoreline.
                cells.fill(0.0)
                cells[0] = 1.0
            } else {
                for (i in cells.indices) cells[i] /= sum
            }
            return cells
        }
    }
}
