package com.footballai.app.data.repository

import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.SyncMetaEntity
import com.footballai.app.data.local.mapper.Ids
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.domain.model.QualityLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

@Serializable
data class DailyAnalysisReport(
    val attemptedAt: Long,
    val coveredLeagues: Int = 0,
    val fixturesReceived: Int = 0,
    val candidates: Int = 0,
    val predicted: Int = 0,
    val alreadySaved: Int = 0,
    val insufficientHistory: Int = 0,
    val failed: Int = 0,
    val resolved: Int = 0,
    val errors: List<String> = emptyList(),
)

/** Keyless daily pipeline. No live endpoints, no cross-provider identity guessing. */
class DailyAnalysisRepository(
    private val db: FootballDatabase,
    private val registry: ProviderRegistry,
    private val sync: SyncRepository,
    private val ratings: RatingsRepository,
    private val predictions: PredictionRepository,
    private val clock: TimeProvider,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val reports = db.syncMetaDao().observeAll().map { rows ->
        rows.firstOrNull { it.key == REPORT_KEY }?.value?.let { decode(it) }
    }

    private fun decode(value: String): DailyAnalysisReport? =
        runCatching { json.decodeFromString<DailyAnalysisReport>(value) }.getOrNull()

    suspend fun clearLocalData() = mutex.withLock {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        ratings.invalidate()
    }

    suspend fun needsRefresh(): Boolean {
        val last = db.syncMetaDao().get(REPORT_KEY)?.let { decode(it) } ?: return true
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(last.attemptedAt).atZone(zone).toLocalDate() !=
            Instant.ofEpochMilli(clock.nowEpochMs()).atZone(zone).toLocalDate()
    }

    suspend fun run(onProgress: (Int, Int) -> Unit = { _, _ -> }): DailyAnalysisReport = mutex.withLock {
        withContext(Dispatchers.Default) {
            val now = clock.nowEpochMs()
            val source = registry.activeProviderId() ?: FootballDataCoUkProvider.PROVIDER_ID
            val leagues = registry.historical.availableCompetitions()
            val fixtureKeys = if (source == FootballDataCoUkProvider.PROVIDER_ID) leagues.map { it.key }
                else HistoryBridge.leagues.keys.toList() + listOf("BSA", "CL", "EC", "WC")
            var report = DailyAnalysisReport(now, coveredLeagues = fixtureKeys.size)
            val errors = mutableListOf<String>()
            try {
                when (val result = sync.syncCompetitions(source)) {
                    is Outcome.Failure -> errors += result.error.code.name
                    is Outcome.Success -> Unit
                }
                when (val result = sync.syncFixtures(source, competitionKeys = fixtureKeys,
                    daysBack = if (source == "football-data-org") 7 else 0, daysAhead = 10, allowFallback = false, onProgress = onProgress)) {
                    is Outcome.Failure -> errors += result.error.code.name
                    is Outcome.Success -> {
                        report = report.copy(fixturesReceived = result.value.fixtures)
                        errors += result.value.errors
                    }
                }
                val end = DailyAnalysisPolicy.endOfHorizon(now, ZoneId.systemDefault())
                val candidates = db.matchDao().scheduledBetween(now + 1, end).filter { it.providerId == source && it.lastSyncedAt >= now }
                report = report.copy(candidates = candidates.size)
                // Finished results are pulled for pending cached fixtures too, even on
                // a day with no new fixtures. Only relevant league-season files are read.
                val pending = db.matchDao().pendingResults(source, now)
                val leagueSeasons = (candidates + pending).mapNotNull { m ->
                    m.season?.let { season ->
                        val key = m.competitionId.substringAfterLast(':')
                        val csvKey = if (source == FootballDataCoUkProvider.PROVIDER_ID) key else HistoryBridge.leagues[key]
                        csvKey?.let { it to season }
                    }
                }.distinct()
                onProgress(0, leagueSeasons.size + candidates.size)
                var done = 0
                for ((league, season) in leagueSeasons) {
                    val bootstrapKey = "daily-history-v2:$league:$season"
                    // Refresh the current season every day. Fetch the two completed
                    // seasons once per league; football-data's GitHub mirror is used
                    // only when the primary host is unreachable and covers that league.
                    val seasons = if (db.syncMetaDao().get(bootstrapKey)?.value == "ready") {
                        listOf(season)
                    } else {
                        listOf(season, season - 1, season - 2)
                    }
                    when (val result = sync.importCsvHistory(listOf(league), seasons)) {
                        is Outcome.Failure -> errors += "$league: ${result.error.code}"
                        is Outcome.Success -> {
                            errors += result.value.errors
                            val count = ratings.matchCount(FootballDataCoUkProvider.PROVIDER_ID, Ids.competition(FootballDataCoUkProvider.PROVIDER_ID, league))
                            if (count >= 40) {
                                db.syncMetaDao().put(SyncMetaEntity(bootstrapKey, "ready", now))
                            }
                        }
                    }
                    onProgress(++done, leagueSeasons.size + candidates.size)
                }
                for (match in candidates) {
                    if (db.predictionDao().firstPreMatch(match.id, PredictionRepository.MODEL_VERSION) != null) {
                        report = report.copy(alreadySaved = report.alreadySaved + 1)
                    } else {
                        val asOf = clock.nowEpochMs()
                        val strengths = ratings.strengths(source, match.competitionId, match.homeTeamId, match.awayTeamId, asOf)
                        val count = ratings.matchCount(source, match.competitionId)
                        if (!DailyAnalysisPolicy.hasEnoughHistory(count, strengths.first.matchesUsed, strengths.second.matchesUsed)) {
                            report = report.copy(insufficientHistory = report.insufficientHistory + 1)
                        } else {
                            when (val result = predictions.predict(match.id)) {
                                is Outcome.Failure -> report = if (result.error.code == com.footballai.app.core.ErrorCode.INSUFFICIENT_HISTORY)
                                    report.copy(insufficientHistory = report.insufficientHistory + 1)
                                    else report.copy(failed = report.failed + 1)
                                is Outcome.Success -> report = report.copy(predicted = report.predicted + 1)
                            }
                        }
                    }
                    onProgress(++done, leagueSeasons.size + candidates.size)
                }
                report = report.copy(resolved = predictions.resolveFinishedPredictions())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errors += "REQUEST_FAILED"
            }
            report = report.copy(errors = errors.distinct())
            db.syncMetaDao().put(SyncMetaEntity(REPORT_KEY, json.encodeToString(report), clock.nowEpochMs()))
            report
        }
    }

    companion object { private const val REPORT_KEY = "daily-analysis-report-v1" }
}

object DailyAnalysisPolicy {
    fun endOfHorizon(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone)
        .toLocalDate().plusDays(11).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    fun endOfTomorrow(now: Long, zone: ZoneId): Long = Instant.ofEpochMilli(now).atZone(zone)
        .toLocalDate().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    fun hasEnoughHistory(league: Int, home: Int, away: Int) = league >= 40 && home >= 5 && away >= 5

    /** Descriptive pick, NOT a calibrated confidence or betting recommendation. */
    fun pick(home: Double, draw: Double, away: Double, quality: QualityLevel): String {
        if (kotlin.math.abs(home + draw + away - 1.0) > 1e-6 || quality == QualityLevel.INSUFFICIENT || listOf(home, draw, away).any { !it.isFinite() || it !in 0.0..1.0 }) return "INSUFFICIENT"
        if (quality == QualityLevel.LOW) return "UNCLEAR"
        val sorted = listOf("HOME" to home, "DRAW" to draw, "AWAY" to away).sortedByDescending { it.second }
        if (sorted[0].second - sorted[1].second + 1e-9 < 0.08) return "UNCLEAR"
        return sorted[0].first
    }
}
