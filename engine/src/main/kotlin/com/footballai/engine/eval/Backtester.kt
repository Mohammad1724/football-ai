package com.footballai.engine.eval

import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.CalibrationSample
import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.OutcomeTriple
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.prediction.EngineConfig
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import com.footballai.engine.ratings.DixonColesFit
import com.footballai.engine.ratings.ContextFeatures
import com.footballai.engine.ratings.DixonColesFitter
import com.footballai.engine.ratings.EloConfig
import com.footballai.engine.ratings.EloRatingSystem
import com.footballai.engine.ratings.RatingMatch
import kotlin.math.max

/** A finished match used for historical evaluation. Odds are optional. */
data class HistoricalRecord(
    val matchId: String,
    val competitionId: String,
    val homeTeamId: String,
    val awayTeamId: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val kickoffEpochMs: Long,
    val oddsHome: Double? = null,
    val oddsDraw: Double? = null,
    val oddsAway: Double? = null,
    /** Post-match expected goals, when the data source carries them. See [BacktestConfig.xgBlendWeight]. */
    val homeXg: Double? = null,
    val awayXg: Double? = null,
    /**
     * Share of the team's season minutes belonging to players listed
     * unavailable for this fixture (injured/suspended), known before kickoff.
     * See [BacktestConfig.availabilityStrength]. Null means the source
     * published no availability list for the team — never a zero.
     */
    val homeMissingShare: Double? = null,
    val awayMissingShare: Double? = null,
) {
    init {
        require(homeGoals >= 0 && awayGoals >= 0) { "Goals must be non-negative" }
    }

    val actualOutcome: MatchOutcome
        get() = when {
            homeGoals > awayGoals -> MatchOutcome.HOME_WIN
            homeGoals == awayGoals -> MatchOutcome.DRAW
            else -> MatchOutcome.AWAY_WIN
        }
}

data class BacktestConfig(
    val refitEveryMatches: Int = 150,
    val minPriorMatchesForCompetition: Int = 60,
    val minMatchesPerTeam: Int = 4,
    val halfLifeDays: Double = 240.0,
    val windowDays: Int = 1200,
    /**
     * Dixon-Coles iterations. Must match the production fit
     * ([com.footballai.engine.ratings.DixonColesConfig.maxIterations], 400):
     * fewer iterations fit a different model, so the backtest would measure
     * something other than the model that actually runs.
     */
    val dixonColesMaxIterations: Int = 400,
    val elo: EloConfig = EloConfig(),
    val engine: EngineConfig = EngineConfig(),
    val binCount: Int = 10,
    /** Enables the same recent-form, venue and congestion features used in production. */
    val contextFeaturesEnabled: Boolean = true,
    /**
     * Blend of xG into the Dixon-Coles strength fit. The default is the production
     * blend (0.7); 0.0 is goals only. A record's xG joins the rating history at the
     * same moment its goals do — after the match was played and the availability
     * delay has passed — so the walk-forward leakage controls apply to xG exactly
     * as they do to goals.
     */
    val xgBlendWeight: Double = 0.7,
    /**
     * Strength of the injuries/suspensions adjustment (0.0 = off, the default,
     * so existing backtests are unchanged). A record's missing shares describe
     * the fixture itself and were published before its kickoff, so — unlike a
     * record's goals — they are applied to that fixture's own forecast, exactly
     * as production applies them. See [com.footballai.engine.ratings.AvailabilityImpact].
     */
    val availabilityStrength: Double = 0.0,
    /**
     * Applies the production calibration inside the walk-forward: the calibrator
     * is fitted only on forecasts whose matches have already been played (and
     * whose results are available), and refit whenever the ratings are refit.
     * Below [Calibrator.Config.minSamples] resolved forecasts it returns NONE,
     * so small backtests are unchanged. Disable it to measure the raw model.
     *
     * The fit is per competition, while production pools it per competition
     * type, so the backtest calibration is the same method and discipline, not
     * the numerically identical model.
     */
    val calibrationEnabled: Boolean = true,
    val calibrator: Calibrator.Config = Calibrator.Config(),
    /**
     * Conservative result availability when historic completion timestamps are absent.
     *
     * 180 minutes covers a match that runs to extra time and penalties (about 150
     * minutes from kickoff) with margin; 120 leaked such a result into the prior
     * while the match was still being played. A caller that knows its competition
     * has no extra time may pass 120 explicitly — the minimum stays there — but
     * the default must not leak.
     */
    val resultAvailabilityDelayMinutes: Int = 180,
)

