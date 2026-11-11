package com.footballai.engine.prediction

import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.Explanation
import com.footballai.engine.model.ExplanationCode
import com.footballai.engine.model.LiveContext
import com.footballai.engine.model.LiveInput
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.ModelDescriptor
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.PredictionResult
import com.footballai.engine.model.QualityNote
import com.footballai.engine.model.QualityNoteCode
import com.footballai.engine.model.ScoreProbability
import com.footballai.engine.poisson.ScoreMatrix
import kotlin.math.abs
import kotlin.math.min

data class EngineConfig(
    val maxGoals: Int = 10,
    /** How strongly the Elo difference tilts the expected goals (0 disables it). */
    val eloBlendWeight: Double = 0.15,
    val minLambda: Double = 0.08,
    val maxLambda: Double = 6.0,
    val firstHalfShare: Double = 0.44,
    val matchLengthMinutes: Int = 90,
    /** Scoring multiplier for a team that received a red card. */
    val redCardOwnFactor: Double = 0.88,
    /** Scoring multiplier for the opponent of a team that received a red card. */
    val redCardOpponentFactor: Double = 1.12,
    val liveIntensityMinMinute: Int = 25,
    val liveIntensityMaxDeviation: Double = 0.20,
    val averageShotsPerTeam: Double = 11.5,
    val averageShotsOnTargetPerTeam: Double = 4.1,
    val maxExtraGoals: Int = 8,
)

/**
 * Entry point of the statistical engine.
 *
 * The engine is a pure function of its inputs: it has no clock, no network and no
 * database access. The caller supplies the training-through timestamp, which makes
 * back-testing and live analysis use exactly the same code as production.
 */
class MatchPredictionEngine(private val config: EngineConfig = EngineConfig()) {

    fun expectedGoals(input: PreMatchInput): Pair<Double, Double> {
        val baseline = input.baseline
        var lambdaHome = baseline.averageHomeGoals * input.home.attack * input.away.defence
        var lambdaAway = baseline.averageAwayGoals * input.away.attack * input.home.defence

        // Elo tilt: symmetric so that the total number of expected goals is preserved.
        val eloDiff = input.home.elo - input.away.elo
        val tilt = (eloDiff / 400.0 * input.eloBlendWeight).coerceIn(-0.25, 0.25)
        if (tilt != 0.0) {
            lambdaHome *= (1.0 + tilt)
            lambdaAway *= (1.0 - tilt)
        }

        // Contextual adjustments (injuries, form, congestion, ...). Each one is bounded.
        for (adjustment in input.adjustments) {
            lambdaHome *= adjustment.homeFactor.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
            lambdaAway *= adjustment.awayFactor.coerceIn(ADJUSTMENT_MIN, ADJUSTMENT_MAX)
        }

        return Pair(
            lambdaHome.coerceIn(config.minLambda, config.maxLambda),
            lambdaAway.coerceIn(config.minLambda, config.maxLambda),
        )
    }

    fun predictPreMatch(input: PreMatchInput): PredictionResult {
        val (lambdaHome, lambdaAway) = expectedGoals(input)
        val rho = input.dixonColesRho ?: 0.0
        val matrix = ScoreMatrix(
            homeLambda = lambdaHome,
            awayLambda = lambdaAway,
            rho = rho,
            maxGoals = config.maxGoals,
        )
        val markets = MarketCalculator.fromMatrix(
            matrix = matrix,
            firstHalfShare = config.firstHalfShare,
            topScoreCount = 8,
        )

        val model = ModelDescriptor(
            name = if (rho != 0.0) "Dixon-Coles" else "Independent Poisson",
            version = input.modelVersion,
            dixonColesEnabled = rho != 0.0,
            rho = rho,
            fittedMatches = input.fittedMatches,
            eloBlendWeight = input.eloBlendWeight,
        )

        val explanations = buildPreMatchExplanations(input, markets.homeWin, markets.draw, markets.awayWin)

        return PredictionResult(
            expectedHomeGoals = lambdaHome,
            expectedAwayGoals = lambdaAway,
            markets = markets,
            mostLikelyScores = markets.topScores.take(5),
            model = model,
            explanations = explanations,
            quality = input.quality,
            asOfEpochMs = input.asOfEpochMs,
            live = null,
        )
    }

