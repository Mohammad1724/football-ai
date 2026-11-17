package com.footballai.engine.ratings

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A finished match in the form the rating models consume. */
data class RatingMatch(
    val homeTeamId: String,
    val awayTeamId: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val kickoffEpochMs: Long,
    /** Sample weight, normally derived from the time decay of the match. */
    val weight: Double = 1.0,
) {
    init {
        require(homeGoals >= 0 && awayGoals >= 0) { "Goals must be non-negative" }
        require(homeTeamId != awayTeamId) { "A team cannot play itself" }
    }
}

data class DixonColesConfig(
    val maxIterations: Int = 400,
    val tolerance: Double = 1e-8,
    val initialLearningRate: Double = 0.10,
    val l2Regularisation: Double = 0.03,
    val minRho: Double = -0.20,
    val maxRho: Double = 0.20,
    val estimateRho: Boolean = true,
    val minMatches: Int = 30,
    /** Numerical floor for the Dixon-Coles tau factor; keeps the likelihood well defined. */
    val tauFloor: Double = 0.05,
    /**
     * How many matches of evidence a team needs before half of its fitted deviation from
     * the league mean is believed.
     *
     * A team with two matches in the sample is fitted from 180 minutes of football; taken
     * at face value, that estimate swings the expected goals of every one of its remaining
     * fixtures. Shrinking it toward the league mean by `n / (n + prior)` is the standard
     * empirical-Bayes fix and cannot invent strength — with a full season of results the
     * factor is above 0.9, so established teams keep what they earned.
     *
     * The fitted likelihood is unchanged; only the reported strengths are tempered, which
     * keeps the back-tester honest because it predicts from exactly these strengths too.
     */
    val shrinkagePriorMatches: Double = 8.0,
    /**
     * Goals above this are treated as this many when fitting.
     *
     * A 9-0 is a real result and its three points are real, but as evidence about a team's
     * scoring rate it is an outlier that a likelihood fit will chase: the winning side's
     * attack jumps and the losing side's defence collapses, and both stay wrong for weeks
     * of predictions. Clamping the count keeps the match, keeps its weight and keeps the
     * result — it only stops one afternoon from outweighing a season.
     */
    val maxGoalsPerTeam: Int = 7,
)

/** Fitted parameters of a Dixon-Coles model for one competition. */
data class DixonColesFit(
    /** Team id -> multiplicative attack strength (1.0 = league average). */
    val attack: Map<String, Double>,
    /** Team id -> multiplicative defence weakness (below 1.0 concedes fewer goals). */
    val defence: Map<String, Double>,
    val homeBase: Double,
    val awayBase: Double,
    val rho: Double,
    val matchesUsed: Int,
    val matchesByTeam: Map<String, Int>,
    val trainedThroughEpochMs: Long,
    /** The penalised objective the optimiser reached, at the unshrunk solution. */
    val logLikelihood: Double,
    val converged: Boolean,
    val iterations: Int,
) {
    val averageHomeGoals: Double get() = homeBase
    val averageAwayGoals: Double get() = awayBase

    fun expectedGoals(homeTeamId: String, awayTeamId: String): Pair<Double, Double> {
        val homeAttack = attack[homeTeamId] ?: 1.0
        val homeDefence = defence[homeTeamId] ?: 1.0
        val awayAttack = attack[awayTeamId] ?: 1.0
        val awayDefence = defence[awayTeamId] ?: 1.0
        return Pair(
            (homeBase * homeAttack * awayDefence).coerceIn(MIN_LAMBDA, MAX_LAMBDA),
            (awayBase * awayAttack * homeDefence).coerceIn(MIN_LAMBDA, MAX_LAMBDA),
        )
    }

    companion object {
        const val MIN_LAMBDA = 0.08
        const val MAX_LAMBDA = 6.0
    }
}