data class BacktestSample(
    val matchId: String,
    val competitionId: String,
    val kickoffEpochMs: Long,
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val over25: Double,
    val btts: Double,
    val actual: MatchOutcome,
    val actualOver25: Boolean,
    val actualBtts: Boolean,
    val marketHome: Double?,
    val marketDraw: Double?,
    val marketAway: Double?,
    val qualityLevel: DataQualityLevel,
    /** 1X2 before the market alignment; equal to the final triple when there were no prices. */
    val unalignedHome: Double = homeWin,
    val unalignedDraw: Double = draw,
    val unalignedAway: Double = awayWin,
    /**
     * The 1X2 triple before calibration (and before market alignment): the raw
     * model forecast. The headline fields carry the calibrated triple when a
     * calibration was applied, so this is what the calibration study must use.
     */
    val rawHomeWin: Double = homeWin,
    val rawDraw: Double = draw,
    val rawAwayWin: Double = awayWin,
)

data class BacktestReport(
    val matchesEvaluated: Int,
    val model: MetricSummary,
    val baseRate: Double,
    val market: MetricSummary?,
    val modelOnMarketSubset: MetricSummary?,
    /** The model with the market alignment switched off, on every evaluated match. */
    val unaligned: MetricSummary? = null,
    /** The same, restricted to matches that had prices: the paired comparison. */
    val unalignedOnMarketSubset: MetricSummary? = null,
    val over25: MetricSummary,
    val btts: MetricSummary,
    /** Home-win reliability curve, for the chart. */
    val calibration: List<CalibrationBin>,
    /** Expected calibration error over all three outcomes: the same measure the calibrator uses. */
    val expectedCalibrationError: Double,
    val byCompetition: Map<String, MetricSummary>,
    /**
     * Base-rate Brier per competition: what "always predict this league's own 1X2
     * frequencies" scores on the same matches. A league's model Brier is only meaningful
     * next to it, because leagues differ in how predictable they are at all.
     */
    val byCompetitionBaseRate: Map<String, Double> = emptyMap(),
    val byQualityLevel: Map<DataQualityLevel, MetricSummary>,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
) {
    val beatsBaseRate: Boolean get() = !model.brier.isNaN() && !baseRate.isNaN() && model.brier < baseRate
    val beatsMarket: Boolean? get() = market?.let { m -> modelOnMarketSubset?.let { paired -> !paired.brier.isNaN() && !m.brier.isNaN() && paired.brier < m.brier } }
}

/**
 * Chronological walk-forward evaluation.
 *
 * For every match the model only ever sees results that happened *before* the
 * kickoff, which is the only honest way to estimate out-of-sample performance.
 * Ratings are refitted every [BacktestConfig.refitEveryMatches] matches to keep the
 * runtime acceptable on a phone.
 */
class Backtester(private val config: BacktestConfig = BacktestConfig()) {

    init {
        require(config.refitEveryMatches > 0)
        require(config.resultAvailabilityDelayMinutes >= 120)
    }

    private val engine = MatchPredictionEngine(config.engine)

    fun run(records: List<HistoricalRecord>): BacktestReport = buildReport(samples(records))

    /**
     * Chronological walk-forward forecasts without aggregation.
     *
     * Exposed so that the same predictions can be studied by more than one analysis -
     * for example the calibration study, which needs the individual probabilities
     * rather than the summary metrics.
     */
    fun samples(records: List<HistoricalRecord>): List<BacktestSample> {
        val samples = ArrayList<BacktestSample>()
        val byCompetition = records.groupBy { it.competitionId }
        for ((competitionId, competitionRecords) in byCompetition) {
            samples.addAll(evaluateCompetition(competitionId, competitionRecords))
        }
        samples.sortBy { it.kickoffEpochMs }
        return samples
    }

