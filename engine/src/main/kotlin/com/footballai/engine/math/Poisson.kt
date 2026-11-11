package com.footballai.engine.math

import kotlin.math.exp
import kotlin.math.ln

/**
 * Poisson helpers used by the goal models.
 *
 * All computations are performed in log space where it matters so that large
 * expected-goal values (up to the engine's clamp of 6.0) never overflow or
 * underflow into meaningless results.
 */
object Poisson {

    /** Factorials are cheap to cache for the small support used by football models. */
    private val logFactorials: DoubleArray = DoubleArray(64).also { cache ->
        cache[0] = 0.0
        for (i in 1 until cache.size) {
            cache[i] = cache[i - 1] + ln(i.toDouble())
        }
    }

    fun logFactorial(n: Int): Double {
        require(n >= 0) { "factorial is undefined for negative numbers" }
        if (n < logFactorials.size) return logFactorials[n]
        var sum = logFactorials.last()
        for (i in logFactorials.size..n) sum += ln(i.toDouble())
        return sum
    }

    /** log P(X = k) for X ~ Poisson(lambda). Returns -inf for impossible events. */
    fun logPmf(goals: Int, lambda: Double): Double {
        if (goals < 0 || !lambda.isFinite() || lambda < 0.0) return Double.NEGATIVE_INFINITY
        if (lambda == 0.0) return if (goals == 0) 0.0 else Double.NEGATIVE_INFINITY
        return -lambda + goals * ln(lambda) - logFactorial(goals)
    }

    /** P(X = k) for X ~ Poisson(lambda). */
    fun pmf(goals: Int, lambda: Double): Double {
        val log = logPmf(goals, lambda)
        return if (log == Double.NEGATIVE_INFINITY) 0.0 else exp(log)
    }

    /** P(X <= k). */
    fun cdf(goals: Int, lambda: Double): Double {
        if (goals < 0) return 0.0
        var sum = 0.0
        for (i in 0..goals) sum += pmf(i, lambda)
        return sum.coerceIn(0.0, 1.0)
    }

    /** Probability mass for 0..[maxGoals]. */
    fun distribution(lambda: Double, maxGoals: Int = 10): DoubleArray {
        require(maxGoals >= 0) { "maxGoals must be non-negative" }
        val result = DoubleArray(maxGoals + 1)
        for (i in 0..maxGoals) result[i] = pmf(i, lambda)
        return result
    }

    /**
     * Probability that the total of two independent Poisson variables exceeds [line].
     * The line is the standard football convention (0.5, 1.5, 2.5, ...).
     */
    fun probabilityOver(homeLambda: Double, awayLambda: Double, line: Double, maxGoals: Int = 12): Double {
        var under = 0.0
        for (h in 0..maxGoals) {
            val pHome = pmf(h, homeLambda)
            if (pHome == 0.0) continue
            for (a in 0..maxGoals) {
                if (h + a <= line) {
                    under += pHome * pmf(a, awayLambda)
                }
            }
        }
        return (1.0 - under).coerceIn(0.0, 1.0)
    }
}