/**
 * Maximum-likelihood estimator for the Dixon-Coles model.
 *
 * The model is
 *
 *   lambda_home = homeBase * attack(home) * defence(away)
 *   lambda_away = awayBase * attack(away) * defence(home)
 *
 * with the low-score dependency factor tau described in Dixon & Coles (1997).
 * Parameters are estimated in log space with a penalised likelihood, using
 * gradient ascent with an adaptive step size. The attack parameters are
 * renormalised to a geometric mean of 1 after every accepted step, which is the
 * standard identifiability constraint.
 */
object DixonColesFitter {

    fun fit(matches: List<RatingMatch>, config: DixonColesConfig = DixonColesConfig()): DixonColesFit? {
        val usable = matches.filter { it.weight > 0.0 && it.homeGoals in 0..15 && it.awayGoals in 0..15 }
        if (usable.size < config.minMatches) return null

        val teamIds = usable.flatMap { listOf(it.homeTeamId, it.awayTeamId) }.distinct().sorted()
        val index = HashMap<String, Int>(teamIds.size * 2)
        teamIds.forEachIndexed { i, id -> index[id] = i }
        val teamCount = teamIds.size

        val homeIndex = IntArray(usable.size)
        val awayIndex = IntArray(usable.size)
        val homeGoals = IntArray(usable.size)
        val awayGoals = IntArray(usable.size)
        val weights = DoubleArray(usable.size)

        var weightSum = 0.0
        var weightedHomeGoals = 0.0
        var weightedAwayGoals = 0.0
        val matchesByTeam = IntArray(teamCount)

        usable.forEachIndexed { i, match ->
            val h = index.getValue(match.homeTeamId)
            val a = index.getValue(match.awayTeamId)
            homeIndex[i] = h
            awayIndex[i] = a
            homeGoals[i] = match.homeGoals.coerceAtMost(config.maxGoalsPerTeam)
            awayGoals[i] = match.awayGoals.coerceAtMost(config.maxGoalsPerTeam)
            weights[i] = match.weight
            weightSum += match.weight
            weightedHomeGoals += match.weight * match.homeGoals
            weightedAwayGoals += match.weight * match.awayGoals
            matchesByTeam[h]++
            matchesByTeam[a]++
        }

        if (weightSum <= 0.0) return null

        val homeBase = max(0.10, weightedHomeGoals / weightSum)
        val awayBase = max(0.10, weightedAwayGoals / weightSum)

        val alpha = DoubleArray(teamCount)
        val beta = DoubleArray(teamCount)
        var rho = 0.0

        var currentObjective = objective(alpha, beta, rho, homeIndex, awayIndex, homeGoals, awayGoals, weights, homeBase, awayBase, config)
        var step = config.initialLearningRate
        var iterations = 0
        var converged = false
        var lastImprovement = Double.MAX_VALUE

        val gradAlpha = DoubleArray(teamCount)
        val gradBeta = DoubleArray(teamCount)

        while (iterations < config.maxIterations && step > 1e-8) {
            iterations++
            java.util.Arrays.fill(gradAlpha, 0.0)
            java.util.Arrays.fill(gradBeta, 0.0)
            var gradRho = 0.0

            for (i in 0 until usable.size) {
                val h = homeIndex[i]
                val a = awayIndex[i]
                val w = weights[i]
                val x = homeGoals[i]
                val y = awayGoals[i]

                val lambdaHome = homeBase * exp(alpha[h] + beta[a])
                val lambdaAway = awayBase * exp(alpha[a] + beta[h])

                var tau = 1.0
                var dTauDLambdaHome = 0.0
                var dTauDLambdaAway = 0.0
                var dTauDRho = 0.0
                when {
                    x == 0 && y == 0 -> {
                        tau = 1.0 - lambdaHome * lambdaAway * rho
                        dTauDLambdaHome = -lambdaAway * rho
                        dTauDLambdaAway = -lambdaHome * rho
                        dTauDRho = -lambdaHome * lambdaAway
                    }
                    x == 0 && y == 1 -> {
                        tau = 1.0 + lambdaHome * rho
                        dTauDLambdaHome = rho
                        dTauDRho = lambdaHome
                    }
                    x == 1 && y == 0 -> {
                        tau = 1.0 + lambdaAway * rho
                        dTauDLambdaAway = rho
                        dTauDRho = lambdaAway
                    }
                    x == 1 && y == 1 -> {
                        tau = 1.0 - rho
                        dTauDRho = -1.0
                    }
                }
                val useTau = tau > config.tauFloor
                val safeTau = if (useTau) tau else 1.0
                val dLogTauDLambdaHome = if (useTau) dTauDLambdaHome / safeTau else 0.0
                val dLogTauDLambdaAway = if (useTau) dTauDLambdaAway / safeTau else 0.0
                val dLogTauDRho = if (useTau) dTauDRho / safeTau else 0.0

                val gradHome = w * ((x - lambdaHome) + lambdaHome * dLogTauDLambdaHome)
                val gradAway = w * ((y - lambdaAway) + lambdaAway * dLogTauDLambdaAway)

                gradAlpha[h] += gradHome
                gradBeta[a] += gradHome
                gradAlpha[a] += gradAway
                gradBeta[h] += gradAway
                gradRho += w * dLogTauDRho
            }

            for (t in 0 until teamCount) {
                gradAlpha[t] -= config.l2Regularisation * alpha[t]
                gradBeta[t] -= config.l2Regularisation * beta[t]
            }
            gradRho -= config.l2Regularisation * rho

            val candidateAlpha = DoubleArray(teamCount) { alpha[it] + step * gradAlpha[it] }
            val candidateBeta = DoubleArray(teamCount) { beta[it] + step * gradBeta[it] }
            val candidateRho = if (config.estimateRho) (rho + step * gradRho).coerceIn(config.minRho, config.maxRho) else 0.0

            // Identifiability: geometric mean of attack parameters fixed at 1.
            renormalise(candidateAlpha)

            val candidateObjective = objective(
                candidateAlpha, candidateBeta, candidateRho,
                homeIndex, awayIndex, homeGoals, awayGoals, weights, homeBase, awayBase, config,
            )

            if (candidateObjective > currentObjective) {
                lastImprovement = candidateObjective - currentObjective
                for (t in 0 until teamCount) {
                    alpha[t] = candidateAlpha[t]
                    beta[t] = candidateBeta[t]
                }
                rho = candidateRho
                currentObjective = candidateObjective
                step *= 1.15
                if (lastImprovement < config.tolerance) {
                    converged = true
                    break
                }
            } else {
                step *= 0.4
            }
        }

        val attackMap = HashMap<String, Double>(teamCount)
        val defenceMap = HashMap<String, Double>(teamCount)
        val countMap = HashMap<String, Int>(teamCount)
        for (t in 0 until teamCount) {
            val seen = matchesByTeam[t]
            attackMap[teamIds[t]] = shrinkTowardsLeagueMean(exp(alpha[t]), seen, config.shrinkagePriorMatches)
            defenceMap[teamIds[t]] = shrinkTowardsLeagueMean(exp(beta[t]), seen, config.shrinkagePriorMatches)
            countMap[teamIds[t]] = seen
        }

        return DixonColesFit(
            attack = attackMap,
            defence = defenceMap,
            homeBase = homeBase,
            awayBase = awayBase,
            rho = rho,
            matchesUsed = usable.size,
            matchesByTeam = countMap,
            trainedThroughEpochMs = usable.maxOf { it.kickoffEpochMs },
            logLikelihood = currentObjective,
            converged = converged,
            iterations = iterations,
        )
    }

