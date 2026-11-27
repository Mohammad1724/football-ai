package com.footballai.engine.prediction

import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.confidence.ConfidenceAssessor
import com.footballai.engine.confidence.ConfidenceInput
import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.CalibrationInfo
import com.footballai.engine.model.Explanation
import com.footballai.engine.model.ExplanationCode
import com.footballai.engine.model.LiveContext
import com.footballai.engine.model.LiveInput
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.ModelDescriptor
import com.footballai.engine.model.OutcomeTriple
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.PredictionResult
import com.footballai.engine.model.QualityNote
import com.footballai.engine.model.QualityNoteCode
import com.footballai.engine.model.ScoreProbability
import com.footballai.engine.poisson.ScoreMatrix
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

data class EngineConfig(
    val maxGoals: Int = 10,
    /** How strongly the Elo difference tilts the expected goals (0 disables it). */
    val eloBlendWeight: Double = 0.15,
    val minLambda: Double = 0.08,
    val maxLambda: Double = 6.0,
    val firstHalfShare: Double = 0.44,
    val matchLengthMinutes: Int = 90,
    /**
     * Minutes played after the clock reaches [matchLengthMinutes], used when the caller
     * cannot supply the real figure.
     *
     * Without it the model treated minute 90 as the end of the match and minute 89 as
     * sixty seconds of football, which is where forecasts get their most confident: at
     * 89 the probability of no further goal came out at 0.97 for a match that still had
     * four or five minutes to play. This is a disclosed assumption, in the same class as
     * [firstHalfShare], not data read from a provider.
     */
    val stoppageTimeAllowanceMinutes: Int = 4,
    /**
     * Extra-time minutes added to the live horizon of a knockout tie.
     *
     * A league match ends at regulation time; a knockout tie that is level (or a
     * second leg) runs for thirty more minutes before penalties. Penalties are
     * not modelled — once extra time is over there are no more goals to expect.
     */
    val extraTimeMinutes: Int = 30,
    /** Scoring multiplier for a team that received a red card. */
    val redCardOwnFactor: Double = 0.88,
    /** Scoring multiplier for the opponent of a team that received a red card. */
    val redCardOpponentFactor: Double = 1.12,
    val liveIntensityMinMinute: Int = 25,
    val liveIntensityMaxDeviation: Double = 0.20,
    val averageShotsPerTeam: Double = 11.5,
    val averageShotsOnTargetPerTeam: Double = 4.1,
    val maxExtraGoals: Int = 8,
    /**
     * Elo weight used when the Dixon-Coles fit has no usable evidence for these teams.
     *
     * Elo is replayed over every competition the app has ever seen, so it knows about a
     * promoted team from its previous division while the league fit only knows the two
     * matches it has played here. When the fitted strengths are thin, the tilt therefore
     * leans on Elo instead of falling back to "both teams are average".
     */
    val maxEloBlendWeight: Double = 0.40,
    /** Matches per team at which the fitted strengths are trusted at full base weight. */
    val eloReliabilityMatches: Int = 20,
    /**
     * How much of the market's 1X2 probability is mixed in when odds exist.
     *
     * A prior, not a fitted value: closing prices beat every model built on results alone,
     * but this engine has its own calibration and a hard evidence gate, so it is a
     * disagreement to be resolved, not a surrender. The back-tester reports both arms.
     */
    val marketBlendWeight: Double = 0.35,
    /** The weight used when the model's own evidence is thin: the market knows more. */
    val marketBlendWeightThinEvidence: Double = 0.60,
)

/**
 * Entry point of the statistical engine.
 *
 * The engine is a pure function of its inputs: it has no clock, no network and no
 * database access. The caller supplies the training-through timestamp, which makes
 * back-testing and live analysis use exactly the same code as production.
 */
class MatchPredictionEngine(private val config: EngineConfig = EngineConfig()) {