    internal fun evaluateCompetition(competitionId: String, records: List<HistoricalRecord>): List<BacktestSample> {
        val ordered = records.sortedWith(compareBy({ it.kickoffEpochMs }, { it.homeTeamId }, { it.awayTeamId }, { it.matchId }))
        if (ordered.size <= config.minPriorMatchesForCompetition) return emptyList()

        val prior = ArrayList<RatingMatch>(ordered.size)
        val elo = EloRatingSystem(config.elo)
        var fit: DixonColesFit? = null
        var baseline = LeagueBaseline.FALLBACK
        val samples = ArrayList<BacktestSample>()
        // Calibration fitted only on forecasts already resolved before the
        // match being forecast — the same out-of-sample discipline as
        // production, where the calibrator is fitted on resolved stored
        // predictions. The samples are emitted in kickoff order, so a pointer
        // into them admits each one once its result is available.
        var calibration: CalibrationModel = CalibrationModel.NONE
        val resolved = ArrayList<CalibrationSample>()
        var resolvedAdmitted = 0

        var index = 0
        var lastFitAt = 0
        var availableIndex = 0
        while (index < ordered.size) {
            // Predict an entire simultaneous kickoff group before admitting any result.
            var chunkEnd = index + 1
            while (chunkEnd < ordered.size && ordered[chunkEnd].kickoffEpochMs == ordered[index].kickoffEpochMs) chunkEnd++
            val predictionTime = ordered[index].kickoffEpochMs
            while (availableIndex < index &&
                ordered[availableIndex].kickoffEpochMs + config.resultAvailabilityDelayMinutes * 60_000L <= predictionTime) {
                val record = ordered[availableIndex++]
                prior.add(
                    RatingMatch(
                        homeTeamId = record.homeTeamId,
                        awayTeamId = record.awayTeamId,
                        homeGoals = record.homeGoals,
                        awayGoals = record.awayGoals,
                        kickoffEpochMs = record.kickoffEpochMs,
                        homeXg = record.homeXg,
                        awayXg = record.awayXg,
                    ),
                )
                elo.update(
                    homeTeamId = record.homeTeamId,
                    awayTeamId = record.awayTeamId,
                    homeGoals = record.homeGoals,
                    awayGoals = record.awayGoals,
                    kickoffEpochMs = record.kickoffEpochMs,
                )
            }
            while (resolvedAdmitted < samples.size &&
                samples[resolvedAdmitted].kickoffEpochMs + config.resultAvailabilityDelayMinutes * 60_000L <= predictionTime) {
                val resolvedSample = samples[resolvedAdmitted++]
                resolved.add(
                    CalibrationSample(
                        resolvedSample.rawHomeWin,
                        resolvedSample.rawDraw,
                        resolvedSample.rawAwayWin,
                        resolvedSample.actual,
                    ),
                )
            }
            if (prior.size >= config.minPriorMatchesForCompetition &&
                (fit == null || prior.size - lastFitAt >= config.refitEveryMatches)) {
                lastFitAt = prior.size
                fit = fitModel(prior, ordered[index].kickoffEpochMs)
                baseline = computeBaseline(prior, ordered[index].kickoffEpochMs)
                if (config.calibrationEnabled) {
                    calibration = Calibrator.fit(resolved, config.calibrator)
                }
            }

            for (i in index until chunkEnd) {
                val record = ordered[i]
                val homeSample = fit?.matchesByTeam?.get(record.homeTeamId) ?: 0
                val awaySample = fit?.matchesByTeam?.get(record.awayTeamId) ?: 0
                val eligible = prior.size >= config.minPriorMatchesForCompetition &&
                    homeSample >= config.minMatchesPerTeam &&
                    awaySample >= config.minMatchesPerTeam

                if (eligible) {
                    val home = TeamStrength(
                        teamId = record.homeTeamId,
                        attack = fit?.attack?.get(record.homeTeamId) ?: 1.0,
                        defence = fit?.defence?.get(record.homeTeamId) ?: 1.0,
                        elo = elo.rating(record.homeTeamId, record.kickoffEpochMs),
                        matchesUsed = homeSample,
                    )
                    val away = TeamStrength(
                        teamId = record.awayTeamId,
                        attack = fit?.attack?.get(record.awayTeamId) ?: 1.0,
                        defence = fit?.defence?.get(record.awayTeamId) ?: 1.0,
                        elo = elo.rating(record.awayTeamId, record.kickoffEpochMs),
                        matchesUsed = awaySample,
                    )
                    val quality = DataQualityAssessor.assess(
                        QualityInputs(
                            competitionHistoricalMatches = prior.size,
                            homeMatchesUsed = homeSample,
                            awayMatchesUsed = awaySample,
                            hasOdds = record.oddsHome != null,
                            leagueBaselineIsFallback = baseline.sampleSize == 0,
                        ),
                    )
                    val input = PreMatchInput(
                        home = home,
                        away = away,
                        baseline = baseline,
                        adjustments = buildList {
                            if (config.contextFeaturesEnabled) {
                                addAll(
                                    ContextFeatures.adjustments(
                                        history = prior,
                                        homeTeamId = record.homeTeamId,
                                        awayTeamId = record.awayTeamId,
                                        asOfEpochMs = record.kickoffEpochMs,
                                    ),
                                )
                            }
                            if (config.availabilityStrength > 0.0) {
                                com.footballai.engine.ratings.AvailabilityImpact.adjustment(
                                    homeMissingShare = record.homeMissingShare,
                                    awayMissingShare = record.awayMissingShare,
                                    config = com.footballai.engine.ratings.AvailabilityImpactConfig(
                                        strength = config.availabilityStrength,
                                    ),
                                )?.let { add(it) }
                            }
                        },
                        market = MarketCalculator.impliedProbabilities(
                            record.oddsHome, record.oddsDraw, record.oddsAway,
                        )?.let { OutcomeTriple(it.first, it.second, it.third) },
                        eloBlendWeight = config.engine.eloBlendWeight,
                        dixonColesRho = fit?.rho,
                        calibration = calibration,
                        quality = quality,
                        asOfEpochMs = record.kickoffEpochMs,
                        fittedMatches = prior.size,
                    )
                    val prediction = engine.predictPreMatch(input)
                    val market = MarketCalculator.impliedProbabilities(
                        record.oddsHome, record.oddsDraw, record.oddsAway,
                    )
                    val totalGoals = record.homeGoals + record.awayGoals
                    samples.add(
                        BacktestSample(
                            matchId = record.matchId,
                            competitionId = competitionId,
                            kickoffEpochMs = record.kickoffEpochMs,
                            homeWin = prediction.markets.homeWin,
                            draw = prediction.markets.draw,
                            awayWin = prediction.markets.awayWin,
                            over25 = prediction.markets.over[2.5] ?: Double.NaN,
                            btts = prediction.markets.bttsYes,
                            actual = record.actualOutcome,
                            actualOver25 = totalGoals > 2.5,
                            actualBtts = record.homeGoals > 0 && record.awayGoals > 0,
                            marketHome = market?.first,
                            marketDraw = market?.second,
                            marketAway = market?.third,
                            unalignedHome = prediction.preMarketProbabilities?.home ?: prediction.markets.homeWin,
                            unalignedDraw = prediction.preMarketProbabilities?.draw ?: prediction.markets.draw,
                            unalignedAway = prediction.preMarketProbabilities?.away ?: prediction.markets.awayWin,
                            rawHomeWin = prediction.calibration?.rawHomeWin
                                ?: prediction.preMarketProbabilities?.home
                                ?: prediction.markets.homeWin,
                            rawDraw = prediction.calibration?.rawDraw
                                ?: prediction.preMarketProbabilities?.draw
                                ?: prediction.markets.draw,
                            rawAwayWin = prediction.calibration?.rawAwayWin
                                ?: prediction.preMarketProbabilities?.away
                                ?: prediction.markets.awayWin,
                            qualityLevel = quality.level,
                        ),
                    )
                }

            }
            index = chunkEnd
        }
        return samples
    }

