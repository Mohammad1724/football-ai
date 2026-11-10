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
data class StoredPredictionStats(
    val resolved: Int,
    val correct: Int,
    val brier: Double,
    val logLoss: Double,
    val accuracy: Double,
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
            )
        }

        val report = Backtester(BacktestConfig(refitEveryMatches = refitEvery)).run(records)
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
            ),
        )
    }

    suspend fun storedPredictionStats(): StoredPredictionStats = withContext(Dispatchers.IO) {
        val resolved = db.predictionDao().evaluatedPreMatch(PredictionRepository.MODEL_VERSION)
        if (resolved.isEmpty()) return@withContext StoredPredictionStats.EMPTY
        val samples = resolved.map { entity ->
            OutcomeSample(
                homeWin = entity.homeWin,
                draw = entity.draw,
                awayWin = entity.awayWin,
                actual = when {
                    (entity.actualHomeGoals ?: 0) > (entity.actualAwayGoals ?: 0) -> MatchOutcome.HOME_WIN
                    (entity.actualHomeGoals ?: 0) == (entity.actualAwayGoals ?: 0) -> MatchOutcome.DRAW
                    else -> MatchOutcome.AWAY_WIN
                },
            )
        }
        val correct = resolved.count { it.outcomeCorrect == true }
        val brier = Metrics.brier1x2(samples)
        val logLoss = Metrics.logLoss1x2(samples)
        StoredPredictionStats(
            resolved = resolved.size,
            correct = correct,
            brier = brier,
            logLoss = logLoss,
            accuracy = correct.toDouble() / resolved.size,
        )
    }

    /** Resolves stored predictions for finished matches, then returns the fresh stats. */
    suspend fun refreshStoredStats(): StoredPredictionStats {
        predictionRepository.resolveFinishedPredictions()
        return storedPredictionStats()
    }

    suspend fun personalLogLossReference(): Double = ln(3.0)

    private fun sourceName(source: String): String = when (source) {
        "football-data-co-uk" -> "football-data.co.uk (CSV history)"
        "api-football" -> "API-Football"
        "football-data-org" -> "football-data.org"
        else -> source
    }
}