    /**
     * Pulls a multiplicative strength toward 1.0 (the league mean) by how little evidence
     * stands behind it: `1 + (value - 1) * n / (n + prior)`.
     */
    internal fun shrinkTowardsLeagueMean(value: Double, matches: Int, priorMatches: Double): Double {
        if (!value.isFinite() || value <= 0.0) return 1.0
        if (priorMatches <= 0.0) return value
        val seen = matches.coerceAtLeast(0)
        val weight = seen / (seen + priorMatches)
        return 1.0 + (value - 1.0) * weight
    }

    private fun renormalise(alpha: DoubleArray) {
        if (alpha.isEmpty()) return
        var sum = 0.0
        for (v in alpha) sum += v
        val mean = sum / alpha.size
        for (i in alpha.indices) alpha[i] -= mean
    }

    private fun objective(
        alpha: DoubleArray,
        beta: DoubleArray,
        rho: Double,
        homeIndex: IntArray,
        awayIndex: IntArray,
        homeGoals: IntArray,
        awayGoals: IntArray,
        weights: DoubleArray,
        homeBase: Double,
        awayBase: Double,
        config: DixonColesConfig,
    ): Double {
        var total = 0.0
        for (i in homeGoals.indices) {
            val h = homeIndex[i]
            val a = awayIndex[i]
            val x = homeGoals[i]
            val y = awayGoals[i]
            val lambdaHome = homeBase * exp(alpha[h] + beta[a])
            val lambdaAway = awayBase * exp(alpha[a] + beta[h])
            val tau = tau(x, y, lambdaHome, lambdaAway, rho)
            val safeTau = if (tau > config.tauFloor) tau else 1.0
            val logTau = if (tau > config.tauFloor) ln(safeTau) else 0.0
            total += weights[i] * (logTau + x * ln(lambdaHome) - lambdaHome + y * ln(lambdaAway) - lambdaAway)
        }
        var penalty = 0.0
        for (v in alpha) penalty += v * v
        for (v in beta) penalty += v * v
        penalty += rho * rho
        return total - 0.5 * config.l2Regularisation * penalty
    }