    fun predictLive(input: LiveInput): PredictionResult {
        val preMatch = input.preMatch
        val (fullHome, fullAway) = expectedGoals(preMatch)

        val totalMinutes = (config.matchLengthMinutes + input.addedTimeMinutes).coerceAtLeast(1)
        val minutesPlayed = input.minute.coerceIn(0, totalMinutes)
        val remainingMinutes = totalMinutes - minutesPlayed
        val remainingShare = (remainingMinutes.toDouble() / config.matchLengthMinutes)
            .coerceIn(0.0, 1.0)

        var lambdaHome = fullHome * remainingShare
        var lambdaAway = fullAway * remainingShare

        val appliedAdjustments = mutableListOf<String>()

        // Red cards: the affected team scores less, its opponent scores more.
        if (input.homeRedCards > 0) {
            val own = Math.pow(config.redCardOwnFactor, input.homeRedCards.toDouble())
            val opp = Math.pow(config.redCardOpponentFactor, input.homeRedCards.toDouble())
            lambdaHome *= own
            lambdaAway *= opp
            appliedAdjustments.add("red_card_home")
        }
        if (input.awayRedCards > 0) {
            val own = Math.pow(config.redCardOwnFactor, input.awayRedCards.toDouble())
            val opp = Math.pow(config.redCardOpponentFactor, input.awayRedCards.toDouble())
            lambdaAway *= own
            lambdaHome *= opp
            appliedAdjustments.add("red_card_away")
        }

        // Observed tempo: only applied when enough of the match has been played and the
        // provider supplied shot data. The adjustment is deliberately bounded.
        var intensityApplied = false
        if (minutesPlayed >= config.liveIntensityMinMinute) {
            val intensityHome = shotIntensity(
                observed = input.homeShotsOnTarget ?: input.homeShots,
                expectedTotal = if (input.homeShotsOnTarget != null) config.averageShotsOnTargetPerTeam else config.averageShotsPerTeam,
                minutesPlayed = minutesPlayed,
            )
            val intensityAway = shotIntensity(
                observed = input.awayShotsOnTarget ?: input.awayShots,
                expectedTotal = if (input.awayShotsOnTarget != null) config.averageShotsOnTargetPerTeam else config.averageShotsPerTeam,
                minutesPlayed = minutesPlayed,
            )
            if (intensityHome != null || intensityAway != null) {
                val cap = config.liveIntensityMaxDeviation
                val factorHome = 1.0 + (intensityHome ?: 0.0).coerceIn(-cap, cap)
                val factorAway = 1.0 + (intensityAway ?: 0.0).coerceIn(-cap, cap)
                lambdaHome *= factorHome
                lambdaAway *= factorAway
                intensityApplied = true
                appliedAdjustments.add("shot_intensity")
            }
        }

        lambdaHome = lambdaHome.coerceIn(0.0, config.maxLambda)
        lambdaAway = lambdaAway.coerceIn(0.0, config.maxLambda)

        val remainingMatrix = ScoreMatrix(
            homeLambda = lambdaHome,
            awayLambda = lambdaAway,
            rho = 0.0,
            maxGoals = config.maxExtraGoals,
        )
        val markets = MarketCalculator.fromMatrixWithOffsets(
            matrix = remainingMatrix,
            baseHomeGoals = input.homeGoals,
            baseAwayGoals = input.awayGoals,
            firstHalfShare = null,
            topScoreCount = 8,
        )

        val noMoreGoals = remainingMatrix.probability(0, 0)
        val anyGoal = (1.0 - noMoreGoals).coerceIn(0.0, 1.0)
        val lambdaSum = lambdaHome + lambdaAway
        val nextGoalHome = if (lambdaSum > 0.0) anyGoal * lambdaHome / lambdaSum else 0.0
        val nextGoalAway = if (lambdaSum > 0.0) anyGoal * lambdaAway / lambdaSum else 0.0

        val remainingOver = LinkedHashMap<Double, Double>()
        for (line in listOf(0.5, 1.5, 2.5)) {
            remainingOver[line] = remainingMatrix.over(line)
        }

        val extraNotes = mutableListOf(QualityNote(QualityNoteCode.LIVE_ADJUSTMENT_HEURISTIC))
        if (!intensityApplied) extraNotes.add(QualityNote(QualityNoteCode.LIVE_STATS_PARTIAL))
        val quality = preMatch.quality.copy(notes = preMatch.quality.notes + extraNotes)

        val model = ModelDescriptor(
            name = "In-play Poisson (time remaining)",
            version = preMatch.modelVersion,
            dixonColesEnabled = false,
            rho = 0.0,
            fittedMatches = preMatch.fittedMatches,
            eloBlendWeight = preMatch.eloBlendWeight,
        )

        val explanations = buildLiveExplanations(
            input = input,
            remainingMinutes = remainingMinutes,
            homeWin = markets.homeWin,
            awayWin = markets.awayWin,
            appliedAdjustments = appliedAdjustments,
        )

        return PredictionResult(
            expectedHomeGoals = lambdaHome,
            expectedAwayGoals = lambdaAway,
            markets = markets,
            mostLikelyScores = markets.topScores.take(5),
            model = model,
            explanations = explanations,
            quality = quality,
            asOfEpochMs = preMatch.asOfEpochMs,
            live = LiveContext(
                minute = minutesPlayed,
                homeGoals = input.homeGoals,
                awayGoals = input.awayGoals,
                remainingMinutes = remainingMinutes,
                remainingHomeExpectedGoals = lambdaHome,
                remainingAwayExpectedGoals = lambdaAway,
                nextGoalHome = nextGoalHome,
                nextGoalAway = nextGoalAway,
                noMoreGoals = noMoreGoals,
                remainingOver = remainingOver,
                adjustmentsApplied = appliedAdjustments,
            ),
        )
    }

