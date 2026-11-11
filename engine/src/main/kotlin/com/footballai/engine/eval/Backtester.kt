package com.footballai.engine.eval

import com.footballai.engine.markets.MarketCalculator
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
    val dixonColesMaxIterations: Int = 250,
    val elo: EloConfig = EloConfig(),
    val engine: EngineConfig = EngineConfig(),
    val binCount: Int = 10,
    /** Enables the same recent-form, venue and congestion features used in production. */
    val contextFeaturesEnabled: Boolean = true,
    /** Conservative result availability when historic completion timestamps are absent. */
    val resultAvailabilityDelayMinutes: Int = 120,
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
)

data class BacktestReport(
    val matchesEvaluated: Int,
    val model: MetricSummary,
    val baseRate: Double,
    val market: MetricSummary?,
    val modelOnMarketSubset: MetricSummary?,
    val over25: MetricSummary,
    val btts: MetricSummary,
    val calibration: List<CalibrationBin>,
    val expectedCalibrationError: Double,
    val byCompetition: Map<String, MetricSummary>,
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

    fun run(records: List<HistoricalRecord>): BacktestReport {
        val samples = ArrayList<BacktestSample>()
        val byCompetition = records.groupBy { it.competitionId }
        for ((competitionId, competitionRecords) in byCompetition) {
            samples.addAll(evaluateCompetition(competitionId, competitionRecords))
        }
        samples.sortBy { it.kickoffEpochMs }
        return buildReport(samples)
    }

    internal fun evaluateCompetition(competitionId: String, records: List<HistoricalRecord>): List<BacktestSample> {
        val ordered = records.sortedWith(compareBy({ it.kickoffEpochMs }, { it.homeTeamId }, { it.awayTeamId }, { it.matchId }))
        if (ordered.size <= config.minPriorMatchesForCompetition) return emptyList()

        val prior = ArrayList<RatingMatch>(ordered.size)
        val elo = EloRatingSystem(config.elo)
        var fit: DixonColesFit? = null
        var baseline = LeagueBaseline.FALLBACK
        val samples = ArrayList<BacktestSample>()

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
            if (prior.size >= config.minPriorMatchesForCompetition &&
                (fit == null || prior.size - lastFitAt >= config.refitEveryMatches)) {
                lastFitAt = prior.size
                fit = fitModel(prior, ordered[index].kickoffEpochMs)
                baseline = computeBaseline(prior, ordered[index].kickoffEpochMs)
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
                        adjustments = if (config.contextFeaturesEnabled) {
                            ContextFeatures.adjustments(
                                history = prior,
                                homeTeamId = record.homeTeamId,
                                awayTeamId = record.awayTeamId,
                                asOfEpochMs = record.kickoffEpochMs,
                            )
                        } else {
                            emptyList()
                        },
                        eloBlendWeight = config.engine.eloBlendWeight,
                        dixonColesRho = fit?.rho,
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
            com.footballai.engine.ratings.DixonColesConfig(maxIterations = config.dixonColesMaxIterations),
        )
    }

    private fun computeBaseline(prior: List<RatingMatch>, asOfEpochMs: Long): LeagueBaseline {
        val windowMs = config.windowDays.toLong() * 86_400_000L
        var weightSum = 0.0
        var homeGoals = 0.0
        var awayGoals = 0.0
        var count = 0
        for (match in prior) {
            val ageMs = asOfEpochMs - match.kickoffEpochMs
            if (ageMs <= 0L || ageMs > windowMs) continue
            val weight = EloRatingSystem.timeDecayWeight(ageMs / 86_400_000.0, config.halfLifeDays)
            weightSum += weight
            homeGoals += weight * match.homeGoals
            awayGoals += weight * match.awayGoals
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
        val overSamples = samples.filter { !it.over25.isNaN() }
            .map { BinarySample(it.over25, it.actualOver25) }
        val bttsSamples = samples.map { BinarySample(it.btts, it.actualBtts) }
        val homeWinSamples = samples.map { BinarySample(it.homeWin, it.actual == MatchOutcome.HOME_WIN) }

        val byCompetition = samples.groupBy { it.competitionId }.mapValues { (_, list) ->
            Metrics.summary(list.map { OutcomeSample(it.homeWin, it.draw, it.awayWin, it.actual) })
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
            over25 = Metrics.binarySummary(overSamples),
            btts = Metrics.binarySummary(bttsSamples),
            calibration = calibration,
            expectedCalibrationError = Metrics.expectedCalibrationError(calibration),
            byCompetition = byCompetition,
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
