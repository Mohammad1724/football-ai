package com.footballai.app.data.repository

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.domain.model.CalibrationPoint
import com.footballai.app.domain.model.CompetitionPerformance
import com.footballai.app.domain.model.PerformanceReport
import com.footballai.engine.eval.BacktestConfig
import com.footballai.engine.eval.Backtester
import com.footballai.engine.eval.HistoricalRecord
import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.eval.CalibrationStudy
import com.footballai.engine.eval.Metrics
import com.footballai.engine.eval.OutcomeSample
import com.footballai.engine.model.MatchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

data class HistoricalSourceOption(
    val source: String,
    val displayName: String,
    val matches: Int,
    val competitions: List<CompetitionPerformanceSource>,
)

data class CompetitionPerformanceSource(
    val competitionId: String,
    val name: String,
    val matches: Int,
    val fromEpochMs: Long,
    val toEpochMs: Long,
)

/** Performance of the predictions the user actually stored (not a backtest). */
/** Measured, out-of-sample effect of the calibration layer for one competition class. */
data class CalibrationStudyResult(
    val competitionClass: String,
    val sampleCount: Int,
    val adopted: Boolean,
    val method: String,
    val rawLogLoss: Double = Double.NaN,
    val calibratedLogLoss: Double = Double.NaN,
    val rawBrier: Double = Double.NaN,
    val calibratedBrier: Double = Double.NaN,
    val rawCalibrationError: Double = Double.NaN,
    val calibratedCalibrationError: Double = Double.NaN,
    val note: String? = null,
) {
    val logLossGain: Double get() = rawLogLoss - calibratedLogLoss
}

data class StoredPredictionStats(
    val resolved: Int,
    val correct: Int,
    val brier: Double,
    val logLoss: Double,
    val accuracy: Double,
    val audit: StoredForecastAudit = StoredForecastAudit(),
    val paired: PairedForecastAudit = PairedForecastAudit(),
    val lateSnapshots: Int = 0,
) {
    companion object {
        val EMPTY = StoredPredictionStats(0, 0, Double.NaN, Double.NaN, Double.NaN)
    }
}

/**
 * Runs the chronological back-tester over locally stored history and aggregates the
 * outcome of stored predictions. Both numbers are reported with their sample size;
 * nothing is extrapolated.
 */