    /** Returns the relative deviation of the observed shot rate from the league average, or null. */
    private fun shotIntensity(observed: Int?, expectedTotal: Double, minutesPlayed: Int): Double? {
        if (observed == null || observed < 0 || expectedTotal <= 0.0) return null
        val expectedSoFar = expectedTotal * (minutesPlayed.toDouble() / config.matchLengthMinutes)
        if (expectedSoFar < 1.0) return null
        return (observed - expectedSoFar) / expectedSoFar
    }

    private fun buildPreMatchExplanations(
        input: PreMatchInput,
        homeWin: Double,
        draw: Double,
        awayWin: Double,
    ): List<Explanation> {
        val list = mutableListOf<Explanation>()

        val edge = homeWin - awayWin
        when {
            edge > 0.08 -> list.add(Explanation(ExplanationCode.FAVOURITE_HOME, (edge * 2).coerceIn(0.0, 1.0)))
            edge < -0.08 -> list.add(Explanation(ExplanationCode.FAVOURITE_AWAY, (-edge * 2).coerceIn(0.0, 1.0)))
            else -> list.add(Explanation(ExplanationCode.EVENLY_MATCHED, 1.0 - abs(edge).coerceIn(0.0, 1.0)))
        }

        if (input.home.attack >= 1.15) {
            list.add(Explanation(ExplanationCode.STRONG_HOME_ATTACK, strengthWeight(input.home.attack), params("attack", input.home.attack)))
        } else if (input.home.attack <= 0.85) {
            list.add(Explanation(ExplanationCode.WEAK_HOME_ATTACK, strengthWeight(1.0 / input.home.attack), params("attack", input.home.attack)))
        }
        if (input.away.attack >= 1.15) {
            list.add(Explanation(ExplanationCode.STRONG_AWAY_ATTACK, strengthWeight(input.away.attack), params("attack", input.away.attack)))
        } else if (input.away.attack <= 0.85) {
            list.add(Explanation(ExplanationCode.WEAK_AWAY_ATTACK, strengthWeight(1.0 / input.away.attack), params("attack", input.away.attack)))
        }
        if (input.home.defence <= 0.88) {
            list.add(Explanation(ExplanationCode.SOLID_HOME_DEFENCE, strengthWeight(1.0 / input.home.defence), params("defence", input.home.defence)))
        } else if (input.home.defence >= 1.12) {
            list.add(Explanation(ExplanationCode.LEAKY_HOME_DEFENCE, strengthWeight(input.home.defence), params("defence", input.home.defence)))
        }
        if (input.away.defence <= 0.88) {
            list.add(Explanation(ExplanationCode.SOLID_AWAY_DEFENCE, strengthWeight(1.0 / input.away.defence), params("defence", input.away.defence)))
        } else if (input.away.defence >= 1.12) {
            list.add(Explanation(ExplanationCode.LEAKY_AWAY_DEFENCE, strengthWeight(input.away.defence), params("defence", input.away.defence)))
        }

        val total = input.baseline.averageTotalGoals
        when {
            total >= 2.9 -> list.add(Explanation(ExplanationCode.HIGH_SCORING_LEAGUE, 0.4, params("goals", total)))
            total <= 2.25 -> list.add(Explanation(ExplanationCode.LOW_SCORING_LEAGUE, 0.4, params("goals", total)))
        }

        if (input.baseline.averageHomeGoals > input.baseline.averageAwayGoals * 1.08) {
            list.add(Explanation(ExplanationCode.HOME_ADVANTAGE, 0.35))
        }

        val eloDiff = input.home.elo - input.away.elo
        if (eloDiff >= 80.0) {
            list.add(Explanation(ExplanationCode.ELO_EDGE_HOME, (eloDiff / 400.0).coerceIn(0.0, 1.0), params("elo", eloDiff)))
        } else if (eloDiff <= -80.0) {
            list.add(Explanation(ExplanationCode.ELO_EDGE_AWAY, (-eloDiff / 400.0).coerceIn(0.0, 1.0), params("elo", -eloDiff)))
        }

        for (adjustment in input.adjustments) {
            val magnitude = maxOf(abs(adjustment.homeFactor - 1.0), abs(adjustment.awayFactor - 1.0))
            list.add(Explanation(adjustment.reason, (magnitude * 4.0).coerceIn(0.05, 0.45)))
        }

        if (input.dixonColesRho != null && input.dixonColesRho != 0.0) {
            list.add(Explanation(ExplanationCode.DIXON_COLES_LOW_SCORE_ADJUSTMENT, 0.3, params("rho", input.dixonColesRho)))
        }

        when {
            input.fittedMatches >= 300 -> list.add(Explanation(ExplanationCode.LARGE_SAMPLE, 0.25, params("matches", input.fittedMatches)))
            input.fittedMatches < 100 -> list.add(Explanation(ExplanationCode.LIMITED_HISTORY, 0.5, params("matches", input.fittedMatches)))
        }

        list.add(Explanation(ExplanationCode.NO_MARKET_EDGE_CLAIMED, 0.05))

        return list.sortedByDescending { it.weight }
    }