    /**
     * How far the Elo tilt may reach for this fixture: the base weight when both teams have
     * a long enough record, rising toward [EngineConfig.maxEloBlendWeight] as it thins out.
     */
    fun effectiveEloBlendWeight(input: PreMatchInput): Double {
        val base = input.eloBlendWeight.coerceAtLeast(0.0)
        val ceiling = config.maxEloBlendWeight
        if (ceiling <= base) return base
        val sample = minOf(input.home.matchesUsed, input.away.matchesUsed).coerceAtLeast(0)
        val saturation = config.eloReliabilityMatches.coerceAtLeast(1)
        val reliability = (sample.toDouble() / saturation).coerceIn(0.0, 1.0)
        return base + (ceiling - base) * (1.0 - reliability)
    }

    /** How much of the market to mix in for this fixture, 0 when there are no prices. */
    fun marketAlignmentWeight(input: PreMatchInput): Double {
        if (input.market?.isValid != true) return 0.0
        val base = config.marketBlendWeight
        val ceiling = config.marketBlendWeightThinEvidence
        if (ceiling <= base) return base
        val sample = minOf(input.home.matchesUsed, input.away.matchesUsed).coerceAtLeast(0)
        val saturation = config.eloReliabilityMatches.coerceAtLeast(1)
        val reliability = (sample.toDouble() / saturation).coerceIn(0.0, 1.0)
        return base + (ceiling - base) * (1.0 - reliability)
    }