class PerformanceRepository(
    private val db: FootballDatabase,
    private val clock: TimeProvider,
    private val predictionRepository: PredictionRepository,
    private val calibrationRepository: CalibrationRepository,
) {

    suspend fun historicalSources(): List<HistoricalSourceOption> = withContext(Dispatchers.IO) {
        val rows = db.historicalDao().all()
        rows.filterNot { it.source.startsWith("legacy-unverified:") }.groupBy { it.source }.map { (source, matches) ->
            HistoricalSourceOption(
                source = source,
                displayName = sourceName(source),
                matches = matches.size,
                competitions = matches.groupBy { it.competitionId to it.competitionName }
                    .map { (key, list) ->
                        CompetitionPerformanceSource(
                            competitionId = key.first,
                            name = key.second.ifBlank { key.first },
                            matches = list.size,
                            fromEpochMs = list.minOf { it.kickoffEpochMs },
                            toEpochMs = list.maxOf { it.kickoffEpochMs },
                        )
                    }
                    .sortedByDescending { it.matches },
            )
        }.sortedByDescending { it.matches }
    }

    suspend fun runBacktest(
        source: String,
        windowDays: Int = 1400,
        refitEvery: Int = 150,
    ): Outcome<PerformanceReport> = withContext(Dispatchers.Default) {
        val now = clock.nowEpochMs()
        val rows = db.historicalDao().forSource(source, now)
            .filter { it.kickoffEpochMs >= now - windowDays * TimeConstants.DAY_MS }
        if (rows.isEmpty()) {
            return@withContext Outcome.Failure(
                AppError(ErrorCode.EMPTY_RESPONSE, detail = "No historical matches stored for $source"),
            )
        }

        val records = rows.map { row ->
            HistoricalRecord(
                matchId = row.id,
                competitionId = row.competitionId,
                homeTeamId = row.homeTeamId,
                awayTeamId = row.awayTeamId,
                homeGoals = row.homeGoals,
                awayGoals = row.awayGoals,
                kickoffEpochMs = row.kickoffEpochMs,
                oddsHome = row.homeOdds,
                oddsDraw = row.drawOdds,
                oddsAway = row.awayOdds,
                homeXg = row.homeXg,
                awayXg = row.awayXg,
            )
        }

        // Both arms use the production xG blend: the A/B here is the context
        // features, and the backtest must measure the model as it actually runs.
        val report = Backtester(
            BacktestConfig(
                refitEveryMatches = refitEvery,
                contextFeaturesEnabled = true,
                xgBlendWeight = RatingsRepository.PRODUCTION_XG_BLEND_WEIGHT,
            ),
        ).run(records)
        val baselineReport = Backtester(
            BacktestConfig(
                refitEveryMatches = refitEvery,
                contextFeaturesEnabled = false,
                xgBlendWeight = RatingsRepository.PRODUCTION_XG_BLEND_WEIGHT,
            ),
        ).run(records)
        if (report.matchesEvaluated == 0) {
            return@withContext Outcome.Failure(
                AppError(
                    ErrorCode.EMPTY_RESPONSE,
                    detail = "Not enough history to evaluate the model (need at least 60 matches per competition)",
                ),
            )
        }

        val nameById = rows.associate { it.competitionId to it.competitionName.ifBlank { it.competitionId } }
        val byCompetition = report.byCompetition.map { (competitionId, summary) ->
            CompetitionPerformance(
                competitionId = competitionId,
                competitionName = nameById[competitionId] ?: competitionId,
                matches = summary.count,
                brier = summary.brier,
                logLoss = summary.logLoss,
                accuracy = summary.accuracy,
                baseRateBrier = report.byCompetitionBaseRate[competitionId] ?: Double.NaN,
            )
        }.sortedByDescending { it.matches }

        Outcome.Success(
            PerformanceReport(
                matchesEvaluated = report.matchesEvaluated,
                brier = report.model.brier,
                logLoss = report.model.logLoss,
                accuracy = report.model.accuracy,
                baseRateBrier = report.baseRate,
                marketBrier = report.market?.brier,
                pairedModelBrier = report.modelOnMarketSubset?.brier,
                unalignedBrier = report.unalignedOnMarketSubset?.brier,
                marketSampleCount = report.market?.count ?: 0,
                over25Brier = report.over25.brier,
                bttsBrier = report.btts.brier,
                calibration = report.calibration.map {
                    CalibrationPoint(
                        lowerBound = it.lowerBound,
                        upperBound = it.upperBound,
                        count = it.count,
                        meanPredicted = it.meanPredicted,
                        observedFrequency = it.observedFrequency,
                    )
                },
                expectedCalibrationError = report.expectedCalibrationError,
                byCompetition = byCompetition.associateBy { it.competitionId },
                periodStartEpochMs = report.periodStartEpochMs,
                periodEndEpochMs = report.periodEndEpochMs,
                generatedAtEpochMs = now,
                baselineMatchesEvaluated = baselineReport.matchesEvaluated,
                baselineBrier = baselineReport.model.brier,
                baselineLogLoss = baselineReport.model.logLoss,
                baselineAccuracy = baselineReport.model.accuracy,
            ),
        )
    }

    suspend fun storedPredictionStats(): StoredPredictionStats = withContext(Dispatchers.IO) {
        val resolved = db.predictionDao().evaluatedPreMatch(PredictionRepository.MODEL_VERSION)
        val lateSnapshots = db.predictionDao().lateForecastCount(PredictionRepository.MODEL_VERSION)
        if (resolved.isEmpty()) return@withContext StoredPredictionStats.EMPTY.copy(lateSnapshots = lateSnapshots)
        val matches = resolved.map { it.matchId }.distinct().chunked(400)
            .flatMap { db.matchDao().byIds(it) }.associateBy { it.id }
        val competitions = matches.values.map { it.competitionId }.distinct().chunked(400)
            .flatMap { db.competitionDao().byIds(it) }.associateBy { it.id }
        val observations = resolved.mapNotNull { entity ->
            val match = matches[entity.matchId] ?: return@mapNotNull null
            val home = entity.actualHomeGoals ?: return@mapNotNull null
            val away = entity.actualAwayGoals ?: return@mapNotNull null
            StoredForecastObservation(
                home = entity.homeWin, draw = entity.draw, away = entity.awayWin,
                homeGoals = home, awayGoals = away, savedCorrect = entity.outcomeCorrect,
                createdAt = entity.createdAtEpochMs, kickoff = match.kickoffEpochMs,
                competitionId = match.competitionId,
                competitionName = competitions[match.competitionId]?.name ?: match.competitionId,
            ).takeIf { it.valid() }
        }
        if (observations.isEmpty()) return@withContext StoredPredictionStats.EMPTY.copy(lateSnapshots = lateSnapshots)
        val pairs = resolved.mapNotNull { entity ->
            val match = matches[entity.matchId] ?: return@mapNotNull null
            val late = db.predictionDao().lateForecast(entity.id) ?: return@mapNotNull null
            if (late.kickoffEpochMs != match.kickoffEpochMs) return@mapNotNull null
            val home = entity.actualHomeGoals ?: return@mapNotNull null
            val away = entity.actualAwayGoals ?: return@mapNotNull null
            val early = StoredForecastObservation(entity.homeWin, entity.draw, entity.awayWin, home, away,
                entity.outcomeCorrect, entity.createdAtEpochMs, match.kickoffEpochMs, match.competitionId, "")
            val probabilities = listOf(late.homeWin, late.draw, late.awayWin)
            val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: return@mapNotNull null
            val actual = if (home > away) 0 else if (home == away) 1 else 2
            val later = StoredForecastObservation(late.homeWin, late.draw, late.awayWin, home, away,
                best == actual, late.createdAtEpochMs, late.kickoffEpochMs, match.competitionId, "")
            early to later
        }
        val samples = observations.map { row ->
            OutcomeSample(row.home, row.draw, row.away, when {
                row.homeGoals > row.awayGoals -> MatchOutcome.HOME_WIN
                row.homeGoals == row.awayGoals -> MatchOutcome.DRAW
                else -> MatchOutcome.AWAY_WIN
            })
        }
        val correct = observations.count { it.savedCorrect == true }
        StoredPredictionStats(
            resolved = observations.size,
            correct = correct,
            brier = Metrics.brier1x2(samples),
            logLoss = Metrics.logLoss1x2(samples),
            accuracy = correct.toDouble() / observations.size,
            audit = StoredForecastAudit.from(observations),
            paired = PairedForecastAudit.from(pairs),
            lateSnapshots = lateSnapshots,
        )
    }

    /** Resolves stored predictions for finished matches, then returns the fresh stats. */
    suspend fun refreshStoredStats(): StoredPredictionStats {
        predictionRepository.resolveFinishedPredictions()
        return storedPredictionStats()
    }

    /**
     * Out-of-sample calibration study for the stored forecasts, split by competition
     * class so a club-league calibration is never reported as if it covered
     * international football.
     */
    suspend fun calibrationStudy(
        competitionClass: String,
        version: String = PredictionRepository.MODEL_VERSION,
    ): CalibrationStudyResult = withContext(Dispatchers.Default) {
        val samples = withContext(Dispatchers.IO) {
            // Newest first from the query, oldest first for the expanding window below.
            db.predictionDao().resolvedForClass(version, competitionClass, 3000).asReversed()
        }.mapNotNull { it.toRawCalibrationSample() }
        if (samples.size < CalibrationStudy.Config().minimumWarmupSamples) {
            return@withContext CalibrationStudyResult(
                competitionClass = competitionClass,
                sampleCount = samples.size,
                adopted = false,
                method = "NONE",
                note = "NOT_ENOUGH_SAMPLES",
            )
        }
        val study = CalibrationStudy.run(samples)
        CalibrationStudyResult(
            competitionClass = competitionClass,
            sampleCount = study.evaluatedMatches,
            adopted = study.adoptedFolds > 0,
            method = study.folds.firstOrNull { it.method != "NONE" }?.method ?: "NONE",
            rawLogLoss = study.rawLogLoss,
            calibratedLogLoss = study.calibratedLogLoss,
            rawBrier = study.rawBrier,
            calibratedBrier = study.calibratedBrier,
            rawCalibrationError = study.rawCalibrationError,
            calibratedCalibrationError = study.calibratedCalibrationError,
            note = study.skippedReason,
        )
    }

    /** Which sample size the current on-device calibration was fitted on. */
    suspend fun calibrationSampleCount(competitionClass: String): Int = withContext(Dispatchers.IO) {
        calibrationRepository.resolvedCount(competitionClass, PredictionRepository.MODEL_VERSION)
    }

    suspend fun activeCalibration(competitionClass: String): CalibrationModel =
        calibrationRepository.modelFor(competitionClass, PredictionRepository.MODEL_VERSION)

    suspend fun personalLogLossReference(): Double = ln(3.0)

    private fun sourceName(source: String): String = when (source) {
        "football-data-co-uk" -> "football-data.co.uk (CSV history)"
        "api-football" -> "API-Football"
        "football-data-org" -> "football-data.org"
        else -> source
    }
}
