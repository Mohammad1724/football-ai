package com.footballai.app.data.repository

import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.SyncMetaEntity
import com.footballai.app.data.local.mapper.Ids
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.sync.SyncTimingRecorder
import com.footballai.app.domain.model.QualityLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    val gradingCompleted: Boolean = false,
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
        predictions.invalidateCalibration()
    }

    suspend fun needsRefresh(): Boolean {
        val last = db.syncMetaDao().get(REPORT_KEY)?.let { decode(it) } ?: return true
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(last.attemptedAt).atZone(zone).toLocalDate() !=
            Instant.ofEpochMilli(clock.nowEpochMs()).atZone(zone).toLocalDate()
    }

    suspend fun run(
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        timing: SyncTimingRecorder? = null,
    ): DailyAnalysisReport = mutex.withLock {
        withContext(Dispatchers.Default) {
            val now = clock.nowEpochMs()
            val source = registry.activeProviderId() ?: FootballDataCoUkProvider.PROVIDER_ID
            val leagues = registry.historical.availableCompetitions()
            val fixtureKeys = if (source == FootballDataCoUkProvider.PROVIDER_ID) leagues.map { it.key }
                else HistoryBridge.leagues.keys.toList() + listOf("BSA", "CL", "EC", "WC")
            var report = DailyAnalysisReport(now, coveredLeagues = fixtureKeys.size)
            val errors: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
            // A full pass already ran today: the fixture list it produced is the day's
            // fixture list, and re-downloading every league again only makes the refresh
            // button slow. Results still come in, because this pass reads exactly the
            // leagues that are waiting for one. A pass that reported errors is repeated
            // in full, so a bad network morning does not leave the day half-empty.
            val previousReport = db.syncMetaDao().get(REPORT_KEY)?.let { decode(it) }
            val lastFullPassAt = db.syncMetaDao().entity(FULL_PASS_KEY)?.updatedAt ?: 0L
            val zone = ZoneId.systemDefault()
            val fullPassToday = lastFullPassAt > 0 &&
                Instant.ofEpochMilli(lastFullPassAt).atZone(zone).toLocalDate() ==
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val fastPass = fullPassToday && previousReport?.errors?.isEmpty() == true
            try {
                // Progress is reported from several import threads at once, so the
                // counter has to be safe to increment from any of them.
                val progressDone = java.util.concurrent.atomic.AtomicInteger(0)
                var total = 0
                fun step() {
                    onProgress(progressDone.incrementAndGet(), total)
                }

                // Results first: a match that has finished is what the user is waiting
                // for when pressing refresh, and those files do not depend on the fixture
                // sweep below, so waiting for the sweep first only delays the answer.
                val pendingSeasons = csvSeasons(db.matchDao().pendingResults(source, now), source)
                total += pendingSeasons.size
                onProgress(progressDone.get(), total)
                errors.recordErrors(timed(timing, "results") { importAll(pendingSeasons, now) { step() } })

                if (!fastPass) {
                    timed(timing, "fixtures") {
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
                    }
                    db.syncMetaDao().put(SyncMetaEntity(FULL_PASS_KEY, "done", now))
                }

                // The Iranian feed supplements, rather than replaces, the daily source.
                when (val iran = timed(timing, "iran") { sync.syncIranCompetitions() }) {
                    is Outcome.Failure -> errors += "Varzesh3: ${iran.error.code}"
                    is Outcome.Success -> {
                        report = report.copy(coveredLeagues = report.coveredLeagues + 2,
                            fixturesReceived = report.fixturesReceived + iran.value.fixtures)
                        errors += iran.value.errors
                    }
                }
                val end = DailyAnalysisPolicy.endOfHorizon(now, zone)
                val candidates = db.matchDao().scheduledBetween(now + 1, end)
                    .filter { (it.providerId == source || it.providerId == com.footballai.app.data.remote.Varzesh3Provider.PROVIDER_ID) && it.lastSyncedAt >= now }
                report = report.copy(candidates = candidates.size)

                val candidateSeasons = csvSeasons(candidates.filter { it.providerId == source }, source).filter { it !in pendingSeasons }
                total += candidateSeasons.size + candidates.size
                onProgress(progressDone.get(), total)
                errors.recordErrors(timed(timing, "history") { importAll(candidateSeasons, now) { step() } })

                timed(timing, "predictions") {
                    for (match in candidates) {
                        if (db.predictionDao().firstPreMatch(match.id, PredictionRepository.MODEL_VERSION) != null) {
                            report = report.copy(alreadySaved = report.alreadySaved + 1)
                        } else {
                            val asOf = clock.nowEpochMs()
                            val strengths = ratings.strengths(match.providerId, match.competitionId, match.homeTeamId, match.awayTeamId, asOf)
                            val count = ratings.matchCount(match.providerId, match.competitionId)
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
                        step()
                    }
                }
                val resolved = timed(timing, "grading") { predictions.resolveFinishedPredictions() }
                report = report.copy(resolved = resolved, gradingCompleted = true)
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

    private suspend fun <T> timed(timing: SyncTimingRecorder?, phase: String, block: suspend () -> T): T =
        if (timing == null) block() else timing.measure(phase, block)

    /** The league-season files that can contain the result of these matches. */
    private fun csvSeasons(matches: List<com.footballai.app.data.local.entity.MatchEntity>, source: String): List<Pair<String, Int>> =
        matches.mapNotNull { match ->
            match.season?.let { season ->
                val key = match.competitionId.substringAfterLast(':')
                val csvKey = if (source == FootballDataCoUkProvider.PROVIDER_ID) key else HistoryBridge.leagues[key]
                csvKey?.let { it to season }
            }
        }.distinct()

    /**
     * Reads several league files at once. Each file is independent, so three at a time
     * turns three round trips into one; the quota guard still enforces the source's rate.
     */
    private suspend fun importAll(
        pairs: List<Pair<String, Int>>,
        now: Long,
        onEach: suspend () -> Unit = {},
    ): List<Pair<String, Outcome<SyncSummary>>> = coroutineScope {
        val permits = Semaphore(PARALLEL_HISTORY_IMPORTS)
        pairs.map { (league, season) ->
            async(Dispatchers.IO) {
                val result = permits.withPermit { importLeague(league, season, now) }
                onEach()
                league to result
            }
        }.awaitAll()
    }

    /** Folds import failures into the report's error list, in a stable order. */
    private fun MutableList<String>.recordErrors(outcomes: List<Pair<String, Outcome<SyncSummary>>>) {
        for ((league, result) in outcomes) {
            when (result) {
                is Outcome.Failure -> add("$league: ${result.error.code}")
                is Outcome.Success -> addAll(result.value.errors)
            }
        }
    }

    /**
     * Refreshes the history of one league. The current season is read every day; the two
     * completed ones are read once per league, which is what the marker records.
     */
    private suspend fun importLeague(league: String, season: Int, now: Long): Outcome<SyncSummary> {
        val bootstrapKey = "daily-history-v2:$league:$season"
        val seasons = if (db.syncMetaDao().get(bootstrapKey) == "ready") {
            listOf(season)
        } else {
            listOf(season, season - 1, season - 2)
        }
        val result = sync.importCsvHistory(listOf(league), seasons)
        if (result is Outcome.Success) {
            val count = ratings.matchCount(
                FootballDataCoUkProvider.PROVIDER_ID,
                Ids.competition(FootballDataCoUkProvider.PROVIDER_ID, league),
            )
            if (count >= 40) db.syncMetaDao().put(SyncMetaEntity(bootstrapKey, "ready", now))
        }
        return result
    }

    companion object {
        private const val REPORT_KEY = "daily-analysis-report-v1"

        /** When the fixture sweep last ran in full; a refresh on the same day may skip it. */
        private const val FULL_PASS_KEY = "daily-full-pass-v1"

        /** Independent CSV files read at the same time; enough to hide the round trips. */
        private const val PARALLEL_HISTORY_IMPORTS = 3
    }
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