    private fun fitModel(prior: List<RatingMatch>, asOfEpochMs: Long): DixonColesFit? {
        val windowMs = config.windowDays.toLong() * 86_400_000L
        val decayed = prior.mapNotNull { match ->
            val ageMs = asOfEpochMs - match.kickoffEpochMs
            if (ageMs <= 0L || ageMs > windowMs) return@mapNotNull null
            val weight = EloRatingSystem.timeDecayWeight(ageMs / 86_400_000.0, config.halfLifeDays)
            match.copy(weight = weight)
        }
        if (decayed.size < config.minPriorMatchesForCompetition) return null
        return DixonColesFitter.fit(
            decayed,
            com.footballai.engine.ratings.DixonColesConfig(
                maxIterations = config.dixonColesMaxIterations,
                xgBlendWeight = config.xgBlendWeight,
            ),
        )
    }

    private fun computeBaseline(prior: List<RatingMatch>, asOfEpochMs: Long): LeagueBaseline {
        val windowMs = config.windowDays.toLong() * 86_400_000L
        var weightSum = 0.0
        var homeGoals = 0.0
        var awayGoals = 0.0
        var count = 0
        val xgWeight = config.xgBlendWeight.coerceIn(0.0, 1.0)
        for (match in prior) {
            val ageMs = asOfEpochMs - match.kickoffEpochMs
            if (ageMs <= 0L || ageMs > windowMs) continue
            val weight = EloRatingSystem.timeDecayWeight(ageMs / 86_400_000.0, config.halfLifeDays)
            weightSum += weight
            // The baseline is the league's average of the same blended evidence the
            // strengths are fitted on; with xgWeight = 0 this is the old goals average.
            val hxg = match.homeXg
            val axg = match.awayXg
            val effHome = if (xgWeight > 0.0 && hxg != null && hxg.isFinite()) {
                (1.0 - xgWeight) * match.homeGoals + xgWeight * hxg
            } else {
                match.homeGoals.toDouble()
            }
            val effAway = if (xgWeight > 0.0 && axg != null && axg.isFinite()) {
                (1.0 - xgWeight) * match.awayGoals + xgWeight * axg
            } else {
                match.awayGoals.toDouble()
            }
            homeGoals += weight * effHome
            awayGoals += weight * effAway
            count++
        }
        if (weightSum <= 0.0) return LeagueBaseline.FALLBACK
        return LeagueBaseline(
            averageHomeGoals = (homeGoals / weightSum).coerceIn(0.2, 5.0),
            averageAwayGoals = (awayGoals / weightSum).coerceIn(0.2, 5.0),
            sampleSize = count,
        )
    }