    private fun buildLiveExplanations(
        input: LiveInput,
        remainingMinutes: Int,
        homeWin: Double,
        awayWin: Double,
        appliedAdjustments: List<String>,
    ): List<Explanation> {
        val list = mutableListOf<Explanation>()
        val remainingShare = remainingMinutes / config.matchLengthMinutes.toDouble()
        list.add(
            Explanation(
                ExplanationCode.LIVE_TIME_REMAINING,
                1.0 - remainingShare.coerceIn(0.0, 1.0),
                params("minutes", remainingMinutes),
            ),
        )
        val scoreDiff = input.homeGoals - input.awayGoals
        if (scoreDiff != 0) {
            list.add(
                Explanation(
                    ExplanationCode.LIVE_CURRENT_SCORE,
                    min(1.0, abs(scoreDiff) / 2.0),
                    mapOf("home" to input.homeGoals.toString(), "away" to input.awayGoals.toString()),
                ),
            )
        }
        if (input.homeRedCards > 0) {
            list.add(Explanation(ExplanationCode.LIVE_RED_CARD_HOME, 0.9, params("cards", input.homeRedCards)))
        }
        if (input.awayRedCards > 0) {
            list.add(Explanation(ExplanationCode.LIVE_RED_CARD_AWAY, 0.9, params("cards", input.awayRedCards)))
        }
        if (appliedAdjustments.contains("shot_intensity")) {
            list.add(Explanation(ExplanationCode.LIVE_INTENSITY_ADJUSTMENT, 0.4))
        }
        val edge = homeWin - awayWin
        if (edge > 0.1) {
            list.add(Explanation(ExplanationCode.FAVOURITE_HOME, (edge * 2).coerceIn(0.0, 1.0)))
        } else if (edge < -0.1) {
            list.add(Explanation(ExplanationCode.FAVOURITE_AWAY, (-edge * 2).coerceIn(0.0, 1.0)))
        }
        list.add(Explanation(ExplanationCode.NO_MARKET_EDGE_CLAIMED, 0.05))
        return list.sortedByDescending { it.weight }
    }

    private fun strengthWeight(value: Double): Double = ((value - 1.0) / 0.6).coerceIn(0.1, 1.0)

    private fun params(key: String, value: Double): Map<String, String> =
        mapOf(key to String.format(java.util.Locale.US, "%.2f", value))

    private fun params(key: String, value: Int): Map<String, String> = mapOf(key to value.toString())

    companion object {
        private const val ADJUSTMENT_MIN = 0.5
        private const val ADJUSTMENT_MAX = 2.0
    }
}