    fun expectedGoals(input: PreMatchInput): Pair<Double, Double> {
        // The competition profile only removes evidence that does not apply (a neutral
        // venue has no home advantage); it never invents an effect.
        val profile = input.competition
        val baseline = profile.adjustBaseline(input.baseline)
        var lambdaHome = baseline.averageHomeGoals * input.home.attack * input.away.defence
        var lambdaAway = baseline.averageAwayGoals * input.away.attack * input.home.defence

        // Elo tilt: total-preserving redistribution. Exponential factors move
        // goals between the teams, then the pair is rescaled to the pre-tilt
        // total, so Elo changes the split, never the total.
        // The weight grows when the fitted strengths rest on very few matches.
        val eloDiff = input.home.elo - input.away.elo
        val blend = effectiveEloBlendWeight(input) * profile.eloBlendScale
        val tilt = (eloDiff / 400.0 * blend).coerceIn(-0.25, 0.25)
        val totalBeforeTilt = lambdaHome + lambdaAway
        if (tilt != 0.0 && totalBeforeTilt > 0.0) {
            val tiltedHome = lambdaHome * exp(tilt)
            val tiltedAway = lambdaAway * exp(-tilt)
            val tiltedTotal = tiltedHome + tiltedAway
            if (tiltedTotal > 0.0 && tiltedTotal.isFinite()) {
                val scale = totalBeforeTilt / tiltedTotal
                lambdaHome = tiltedHome * scale
                lambdaAway = tiltedAway * scale
            }
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
        val rawMarkets = MarketCalculator.fromMatrix(
            matrix = matrix,
            firstHalfShare = config.firstHalfShare,
            topScoreCount = 8,
        )

        val calibration = input.calibration
        val calibratedTriple = Calibrator.apply(
            calibration,
            rawMarkets.homeWin,
            rawMarkets.draw,
            rawMarkets.awayWin,
        )
        // Market alignment comes after calibration: the calibrated triple is the model's
        // best statement, and the prices are then mixed into it. Because both are pushed
        // through the same score matrix, over/under, BTTS and exact score stay consistent
        // with the headline numbers instead of drifting away from them.
        val alignmentWeight = marketAlignmentWeight(input)
        val marketTriple = input.market
        val alignedTriple = if (marketTriple != null && alignmentWeight > 0.0) {
            val weight = alignmentWeight.coerceIn(0.0, 0.9)
            Triple(
                calibratedTriple.first * (1.0 - weight) + marketTriple.home * weight,
                calibratedTriple.second * (1.0 - weight) + marketTriple.draw * weight,
                calibratedTriple.third * (1.0 - weight) + marketTriple.away * weight,
            )
        } else {
            calibratedTriple
        }
        val markets = if (calibration.isApplied || alignedTriple != calibratedTriple) {
            // Propagate the 1X2 masses through the whole score matrix so that over/under,
            // BTTS and exact score stay consistent with the headline numbers.
            MarketCalculator.fromMatrix(
                matrix = matrix.rescaledToOutcomes(
                    homeWin = alignedTriple.first,
                    draw = alignedTriple.second,
                    awayWin = alignedTriple.third,
                ),
                firstHalfShare = config.firstHalfShare,
                topScoreCount = 8,
            )
        } else {
            rawMarkets
        }
        val normalised = Calibrator.normalise(markets.homeWin, markets.draw, markets.awayWin)
            ?: Triple(rawMarkets.homeWin, rawMarkets.draw, rawMarkets.awayWin)

        val model = ModelDescriptor(
            name = if (rho != 0.0) "Dixon-Coles" else "Independent Poisson",
            version = input.modelVersion,
            dixonColesEnabled = rho != 0.0,
            rho = rho,
            fittedMatches = input.fittedMatches,
            // The weight actually applied, not the configured base weight:
            // the effective weight rises toward maxEloBlendWeight when evidence is thin.
            eloBlendWeight = effectiveEloBlendWeight(input) * input.competition.eloBlendScale,
        )

        val explanations = buildPreMatchExplanations(input, normalised.first, normalised.second, normalised.third)
            .let { base ->
                if (alignmentWeight > 0.0) {
                    base + Explanation(ExplanationCode.MARKET_ALIGNED, alignmentWeight.coerceIn(0.05, 0.45))
                } else {
                    base
                }
            }

        val confidence = confidenceFor(
            input = input,
            probabilities = normalised,
            calibration = calibration,
        )

        return PredictionResult(
            expectedHomeGoals = lambdaHome,
            expectedAwayGoals = lambdaAway,
            markets = markets,
            mostLikelyScores = markets.topScores.take(5),
            model = model,
            explanations = explanations,
            quality = noteQuality(input, calibration),
            asOfEpochMs = input.asOfEpochMs,
            live = null,
            confidence = confidence,
            preMarketProbabilities = if (alignmentWeight > 0.0) {
                OutcomeTriple(calibratedTriple.first, calibratedTriple.second, calibratedTriple.third)
            } else {
                null
            },
            // Record the raw model triple even when no calibration is applied. The final
            // triple may already include market alignment and must not be used to fit a
            // mapping that will later be applied to the raw model distribution.
            calibration = CalibrationInfo(
                method = calibration.method.name,
                temperature = calibration.temperature,
                sampleCount = calibration.sampleCount,
                holdoutCount = calibration.holdoutCount,
                holdoutLogLossBefore = calibration.holdoutLogLossBefore,
                holdoutLogLossAfter = calibration.holdoutLogLossAfter,
                rawHomeWin = rawMarkets.homeWin,
                rawDraw = rawMarkets.draw,
                rawAwayWin = rawMarkets.awayWin,
            ),
        )
    }

    fun predictLive(input: LiveInput): PredictionResult {
        val preMatch = input.preMatch
        val (fullHome, fullAway) = expectedGoals(preMatch)

        // The horizon of a football match is regulation time plus its stoppage time, not
        // ninety minutes. A caller that knows the real figure supplies it; otherwise the
        // configured allowance stands in, so the last minutes are not modelled as if the
        // whistle were already at the clock. A knockout tie adds thirty minutes of extra
        // time on top: minute 115 of a cup tie is nine minutes of football left, not a
        // finished match. The stoppage allowance is shared across regulation and extra
        // time; penalties are not modelled, so once extra time is over the remaining
        // minutes are zero, which is then correct.
        val extraTime = if (preMatch.competition.isKnockout) config.extraTimeMinutes else 0
        val addedMinutes = maxOf(input.addedTimeMinutes, config.stoppageTimeAllowanceMinutes)
        val totalMinutes = (config.matchLengthMinutes + extraTime + addedMinutes).coerceAtLeast(1)
        val minutesPlayed = input.minute.coerceIn(0, totalMinutes)
        val remainingMinutes = totalMinutes - minutesPlayed
        // Share of the *match* still to be played, so that a minute spent is a minute gone
        // whether or not the clock has passed ninety.
        val remainingShare = (remainingMinutes.toDouble() / totalMinutes)
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
        val quality = preMatch.quality.copy(notes = (preMatch.quality.notes + extraNotes).distinct())

        val model = ModelDescriptor(
            name = "In-play Poisson (time remaining)",
            version = preMatch.modelVersion,
            dixonColesEnabled = false,
            rho = 0.0,
            fittedMatches = preMatch.fittedMatches,
            // The weight actually applied, not the configured base weight.
            eloBlendWeight = effectiveEloBlendWeight(preMatch) * preMatch.competition.eloBlendScale,
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
            confidence = confidenceFor(
                input = preMatch,
                probabilities = Calibrator.normalise(markets.homeWin, markets.draw, markets.awayWin)
                    ?: Triple(markets.homeWin, markets.draw, markets.awayWin),
                // The in-play model is not calibrated yet, so no calibration evidence may
                // be credited to it even when a pre-match calibration exists.
                calibration = CalibrationModel.NONE,
                isLive = true,
            ),
            calibration = null,
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

        if (input.competition.neutralVenue) {
            list.add(Explanation(ExplanationCode.NEUTRAL_VENUE_ADJUSTMENT, 0.45))
        }
        if (input.competition.isInternational) {
            val nationalEdge = input.home.elo - input.away.elo
            if (abs(nationalEdge) >= 60.0) {
                list.add(
                    Explanation(
                        ExplanationCode.NATIONAL_TEAM_ELO,
                        (abs(nationalEdge) / 400.0).coerceIn(0.0, 1.0),
                        params("elo", abs(nationalEdge)),
                    ),
                )
            }
        }
        if (input.calibration.isApplied) {
            list.add(
                Explanation(
                    ExplanationCode.CALIBRATION_ADJUSTMENT,
                    0.35,
                    mapOf("method" to input.calibration.method.name),
                ),
            )
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

    /**
     * Builds the evidence assessment for a prediction.
     *
     * The level comes from sample size, data completeness, calibration quality and
     * freshness. The spread of the probabilities is reported separately and is never
     * used to claim confidence.
     */
    private fun confidenceFor(
        input: PreMatchInput,
        probabilities: Triple<Double, Double, Double>,
        calibration: CalibrationModel,
        isLive: Boolean = false,
    ): com.footballai.engine.confidence.ConfidenceAssessment {
        val unsupported = input.quality.notes.any { it.code == QualityNoteCode.NO_HISTORY_FOR_COMPETITION }
        return ConfidenceAssessor.assess(
            ConfidenceInput(
                competitionHistoricalMatches = input.quality.historicalMatchesUsed,
                homeMatchesUsed = input.home.matchesUsed,
                awayMatchesUsed = input.away.matchesUsed,
                probabilities = probabilities,
                dataCompleteness = input.quality.completeness,
                calibrationSamples = if (calibration.isApplied) calibration.sampleCount else 0,
                calibrationError = if (calibration.isApplied) {
                    calibration.holdoutExpectedCalibrationError
                } else {
                    Double.NaN
                },
                freshnessMinutes = input.dataFreshnessMinutes,
                isLive = isLive,
                unsupportedCompetition = unsupported,
            ),
        )
    }

    /** Adds the two calibration notes so the UI can explain what was actually applied. */
    private fun noteQuality(
        input: PreMatchInput,
        calibration: CalibrationModel,
    ): com.footballai.engine.model.DataQuality {
        val note = if (calibration.isApplied) {
            QualityNote(
                QualityNoteCode.CALIBRATION_APPLIED,
                mapOf(
                    "samples" to calibration.sampleCount.toString(),
                    "method" to calibration.method.name,
                ),
            )
        } else {
            QualityNote(QualityNoteCode.CALIBRATION_UNAVAILABLE)
        }
        val profileNotes = buildList {
            if (input.competition.neutralVenue) add(QualityNote(QualityNoteCode.NEUTRAL_VENUE))
            if (input.competition.isInternational) add(QualityNote(QualityNoteCode.NATIONAL_TEAM_MODEL))
        }
        // One statement about calibration, and it is the true one: a note carried in from
        // an earlier stage is dropped rather than printed beside its own opposite.
        val inherited = input.quality.notes.filterNot {
            it.code == QualityNoteCode.MODEL_NOT_CALIBRATED || it.code == QualityNoteCode.CALIBRATION_APPLIED ||
                it.code == QualityNoteCode.CALIBRATION_UNAVAILABLE
        }
        val notes = (inherited + profileNotes + note).distinct()
        return input.quality.copy(notes = notes)
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