    private fun buildReport(samples: List<BacktestSample>): BacktestReport {
        val outcomeSamples = samples.map { OutcomeSample(it.homeWin, it.draw, it.awayWin, it.actual) }
        val marketSamples = samples.mapNotNull { sample ->
            val home = sample.marketHome ?: return@mapNotNull null
            val draw = sample.marketDraw ?: return@mapNotNull null
            val away = sample.marketAway ?: return@mapNotNull null
            OutcomeSample(home, draw, away, sample.actual)
        }
        val pairedModel = samples.filter { it.marketHome != null && it.marketDraw != null && it.marketAway != null }
            .map { OutcomeSample(it.homeWin, it.draw, it.awayWin, it.actual) }
        // The same forecasts with the market alignment removed, in the two shapes the
        // report advertises: over every evaluated match, and restricted to the ones that
        // had prices. Both arms used to be the priced subset, so the field documented as
        // "every match" silently differed from the headline only by its sample, and the
        // effect of leaning on the prices could not be read off the report at all.
        val unalignedAll = samples.map {
            OutcomeSample(it.unalignedHome, it.unalignedDraw, it.unalignedAway, it.actual)
        }
        val unalignedPaired = samples
            .filter { it.marketHome != null && it.marketDraw != null && it.marketAway != null }
            .map { OutcomeSample(it.unalignedHome, it.unalignedDraw, it.unalignedAway, it.actual) }
        val overSamples = samples.filter { !it.over25.isNaN() }
            .map { BinarySample(it.over25, it.actualOver25) }
        val bttsSamples = samples.map { BinarySample(it.btts, it.actualBtts) }
        val homeWinSamples = samples.map { BinarySample(it.homeWin, it.actual == MatchOutcome.HOME_WIN) }

        val byCompetition = samples.groupBy { it.competitionId }.mapValues { (_, list) ->
            Metrics.summary(list.map { OutcomeSample(it.homeWin, it.draw, it.awayWin, it.actual) })
        }
        val byCompetitionBaseRate = samples.groupBy { it.competitionId }.mapValues { (_, list) ->
            Metrics.baseRateBrier(list.map { OutcomeSample(it.homeWin, it.draw, it.awayWin, it.actual) })
        }
        val byQuality = samples.groupBy { it.qualityLevel }.mapValues { (level, list) ->
            Metrics.summary(list.map { OutcomeSample(it.homeWin, it.draw, it.awayWin, it.actual) })
        }
        val calibration = Metrics.calibration(homeWinSamples, config.binCount)

        return BacktestReport(
            matchesEvaluated = samples.size,
            model = Metrics.summary(outcomeSamples),
            baseRate = Metrics.baseRateBrier(outcomeSamples),
            market = if (marketSamples.isEmpty()) null else Metrics.summary(marketSamples),
            modelOnMarketSubset = if (pairedModel.isEmpty()) null else Metrics.summary(pairedModel),
            unaligned = if (unalignedAll.isEmpty()) null else Metrics.summary(unalignedAll),
            unalignedOnMarketSubset = if (unalignedPaired.isEmpty()) null else Metrics.summary(unalignedPaired),
            over25 = Metrics.binarySummary(overSamples),
            btts = Metrics.binarySummary(bttsSamples),
            calibration = calibration,
            // The chart stays a home-win reliability curve; the error that goes on the same
            // screen is the pooled three-outcome one, so it agrees with the number the
            // calibrator decides on.
            expectedCalibrationError = Metrics.expectedCalibrationErrorMultiClass(outcomeSamples, config.binCount),
            byCompetition = byCompetition,
            byCompetitionBaseRate = byCompetitionBaseRate,
            byQualityLevel = byQuality,
            periodStartEpochMs = samples.minOfOrNull { it.kickoffEpochMs } ?: 0L,
            periodEndEpochMs = samples.maxOfOrNull { it.kickoffEpochMs } ?: 0L,
        )
    }

    /** Exposed for diagnostics and unit tests. */
    fun evaluationSampleCount(records: List<HistoricalRecord>): Int {
        val report = run(records)
        return max(0, report.matchesEvaluated)
    }
}
