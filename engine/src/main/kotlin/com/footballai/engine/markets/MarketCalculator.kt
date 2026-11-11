package com.footballai.engine.markets

import com.footballai.engine.model.DoubleChance
import com.footballai.engine.model.FirstHalfProbabilities
import com.footballai.engine.model.MarketProbabilities
import com.footballai.engine.model.ScoreProbability
import com.footballai.engine.poisson.ScoreMatrix

/**
 * Derives every supported betting-style market from a score matrix.
 *
 * Nothing here is hardcoded: all probabilities are functions of the matrix that
 * the goal model produced. Mutually exclusive outcomes are guaranteed to sum to
 * 1 (within rounding) because the matrix itself is normalised.
 */
object MarketCalculator {

    /** Standard European goal lines supported by the UI. */
    val GOAL_LINES: List<Double> = listOf(0.5, 1.5, 2.5, 3.5, 4.5)

    /**
     * Empirically, roughly 44% of goals are scored before half time. The value is
     * applied to both teams' expected goals to derive first-half markets and is
     * documented as an assumption rather than a per-match estimate.
     */
    const val DEFAULT_FIRST_HALF_SHARE = 0.44

    fun fromMatrix(
        matrix: ScoreMatrix,
        firstHalfShare: Double = DEFAULT_FIRST_HALF_SHARE,
        topScoreCount: Int = 8,
    ): MarketProbabilities = fromMatrixWithOffsets(
        matrix = matrix,
        baseHomeGoals = 0,
        baseAwayGoals = 0,
        firstHalfShare = firstHalfShare,
        topScoreCount = topScoreCount,
    )

    /**
     * Same as [fromMatrix] but accounts for goals that were already scored, which
     * is what live predictions need: the matrix then describes *remaining* goals.
     */
    fun fromMatrixWithOffsets(
        matrix: ScoreMatrix,
        baseHomeGoals: Int,
        baseAwayGoals: Int,
        firstHalfShare: Double? = null,
        topScoreCount: Int = 8,
    ): MarketProbabilities {
        require(baseHomeGoals >= 0 && baseAwayGoals >= 0) { "Base scores must be non-negative" }

        val maxGoals = matrix.maxGoals
        var homeWin = 0.0
        var draw = 0.0
        var awayWin = 0.0
        var btts = 0.0
        val over = LinkedHashMap<Double, Double>()
        val under = LinkedHashMap<Double, Double>()
        val lineUnder = LinkedHashMap<Double, Double>()
        for (line in GOAL_LINES) lineUnder[line] = 0.0

        val scores = ArrayList<ScoreProbability>((maxGoals + 1) * (maxGoals + 1))

        for (h in 0..maxGoals) {
            for (a in 0..maxGoals) {
                val p = matrix.probability(h, a)
                if (p <= 0.0) continue
                val finalHome = baseHomeGoals + h
                val finalAway = baseAwayGoals + a
                val total = finalHome + finalAway
                when {
                    finalHome > finalAway -> homeWin += p
                    finalHome == finalAway -> draw += p
                    else -> awayWin += p
                }
                if (finalHome > 0 && finalAway > 0) btts += p
                for (line in GOAL_LINES) {
                    if (total <= line) lineUnder[line] = (lineUnder[line] ?: 0.0) + p
                }
                scores.add(ScoreProbability(finalHome, finalAway, p))
            }
        }

        for (line in GOAL_LINES) {
            val pUnder = (lineUnder[line] ?: 0.0).coerceIn(0.0, 1.0)
            under[line] = pUnder
            over[line] = (1.0 - pUnder).coerceIn(0.0, 1.0)
        }

        scores.sortByDescending { it.probability }

        val firstHalf = firstHalfShare?.let { share ->
            val safeShare = share.coerceIn(0.3, 0.6)
            firstHalfProbabilities(
                homeLambda = matrix.homeLambda * safeShare,
                awayLambda = matrix.awayLambda * safeShare,
            )
        }

        return MarketProbabilities(
            homeWin = homeWin.coerceIn(0.0, 1.0),
            draw = draw.coerceIn(0.0, 1.0),
            awayWin = awayWin.coerceIn(0.0, 1.0),
            over = over,
            under = under,
            bttsYes = btts.coerceIn(0.0, 1.0),
            bttsNo = (1.0 - btts).coerceIn(0.0, 1.0),
            doubleChance = mapOf(
                DoubleChance.HOME_OR_DRAW to (homeWin + draw).coerceIn(0.0, 1.0),
                DoubleChance.HOME_OR_AWAY to (homeWin + awayWin).coerceIn(0.0, 1.0),
                DoubleChance.DRAW_OR_AWAY to (draw + awayWin).coerceIn(0.0, 1.0),
            ),
            topScores = scores.take(topScoreCount),
            exactScoreMatrix = scores,
            firstHalf = firstHalf,
        )
    }

    private fun firstHalfProbabilities(homeLambda: Double, awayLambda: Double): FirstHalfProbabilities {
        val matrix = ScoreMatrix(homeLambda = homeLambda, awayLambda = awayLambda)
        return FirstHalfProbabilities(
            homeWin = matrix.homeWin(),
            draw = matrix.draw(),
            awayWin = matrix.awayWin(),
            over05 = matrix.over(0.5),
            over15 = matrix.over(1.5),
            bttsYes = matrix.bttsYes(),
        )
    }

    /**
     * Removes the bookmaker margin from a set of decimal odds and returns the
     * implied probabilities. Returns null when the odds are not usable.
     */
    fun impliedProbabilities(oddsHome: Double?, oddsDraw: Double?, oddsAway: Double?): Triple<Double, Double, Double>? {
        if (oddsHome == null || oddsDraw == null || oddsAway == null) return null
        if (oddsHome <= 1.0 || oddsDraw <= 1.0 || oddsAway <= 1.0) return null
        val invHome = 1.0 / oddsHome
        val invDraw = 1.0 / oddsDraw
        val invAway = 1.0 / oddsAway
        val total = invHome + invDraw + invAway
        if (total <= 0.0) return null
        return Triple(invHome / total, invDraw / total, invAway / total)
    }
}