    private fun tau(homeGoals: Int, awayGoals: Int, lambdaHome: Double, lambdaAway: Double, rho: Double): Double =
        when {
            homeGoals == 0 && awayGoals == 0 -> 1.0 - lambdaHome * lambdaAway * rho
            homeGoals == 0 && awayGoals == 1 -> 1.0 + lambdaHome * rho
            homeGoals == 1 && awayGoals == 0 -> 1.0 + lambdaAway * rho
            homeGoals == 1 && awayGoals == 1 -> 1.0 - rho
            else -> 1.0
        }

    /**
     * Log likelihood this parameter set implies for [matches] — evaluated at the strengths
     * the fit *reports*, which are shrunk toward the league mean and clamped.
     *
     * It is therefore not the number the optimiser maximised, and it will not reproduce
     * [DixonColesFit.logLikelihood]. That is expected: the fit's own figure is the
     * penalised objective at the unshrunk solution. The name says which one this is so a
     * diagnostic that disagrees with the fit is read as a measurement of the reported
     * strengths rather than as a bug in the arithmetic.
     */
    fun logLikelihoodAtReportedStrengths(matches: List<RatingMatch>, fit: DixonColesFit): Double {
        var total = 0.0
        for (match in matches) {
            val (lambdaHome, lambdaAway) = fit.expectedGoals(match.homeTeamId, match.awayTeamId)
            val tau = tau(match.homeGoals, match.awayGoals, lambdaHome, lambdaAway, fit.rho)
            val safeTau = if (tau > 1e-6) tau else 1e-6
            val x = match.homeGoals
            val y = match.awayGoals
            total += match.weight * (
                ln(safeTau) + x * ln(lambdaHome) - lambdaHome + y * ln(lambdaAway) - lambdaAway
                )
        }
        return total
    }

    /** Clamps a probability into a display-safe range; used by the presentation layer. */
    fun clampProbability(value: Double): Double = min(1.0, max(0.0, value))
}
