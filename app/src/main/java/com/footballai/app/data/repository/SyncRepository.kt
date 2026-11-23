package com.footballai.app.data.repository

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.CompetitionEntity
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.local.entity.LineupPlayerEntity
import com.footballai.app.data.local.entity.MatchEventEntity
import com.footballai.app.data.local.entity.MatchStatisticsEntity
import com.footballai.app.data.local.entity.StandingEntity
import com.footballai.app.data.local.entity.SyncMetaEntity
import com.footballai.app.data.local.entity.TeamEntity
import com.footballai.app.data.local.mapper.Ids
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.data.local.mapper.toEntity
import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.RemoteHistoryMatch
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.app.domain.catalog.ClassificationSource
import com.footballai.app.domain.catalog.CompetitionCatalog
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.StatisticType
import androidx.room.withTransaction
import com.footballai.app.data.remote.CrestQueuePolicy
import com.footballai.app.data.remote.EspnOpenProvider
import com.footballai.app.data.remote.CsvFixtureIdentity
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.engine.model.TeamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class SyncSummary(
    val competitions: Int = 0,
    val fixtures: Int = 0,
    val live: Int = 0,
    val details: Int = 0,
    val historical: Int = 0,
    val standings: Int = 0,
    val requestsEstimated: Int = 0,
    val errors: List<String> = emptyList(),
    /** Set when the configured provider returned nothing and a keyless source was used. */
    val fallbackProviderId: String? = null,
) {
    operator fun plus(other: SyncSummary): SyncSummary = SyncSummary(
        competitions = competitions + other.competitions,
        fixtures = fixtures + other.fixtures,
        live = live + other.live,
        details = details + other.details,
        historical = historical + other.historical,
        standings = standings + other.standings,
        requestsEstimated = requestsEstimated + other.requestsEstimated,
        errors = errors + other.errors,
    )
}

private fun AppError.syncDescription(): String = buildString {
    append(code.name)
    httpStatus?.let { append(" (HTTP ").append(it).append(')') }
    detail?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(160)) }
}

/**
 * Writes provider payloads into the local database.
 *
 * Every write is idempotent (upserts keyed by provider identifiers), so a failed or
 * repeated sync can never duplicate a match. Finished matches are appended to the
 * historical table, which is what the rating models train on.
 */
class SyncRepository(
    private val db: FootballDatabase,
    private val registry: ProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val ratingsRepository: RatingsRepository,
    private val clock: TimeProvider,
    private val sportsDbCrests: com.footballai.app.data.remote.SportsDbCrests,
    private val leagueCrestIndex: com.footballai.app.data.remote.LeagueCrestIndex,
    private val xgProvider: com.footballai.app.data.remote.XgHistoryProvider,
    private val availabilityProvider: com.footballai.app.data.remote.AvailabilityHistoryProvider,
) {

    private val crestBackfillMutex = Mutex()

    suspend fun syncCompetitions(providerId: String): Outcome<Int> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        return when (val result = provider.competitions()) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val settings = settingsRepository.current()
                val now = clock.nowEpochMs()
                val existing = db.competitionDao().allOnce().associateBy { it.id }
                val entities = result.value.map { competition ->
                    val id = Ids.competition(providerId, competition.providerKey)
                    val previous = existing[id]
                    val classification = CompetitionCatalog.classify(
                        providerId = providerId,
                        providerKey = competition.providerKey,
                        name = competition.name,
                        country = competition.country,
                    )
                    CompetitionEntity(
                        id = id,
                        providerId = providerId,
                        providerKey = competition.providerKey,
                        name = competition.name,
                        country = competition.country,
                        emblemUrl = competition.emblemUrl,
                        season = competition.currentSeason,
                        hasFullCoverage = provider.info.capabilities.liveScores,
                        isFollowed = previous?.isFollowed
                            ?: settings.followedCompetitionKeys.contains(competition.providerKey),
                        updatedAt = now,
                        competitionClass = classification.label(),
                        gender = classification.gender.name,
                        classificationSource = classification.source.name,
                    )
                }
                db.competitionDao().upsertAll(entities)
                Outcome.Success(entities.size)
            }
        }
    }

    suspend fun syncFixtures(
        providerId: String,
        competitionKeys: List<String>? = null,
        daysBack: Int = 7,
        daysAhead: Int = 10,
        allowFallback: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome<SyncSummary> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        val now = clock.nowEpochMs()
        val from = now - daysBack * TimeConstants.DAY_MS
        val to = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().plusDays(daysAhead.toLong() + 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

        var summary = syncWithProvider(provider, competitionKeys, from, to, now, onProgress)
        var fallbackProviderId: String? = null

        // An authenticated provider can legitimately return nothing (plan restrictions,
        // exhausted quota, a rejected season). A keyless provider cannot: its empty
        // answer means "there is nothing to show", and adding another source's fixtures
        // would mislabel their origin.
        if (allowFallback && provider.info.requiresApiKey && summary.fixtures == 0) {
            val keyless = registry.keylessProvider(excludeProviderId = provider.info.id)
            if (keyless != null) {
                val fallback = syncWithProvider(keyless, null, from, to, now) { _, _ -> }
                if (fallback.fixtures > 0) {
                    fallbackProviderId = keyless.info.id
                    summary = SyncSummary(
                        competitions = summary.competitions + fallback.competitions,
                        fixtures = fallback.fixtures,
                        historical = summary.historical + fallback.historical,
                        standings = summary.standings + fallback.standings,
                        requestsEstimated = summary.requestsEstimated + fallback.requestsEstimated,
                        errors = summary.errors + fallback.errors,
                    )
                    appendFinishedMatches(keyless.info.id)
                } else {
                    summary += fallback
                }
            }
        }

        if (summary.fixtures > 0 || summary.errors.isEmpty()) {
            settingsRepository.setLastSync(now)
        }
        val appended = appendFinishedMatches(provider.info.id)
        if (allowFallback) summary += syncPendingCsvResults()
        ratingsRepository.invalidate()
        return Outcome.Success(
            summary.copy(
                historical = summary.historical + appended,
                fallbackProviderId = fallbackProviderId,
            ),
        )
    }

    /**
     * One provider pass: decides which competitions to fetch, then persists whatever it
     * returns. Followed competition keys belong to a specific provider, so a list that
     * belongs to a different source is replaced by the provider's own defaults instead of
     * silently producing an empty sync.
     */
    private suspend fun syncWithProvider(
        provider: FootballDataProvider,
        competitionKeys: List<String>?,
        fromEpochMs: Long,
        toEpochMs: Long,
        now: Long,
        onProgress: (done: Int, total: Int) -> Unit,
    ): SyncSummary {
        val known = db.competitionDao().allOnce()
            .filter { it.providerId == provider.info.id }
            .map { it.providerKey }
        val followed = settingsRepository.current().followedCompetitionKeys.toList()
        val requested = competitionKeys?.takeIf { it.isNotEmpty() } ?: followed
        val matched = requested.filter { it in known }
        val keys = when {
            matched.isNotEmpty() -> matched
            provider.defaultCompetitionKeys.isNotEmpty() -> provider.defaultCompetitionKeys
            known.isNotEmpty() -> known
            else -> requested
        }
        if (keys.isEmpty()) {
            return SyncSummary(errors = listOf("NO_COMPETITIONS"))
        }

        var summary = SyncSummary(requestsEstimated = keys.size)
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        var lastErrorCode: ErrorCode? = null
        var repeatedFailures = 0
        val summaryLock = Mutex()
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        val nextKey = java.util.concurrent.atomic.AtomicInteger(-1)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        onProgress(0, keys.size)

        // Competitions are independent documents, so a few are fetched at a time instead
        // of strictly one after another. The quota guard still enforces the provider's
        // average rate; this removes the idle gap between requests, which is what made a
        // refresh feel like it had stalled.
        coroutineScope {
            repeat(minOf(keys.size, PARALLEL_SYNCS)) {
                launch(Dispatchers.IO) {
                    while (!stop.get()) {
                        val index = nextKey.incrementAndGet()
                        if (index >= keys.size) break
                        val key = keys[index]
                        when (val result = provider.matches(key, null, fromEpochMs, toEpochMs)) {
                            is Outcome.Failure -> {
                                errors.add("${provider.info.displayName}/$key: ${result.error.syncDescription()}")
                                summaryLock.withLock {
                                    repeatedFailures =
                                        if (result.error.code == lastErrorCode) repeatedFailures + 1 else 1
                                    lastErrorCode = result.error.code
                                    // A plan restriction fails identically for every
                                    // competition, so stop instead of spending minutes and
                                    // quota on a provider that cannot serve this request.
                                    if (result.error.code in FATAL_CODES ||
                                        repeatedFailures >= MAX_REPEATED_FAILURES
                                    ) {
                                        stop.set(true)
                                    }
                                }
                            }
                            is Outcome.Success -> {
                                summaryLock.withLock {
                                    repeatedFailures = 0
                                    lastErrorCode = null
                                }
                                // Written as it arrives, so the list fills while the
                                // sync is still running.
                                val written = persistMatches(provider.info.id, key, result.value, now)
                                summaryLock.withLock { summary = summary + SyncSummary(fixtures = written) }
                            }
                        }
                        onProgress(finished.incrementAndGet(), keys.size)
                    }
                }
            }
        }
        return summary.copy(errors = errors.toList())
    }

    /** Refreshes the Iranian leagues alongside the selected source, never replacing it. */
    suspend fun syncIranCompetitions(): Outcome<SyncSummary> {
        val id = com.footballai.app.data.remote.Varzesh3Provider.PROVIDER_ID
        val provider = registry.provider(id) ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND))
        when (val competitions = syncCompetitions(id)) {
            is Outcome.Failure -> return competitions
            is Outcome.Success -> Unit
        }
        val main = when (val fixtures = syncFixtures(id, com.footballai.app.data.remote.Varzesh3Provider.KEYS,
            daysBack = 420, daysAhead = 14, allowFallback = false)) {
            is Outcome.Failure -> return fixtures
            is Outcome.Success -> fixtures.value
        }
        var extra = SyncSummary()
        val now = clock.nowEpochMs()
        val from = now - HISTORY_LOOKBACK_DAYS * TimeConstants.DAY_MS
        for (key in com.footballai.app.data.remote.Varzesh3Provider.KEYS) {
            val competition = db.competitionDao().byId(Ids.competition(id, key)) ?: continue
            val year = competition.season ?: continue
            val marker = "varzesh3-history:$key:${year - 1}"
            if (db.syncMetaDao().get(marker) == "ready") continue
            when (val history = provider.matches(key, year - 1, from, now)) {
                is Outcome.Failure -> extra += SyncSummary(errors = listOf("Varzesh3/$key history: ${history.error.syncDescription()}"))
                is Outcome.Success -> {
                    val written = persistMatches(id, key, history.value, now)
                    extra += SyncSummary(historical = written)
                    db.syncMetaDao().put(SyncMetaEntity(marker, "ready", now))
                }
            }
        }
        val appended = appendFinishedMatches(id)
        ratingsRepository.invalidate()
        return Outcome.Success(main + extra + SyncSummary(historical = appended))
    }

    suspend fun syncLive(providerId: String): Outcome<SyncSummary> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        if (!provider.info.capabilities.liveScores) {
            return Outcome.Failure(
                AppError(
                    ErrorCode.REQUEST_FAILED,
                    detail = "${provider.info.displayName} does not provide live scores",
                ),
            )
        }
        return when (val result = provider.liveMatches()) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val settings = settingsRepository.current()
                val followed = settings.followedCompetitionKeys
                val now = clock.nowEpochMs()
                var written = 0
                val grouped = result.value
                    .filter { followed.isEmpty() || followed.contains(it.competitionKey) }
                    .groupBy { it.competitionKey }
                for ((competitionKey, matches) in grouped) {
                    written += persistMatches(providerId, competitionKey, matches, now)
                }
                if (written > 0) {
                    appendFinishedMatches(providerId)
                    ratingsRepository.invalidate()
                }
                Outcome.Success(SyncSummary(live = written, requestsEstimated = 1))
            }
        }
    }

    /**
     * Whether a stored fixture of this source can be re-read on its own. The detail
     * screen uses it to offer live tracking only where it can actually work.
     */
    fun supportsMatchRefresh(providerId: String): Boolean =
        registry.info(providerId) != null

    /** Refreshes a single fixture (one request); used by live tracking. */
    suspend fun refreshMatch(matchId: String): Outcome<SyncSummary> {
        val match = db.matchDao().byId(matchId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown match $matchId"))
        val provider = registry.provider(match.providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider"))
        if (match.providerId == FootballDataCoUkProvider.PROVIDER_ID &&
            match.kickoffEpochMs <= clock.nowEpochMs() && match.season != null) {
            val result = importCsvHistory(listOf(match.competitionId.substringAfterLast(':')), listOf(match.season))
            if (result is Outcome.Failure) return result
            val refreshed = db.matchDao().byId(matchId)
            if (refreshed?.status == MatchStatus.FINISHED.name) return result
            return Outcome.Failure(AppError(ErrorCode.STALE_DATA, detail = "CSV result has not been published yet"))
        }
        // A source that publishes whole matchdays cannot read one fixture directly. The
        // matchday that contains the fixture is the only way to refresh it, so that is
        // what is fetched instead of reporting "not found" to a screen that already has
        // the fixture.
        if (!provider.info.capabilities.singleMatchRefresh) {
            return refreshMatchDay(provider, match)
        }
        return when (val result = provider.match(match.providerKey)) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val remote = result.value
                    ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Fixture not found upstream"))
                val competitionKey = match.competitionId.substringAfterLast(':')
                val written = persistMatches(match.providerId, competitionKey, listOf(remote))
                appendFinishedMatches(match.providerId)
                ratingsRepository.invalidate()
                Outcome.Success(SyncSummary(live = written, requestsEstimated = 1))
            }
        }
    }

    /**
     * Refreshes one fixture through the matchday it belongs to.
     *
     * The open sources publish a whole day per competition, so this is one request for
     * the one day around kickoff. A day outside the source's published window returns
     * nothing to write, which is not an error: the stored row simply stays as it is.
     */
    private suspend fun refreshMatchDay(
        provider: FootballDataProvider,
        match: com.footballai.app.data.local.entity.MatchEntity,
    ): Outcome<SyncSummary> {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(match.kickoffEpochMs).atZone(zone).toLocalDate()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val competitionKey = match.competitionId.substringAfterLast(':')
        return when (val result = provider.matches(competitionKey, match.season, from, to)) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val written = persistMatches(match.providerId, competitionKey, result.value)
                if (written > 0) {
                    appendFinishedMatches(match.providerId)
                    ratingsRepository.invalidate()
                }
                Outcome.Success(SyncSummary(live = written, requestsEstimated = 1))
            }
        }
    }

    suspend fun syncMatchDetails(matchId: String, force: Boolean = false): Outcome<SyncSummary> {
        val match = db.matchDao().byId(matchId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown match $matchId"))
        val provider = registry.provider(match.providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider"))
        if (!provider.info.capabilities.matchStatistics && !provider.info.capabilities.events) {
            return Outcome.Success(SyncSummary())
        }

        val now = clock.nowEpochMs()
        val fresh = match.statisticsSyncedAt != null && now - match.statisticsSyncedAt < DETAILS_TTL_MS
        if (fresh && !force) return Outcome.Success(SyncSummary())

        var summary = SyncSummary(requestsEstimated = 3)
        val errors = mutableListOf<String>()

        when (val result = provider.matchStatistics(match.providerKey)) {
            is Outcome.Failure -> errors.add(result.error.code.name)
            is Outcome.Success -> {
                val home = result.value.firstOrNull { it.teamKey == providerTeamKey(match.homeTeamId) }
                val away = result.value.firstOrNull { it.teamKey == providerTeamKey(match.awayTeamId) }
                if (home != null || away != null) {
                    db.statisticsDao().upsert(
                        MatchStatisticsEntity(
                            matchId = matchId,
                            homeShots = home?.values?.get(StatisticType.SHOTS)?.toInt(),
                            awayShots = away?.values?.get(StatisticType.SHOTS)?.toInt(),
                            homeShotsOnTarget = home?.values?.get(StatisticType.SHOTS_ON_TARGET)?.toInt(),
                            awayShotsOnTarget = away?.values?.get(StatisticType.SHOTS_ON_TARGET)?.toInt(),
                            homePossession = home?.values?.get(StatisticType.POSSESSION),
                            awayPossession = away?.values?.get(StatisticType.POSSESSION),
                            homeCorners = home?.values?.get(StatisticType.CORNERS)?.toInt(),
                            awayCorners = away?.values?.get(StatisticType.CORNERS)?.toInt(),
                            homeYellowCards = home?.values?.get(StatisticType.YELLOW_CARDS)?.toInt(),
                            awayYellowCards = away?.values?.get(StatisticType.YELLOW_CARDS)?.toInt(),
                            homeRedCards = home?.values?.get(StatisticType.RED_CARDS)?.toInt(),
                            awayRedCards = away?.values?.get(StatisticType.RED_CARDS)?.toInt(),
                            homeExpectedGoals = home?.values?.get(StatisticType.EXPECTED_GOALS),
                            awayExpectedGoals = away?.values?.get(StatisticType.EXPECTED_GOALS),
                            updatedAt = now,
                        ),
                    )
                    db.matchDao().markStatisticsSynced(matchId, now)
                    summary = summary + SyncSummary(details = 1)
                }
            }
        }

        when (val result = provider.matchEvents(match.providerKey)) {
            is Outcome.Failure -> errors.add(result.error.code.name)
            is Outcome.Success -> {
                if (result.value.isNotEmpty()) {
                    val events = result.value.map { event ->
                        val teamId = event.teamKey?.let { key -> teamIdForProviderKey(match, key) }
                        MatchEventEntity(
                            id = Ids.event(matchId, event.minute, event.type.name, event.playerName),
                            matchId = matchId,
                            minute = event.minute,
                            type = event.type.name,
                            detail = event.detail,
                            teamId = teamId,
                            playerName = event.playerName,
                            assistName = event.assistName,
                        )
                    }
                    db.eventDao().replaceForMatch(matchId, events)
                    db.matchDao().markEventsSynced(matchId)
                }
            }
        }

        when (val result = provider.lineups(match.providerKey)) {
            is Outcome.Failure -> errors.add(result.error.code.name)
            is Outcome.Success -> {
                if (result.value.isNotEmpty()) {
                    val players = result.value.flatMap { lineup ->
                        val teamId = teamIdForProviderKey(match, lineup.teamKey)
                        lineup.players.map { player ->
                            LineupPlayerEntity(
                                id = Ids.lineup(matchId, teamId, player.name),
                                matchId = matchId,
                                teamId = teamId,
                                name = player.name,
                                number = player.number,
                                position = player.position,
                                isStarter = player.isStarter,
                                formation = lineup.formation,
                                updatedAt = now,
                            )
                        }
                    }
                    db.lineupDao().replaceForMatch(matchId, players)
                    db.matchDao().markLineupsSynced(matchId)
                }
            }
        }

        return Outcome.Success(summary.copy(errors = errors))
    }

    /**
     * Tops up the league tables the active source publishes, at most once a day each.
     *
     * Only competitions the user follows are asked for: one document per league, per day,
     * is a light client. The marker lives in sync_meta so a refresh a minute later does
     * not re-download a table that cannot have changed.
     */
    suspend fun refreshStandings(
        providerId: String,
        competitionKeys: List<String>,
        maxAgeMs: Long = STANDINGS_MAX_AGE_MS,
    ): Int {
        val provider = registry.provider(providerId) ?: return 0
        if (!provider.info.capabilities.standings) return 0
        val now = clock.nowEpochMs()
        var written = 0
        // The list holds provider keys, not ids: the id is built here so a follower of
        // "ger.1" and a stored competition are the same row.
        for (key in competitionKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val competitionId = Ids.competition(providerId, key)
            val marker = STANDINGS_MARKER + competitionId
            val last = db.syncMetaDao().entity(marker)?.updatedAt ?: 0L
            if (now - last < maxAgeMs) continue
            val season = db.competitionDao().byId(competitionId)?.season
                ?: java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).year
            val result = syncStandings(providerId, key, season)
            if (result is Outcome.Success) {
                db.syncMetaDao().put(SyncMetaEntity(marker, "ok", now))
                written += result.value
            }
        }
        return written
    }

    /**
     * The table of a competition, even when its own source publishes none.
     *
     * Most fixtures here come from the CSV source, which has no table. The same league *is*
     * documented by ESPN under its own slug, and `LeagueCrestIndex.LEAGUES` already maps the
     * two ("E0" is "eng.1"). So the table is borrowed from there and stored under the
     * competition the reader is actually looking at: a row it can recognise keeps our club
     * id, name and crest, and a row it cannot is stored as published rather than dropped —
     * a table with holes reads as a broken sync.
     */
    suspend fun refreshTableFor(competitionId: String, force: Boolean = false): Int {
        val competition = db.competitionDao().byId(competitionId) ?: return 0
        // Opening the page asks for the table, and a league whose source has none would be
        // asked again on every visit. The attempt is remembered either way — a failure is
        // still an answer — so the next visit within this window reads what is stored.
        val attemptMarker = TABLE_ATTEMPT_MARKER + competitionId
        val lastAttempt = db.syncMetaDao().entity(attemptMarker)?.updatedAt ?: 0L
        if (!force && clock.nowEpochMs() - lastAttempt < TABLE_ATTEMPT_INTERVAL_MS) return 0
        db.syncMetaDao().put(SyncMetaEntity(attemptMarker, "asked", clock.nowEpochMs()))
        val own = registry.provider(competition.providerId)
        if (own?.info?.capabilities?.standings == true) {
            return refreshStandings(competition.providerId, listOf(competition.providerKey), maxAgeMs = 0)
        }
        val slug = com.footballai.app.data.remote.LeagueCrestIndex.LEAGUES[competition.providerKey]
            ?: return 0
        val source = registry.provider(EspnOpenProvider.PROVIDER_ID) ?: return 0
        if (!source.info.capabilities.standings) return 0
        val season = competition.season ?: java.time.Instant.ofEpochMilli(clock.nowEpochMs())
            .atZone(java.time.ZoneId.systemDefault()).year
        val table = when (val result = source.standings(slug, season)) {
            is Outcome.Failure -> return 0
            is Outcome.Success -> result.value
        }
        if (table.isEmpty()) return 0
        val ours = db.teamDao().forCompetition(competitionId)
        val now = clock.nowEpochMs()
        val rows = table.map { row ->
            val team = matchPublishedName(ours, row.teamName)
            StandingEntity(
                competitionId = competitionId,
                season = season,
                teamId = team?.id ?: Ids.team(EspnOpenProvider.PROVIDER_ID, row.teamKey),
                teamName = team?.name ?: row.teamName,
                crestUrl = team?.crestUrl?.takeIf { it.isNotBlank() } ?: row.crestUrl,
                rank = row.rank,
                points = row.points,
                played = row.played,
                goalDifference = row.goalDifference,
                form = row.form,
                updatedAt = now,
                wins = row.wins,
                draws = row.draws,
                losses = row.losses,
                goalsFor = row.goalsFor,
                goalsAgainst = row.goalsAgainst,
            )
        }
        db.standingDao().insertAll(rows)
        return rows.size
    }

    /** The club this published name can only mean, or nothing when it is ambiguous. */
    private fun matchPublishedName(teams: List<TeamEntity>, published: String): TeamEntity? =
        teams.filter { team -> com.footballai.app.data.remote.ClubNameMatcher.matches(published, team.name) }
            .singleOrNull()

    suspend fun syncStandings(providerId: String, competitionKey: String, season: Int): Outcome<Int> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        if (!provider.info.capabilities.standings) return Outcome.Success(0)
        return when (val result = provider.standings(competitionKey, season)) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val competitionId = Ids.competition(providerId, competitionKey)
                val now = clock.nowEpochMs()
                val rows = result.value.map { row ->
                    StandingEntity(
                        competitionId = competitionId,
                        season = season,
                        teamId = Ids.team(providerId, row.teamKey),
                        teamName = row.teamName,
                        crestUrl = row.crestUrl,
                        rank = row.rank,
                        points = row.points,
                        played = row.played,
                        goalDifference = row.goalDifference,
                        form = row.form,
                        updatedAt = now,
                        wins = row.wins,
                        draws = row.draws,
                        losses = row.losses,
                        goalsFor = row.goalsFor,
                        goalsAgainst = row.goalsAgainst,
                    )
                }
                if (rows.isNotEmpty()) {
                    if (providerId == com.footballai.app.data.remote.Varzesh3Provider.PROVIDER_ID) {
                        db.withTransaction {
                            db.standingDao().deleteSeason(competitionId, season)
                            db.standingDao().insertAll(rows)
                        }
                    } else db.standingDao().insertAll(rows)
                }
                Outcome.Success(rows.size)
            }
        }
    }

    /**
     * Repair crestless clubs in fixtures, prioritising matches closest to today.
     *
     * A completed search without a match cools down per team so it cannot starve the queue.
     * A request that fails for its own reasons is tried once more and then skipped — the club
     * keeps its place, unmarked, and the sweep carries on with the next one. Only a source
     * that has actually closed (a rate limit, a spent quota, a refused key) ends the sweep.
     */
    suspend fun backfillCrests(
        providerId: String = EspnOpenProvider.PROVIDER_ID,
        limit: Int = 60,
        force: Boolean = false,
        priorityEpochMs: Long? = null,
    ): Int {
        // The queue is only worked while the app is open, and the fallback source is rate
        // limited to about twenty lookups a minute, so one batch per sync left a hundred
        // clubs waiting for days. A sync now takes three batches — ten minutes of requests
        // at the pace the source allows — and stops when a batch comes back short because
        // the queue is short, not because the batch ran out of requests.
        var filled = 0
        repeat(CREST_BATCHES_PER_SYNC) { round ->
            val batch = crestBackfillBatch(
                providerId, limit, force || round > 0, priorityEpochMs, null, null,
            )
            filled += batch.filled
            // A short batch means the queue is short only when the request allowance was not
            // what cut it short: a batch that spent its budget leaves clubs behind it, and the
            // next round is exactly how they get served.
            if (batch.failed || (batch.attempted < limit && !batch.budgetSpent)) return filled
        }
        return filled
    }

    /** Continue through multiple bounded batches for the selected day, not just the first 30. */
    suspend fun backfillCrestsForDay(fromEpochMs: Long, toEpochMs: Long) {
        prefillLeagueCrests(fromEpochMs, toEpochMs)
        repeat(4) {
            val batch = crestBackfillBatch(
                EspnOpenProvider.PROVIDER_ID, 40, true,
                fromEpochMs + (toEpochMs - fromEpochMs) / 2, fromEpochMs, toEpochMs,
            )
            if (batch.failed || (batch.attempted < 40 && !batch.budgetSpent)) return
        }
    }

    /** One league document can repair many clubs before the slower name-search queue. */
    private suspend fun prefillLeagueCrests(fromEpochMs: Long, toEpochMs: Long) = coroutineScope {
        val leagues = db.teamDao().crestCompetitionsForDay(fromEpochMs, toEpochMs)
        val permits = Semaphore(3)
        leagues.map { competition ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    val slug = com.footballai.app.data.remote.LeagueCrestIndex.LEAGUES[competition.providerKey]
                        ?: return@withPermit
                    val teams = db.teamDao().crestlessTeamsForCompetitionDay(competition.id, fromEpochMs, toEpochMs)
                    if (teams.isEmpty()) return@withPermit
                    val roster = (leagueCrestIndex.league(slug) as? Outcome.Success)?.value ?: return@withPermit
                    for (team in teams) {
                        if (db.teamDao().fixtureGenders(team.id).singleOrNull() != "MEN") continue
                        val alias = com.footballai.app.data.remote.CrestSearchAliases.lookup(
                            team.providerId, listOf(competition.providerKey), team.name,
                        )
                        val url = com.footballai.app.data.remote.LeagueCrestIndex.match(roster, team.name, alias)
                        if (url != null) db.teamDao().setCrestIfMissing(team.id, url)
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * The club as its own league publishes it.
     *
     * `LEAGUES` maps a feed league key to the competition the crest source documents, and
     * the roster document for that competition carries the published name of every club in
     * it. Rosters are cached for twelve hours, so this costs one request per league per
     * run, not one per club. Nothing is stored unless exactly one club in that roster can
     * be meant.
     */
    private suspend fun crestFromRoster(
        name: String,
        slugs: List<String>,
        alias: String?,
    ): com.footballai.app.data.remote.RosterLookup {
        for (slug in slugs) {
            val roster = (leagueCrestIndex.league(slug) as? Outcome.Success)?.value ?: continue
            val lookup = com.footballai.app.data.remote.LeagueCrestIndex.lookup(roster, name, alias)
            // The club was found in its own league's roster; whatever that roster publishes
            // for it is the final word from this source.
            if (lookup.matched) return lookup
        }
        return com.footballai.app.data.remote.RosterLookup(null, matched = false)
    }

    /**
     * Why the last sweep stopped where it did.
     *
     * A sweep that ends short leaves no trace on the phone: the diagnostic could only say
     * that a club was never asked, not whether the queue was slow or a source had refused
     * every request since noon. This keeps the last failure and its time.
     */
    private suspend fun recordCrestFailure(providerId: String, error: AppError) {
        val detail = "$providerId ${error.code}" + (error.httpStatus?.let { " (HTTP $it)" } ?: "")
        db.syncMetaDao().put(SyncMetaEntity(CREST_LAST_ERROR_MARKER, detail, clock.nowEpochMs()))
    }

    /**
     * What one sweep of the crest queue did, and why it stopped.
     *
     * [attempted] alone cannot tell "the queue is short" from "the source's request budget for
     * this batch ran out": in the second case there are still clubs waiting, and the caller
     * stopping because `attempted < limit` leaves them waiting all day. That reason is kept
     * apart in [budgetSpent] instead of being guessed from the count.
     */
    private data class CrestBatch(
        val filled: Int,
        val attempted: Int,
        val failed: Boolean = false,
        val budgetSpent: Boolean = false,
    )

    private suspend fun crestBackfillBatch(
        providerId: String, limit: Int, force: Boolean, priorityEpochMs: Long?,
        fromEpochMs: Long?, toEpochMs: Long?,
    ): CrestBatch = crestBackfillMutex.withLock {
        val provider = registry.provider(providerId) as? EspnOpenProvider ?: return@withLock CrestBatch(0, 0, failed = true)
        val now = clock.nowEpochMs()
        val last = db.syncMetaDao().entity(CREST_BACKFILL_MARKER)?.updatedAt
        if (!force && last != null && now - last < CREST_BACKFILL_INTERVAL_MS) return@withLock CrestBatch(0, 0)
        var filled = 0
        var attempted = 0
        var sportsDbAttempts = 0
        var budgetSpent = false
        for (team in db.teamDao().crestlessInFixtures(
            priorityEpochMs ?: now, now - CREST_RETRY_INTERVAL_MS, limit, fromEpochMs, toEpochMs,
        )) {
            var requestLost = false
            val competitionKeys = db.teamDao().fixtureCompetitionKeys(team.id)
            val slugs = competitionKeys
                .mapNotNull { key -> com.footballai.app.data.remote.LeagueCrestIndex.LEAGUES[key] }
                .distinct()
            val documented = slugs.any { slug ->
                leagueCrestIndex.league(slug) is Outcome.Success
            }
            val alias = com.footballai.app.data.remote.CrestSearchAliases.lookup(
                team.providerId, competitionKeys, team.name,
            )
            // The roster of the club's own league spells it the way that source does, so one
            // cached document can settle a whole league — and it does not care which
            // provider the fixture came from, which day is open, or how the gender was
            // classified. This is the first attempt for every club now, not a side path.
            val roster = crestFromRoster(team.name, slugs, alias)
            var url: String? = roster.crestUrl
            val queries = listOfNotNull(
                alias,
                com.footballai.app.domain.catalog.TeamNames.canonicalName(team.name),
                // The search is literal: "sc Heerenveen" answers nothing while "Heerenveen"
                // finds the club, so the words that only describe a legal form are tried too.
                com.footballai.app.data.remote.ClubNameMatcher.core(team.name).joinToString(" ")
                    .takeIf { stripped ->
                        stripped.isNotBlank() &&
                            stripped != com.footballai.app.domain.catalog.TeamNames
                                .normalizeNameKey(team.name)
                    },
                team.name,
                // Both search services treat slash-separated names as a single token.
                team.name.replace('/', ' '),
            ).filter { it.isNotBlank() }.distinct()
            // A competition the crest source does not document — no league document at all,
            // which is the case for the German third tier, the English sixth tier and the
            // Polish and Czech divisions — is not where a search answer comes from. Asking
            // five spellings per club there spends the request budget of the whole batch on
            // lanes that cannot answer and starves the fallback source of its turn. Those
            // lanes get their own name and nothing else.
            val espnQueries = if (documented) queries else listOf(team.name, team.name.replace('/', ' '))
                .filter { it.isNotBlank() }.distinct()
            val searchWorthwhile = url == null && !roster.matched
            for (query in if (searchWorthwhile) espnQueries else emptyList()) {
                when (val result = CrestQueuePolicy.attempt { provider.crestForTeamName(query, slugs.firstOrNull()) }) {
                    is Outcome.Success -> url = result.value
                    // In particular, do not consume the rest of the batch on quota errors.
                    is Outcome.Failure -> {
                        recordCrestFailure(EspnOpenProvider.PROVIDER_ID, result.error)
                        if (CrestQueuePolicy.stopsBatch(result.error)) {
                            return@withLock CrestBatch(filled, attempted, failed = true)
                        }
                        // One club's request was lost twice: it keeps its place in the queue
                        // (nothing is marked as asked) and the sweep moves on to the next one.
                        requestLost = true
                        break
                    }
                }
                if (url != null) break
            }
            // Nothing was learned about this club, so it is not counted as asked and not
            // marked for cooldown; the clubs behind it in the queue are still served.
            if (requestLost) continue
            // This source is rate limited to a few dozen lookups a minute, so a batch takes
            // a bounded slice and the rest of the queue continues on the next run — the team
            // keeps its place instead of being marked as tried.
            if (url == null && sportsDbAttempts >= SPORTSDB_ATTEMPTS_PER_BATCH) {
                // The batch ran out of the fallback source's allowance, not out of clubs.
                budgetSpent = true
                break
            }
            // Only a verified name or published alias, with unambiguous gender.
            if (url == null) {
                val gender = db.teamDao().fixtureGenders(team.id).singleOrNull()
                if (gender != null) {
                    sportsDbAttempts++
                    // The search is literal, so the spellings that can answer are the
                    // published one, the verified alias, and the same words without the
                    // legal form — "Rot-Weiss Essen" is published with a hyphen the feed
                    // does not have. Each extra spelling costs a request at 2 seconds a
                    // request, so the rest of the list is not tried here.
                    val coreQuery = com.footballai.app.data.remote.ClubNameMatcher.core(team.name)
                        .joinToString(" ")
                    for (query in listOfNotNull(team.name, coreQuery.takeIf { it.isNotBlank() }, alias)
                        .distinct()) {
                        when (val result = CrestQueuePolicy.attempt { sportsDbCrests.find(query, gender) }) {
                            is Outcome.Success -> url = result.value
                            is Outcome.Failure -> {
                                recordCrestFailure("sportsdb-crest", result.error)
                                if (CrestQueuePolicy.stopsBatch(result.error)) {
                                    return@withLock CrestBatch(filled, attempted, failed = true)
                                }
                                requestLost = true
                                break
                            }
                        }
                        if (url != null) break
                    }
                }
            }
            if (requestLost) continue
            if (url != null) filled += db.teamDao().setCrestIfMissing(team.id, url)
            // Bumped to v7 with the fallback source's name rules: a club that was refused
            // because its published name was `Billericay Town` and the feed says `Billericay`
            // is asked once more instead of cooling down for a week.
            db.syncMetaDao().put(
                SyncMetaEntity(
                    com.footballai.app.data.local.CrestAttempts.key(team.id), "checked", clock.nowEpochMs(),
                ),
            )
            attempted++
        }
        if (priorityEpochMs == null) {
            db.syncMetaDao().put(SyncMetaEntity(CREST_BACKFILL_MARKER, "ok", clock.nowEpochMs()))
        }
        CrestBatch(filled, attempted, budgetSpent = budgetSpent)
    }

    /**
     * Imports bulk history from football-data.co.uk. Requests are estimated up front
     * so the user can decide whether the download is worth it.
     */
    suspend fun importCsvHistory(
        competitionKeys: List<String>,
        seasons: List<Int>,
    ): Outcome<SyncSummary> {
        if (competitionKeys.isEmpty() || seasons.isEmpty()) {
            return Outcome.Failure(AppError(ErrorCode.EMPTY_RESPONSE, detail = "Nothing selected"))
        }
        val provider = registry.historical
        val now = clock.nowEpochMs()
        var summary = SyncSummary(requestsEstimated = competitionKeys.size * seasons.size)
        val errors = mutableListOf<String>()

        for (key in competitionKeys) {
            val competitionName = provider.availableCompetitions().firstOrNull { it.key == key }?.name ?: key
            for (season in seasons) {
                when (val result = provider.history(key, season)) {
                    is Outcome.Failure -> {
                        if (result.error.code != ErrorCode.NOT_FOUND) {
                            errors.add("$key/$season: ${result.error.code}")
                        }
                    }
                    is Outcome.Success -> {
                        val competitionId = Ids.competition(provider.info.id, key)
                        val inserted = persistHistory(
                            source = com.footballai.app.data.remote.FootballDataCoUkProvider.PROVIDER_ID,
                            competitionId = competitionId,
                            competitionName = competitionName,
                            rows = result.value,
                            now = now,
                        )
                        applyXg(key, competitionId, season)
                        applyAvailability(key, season)
                        summary = summary + SyncSummary(historical = inserted)
                    }
                }
            }
        }
        settingsRepository.setLastHistoricalImport(now)
        ratingsRepository.invalidate()
        return Outcome.Success(summary.copy(errors = errors))
    }

    /**
     * Best-effort xG enrichment for one imported competition season.
     *
     * The mirror names teams exactly as football-data.co.uk does, and the stored
     * history rows carry those names, so a row is matched on (home name, away
     * name, kickoff date in Europe/London) — the same dated-fixture discipline
     * [persistHistory] uses for results, with a one-day tolerance for timezone
     * edges. A row is only ever filled, never overwritten, and any failure
     * (mirror unreachable, competition not covered) leaves the import exactly
     * as it was: xG is an enhancement to the rating fit, not a requirement.
     */
    private suspend fun applyXg(competitionKey: String, competitionId: String, seasonStartYear: Int) {
        if (competitionKey !in com.footballai.app.data.remote.XgHistoryProvider.COVERED_COMPETITIONS) return
        val xgRows = when (val result = xgProvider.xg(competitionKey, seasonStartYear)) {
            is Outcome.Failure -> return
            is Outcome.Success -> result.value
        }
        if (xgRows.isEmpty()) return
        val byFixture = HashMap<Triple<String, String, LocalDate>, Pair<Double, Double>>()
        for (row in xgRows) {
            byFixture[Triple(row.homeTeamName, row.awayTeamName, row.date)] = row.homeXg to row.awayXg
        }
        val stored = db.historicalDao().forCompetition(
            FootballDataCoUkProvider.PROVIDER_ID, competitionId, Long.MAX_VALUE,
        )
        db.withTransaction {
            for (row in stored) {
                if (row.homeXg != null && row.awayXg != null) continue
                val date = Instant.ofEpochMilli(row.kickoffEpochMs)
                    .atZone(ZoneId.of("Europe/London")).toLocalDate()
                val xg = byFixture[Triple(row.homeTeamName, row.awayTeamName, date)]
                    ?: byFixture[Triple(row.homeTeamName, row.awayTeamName, date.minusDays(1))]
                    ?: byFixture[Triple(row.homeTeamName, row.awayTeamName, date.plusDays(1))]
                    ?: continue
                db.historicalDao().updateXg(row.id, xg.first, xg.second)
            }
        }
    }

    /**
     * Best-effort availability sync for one competition season.
     *
     * The mirror names teams exactly as football-data.co.uk does, so a row is
     * matched to a stored fixture on (home name, away name, kickoff date in
     * Europe/London) with a one-day tolerance — the same discipline [applyXg]
     * uses for history rows. Fixtures stored under another provider's naming
     * simply do not match and keep no availability row, which prediction
     * treats as "no data", never as "nobody missing". Any failure (mirror
     * unreachable, competition not covered) leaves the sync exactly as it was.
     */
    private suspend fun applyAvailability(competitionKey: String, seasonStartYear: Int) {
        if (competitionKey !in com.footballai.app.data.remote.AvailabilityHistoryProvider.COVERED_COMPETITIONS) return
        val rows = when (val result = availabilityProvider.availability(competitionKey, seasonStartYear)) {
            is Outcome.Failure -> return
            is Outcome.Success -> result.value
        }
        if (rows.isEmpty()) return
        val byFixture = HashMap<Triple<String, String, LocalDate>, Pair<Double, Double>>()
        for (row in rows) {
            byFixture[Triple(row.homeTeamName, row.awayTeamName, row.date)] =
                row.homeMissingShare to row.awayMissingShare
        }
        val competitionIds = db.competitionDao().allOnce()
            .filter { it.providerKey == competitionKey }
            .map { it.id }
        if (competitionIds.isEmpty()) return
        val now = clock.nowEpochMs()
        val entities = ArrayList<com.footballai.app.data.local.entity.MatchAvailabilityEntity>()
        for (competitionId in competitionIds) {
            val matches = db.matchDao().forCompetition(competitionId)
            if (matches.isEmpty()) continue
            val teamNames = db.teamDao()
                .byIds(matches.flatMap { listOf(it.homeTeamId, it.awayTeamId) }.distinct())
                .associate { it.id to it.name }
            for (match in matches) {
                val homeName = teamNames[match.homeTeamId] ?: continue
                val awayName = teamNames[match.awayTeamId] ?: continue
                val date = Instant.ofEpochMilli(match.kickoffEpochMs)
                    .atZone(ZoneId.of("Europe/London")).toLocalDate()
                val shares = byFixture[Triple(homeName, awayName, date)]
                    ?: byFixture[Triple(homeName, awayName, date.minusDays(1))]
                    ?: byFixture[Triple(homeName, awayName, date.plusDays(1))]
                    ?: continue
                entities.add(
                    com.footballai.app.data.local.entity.MatchAvailabilityEntity(
                        matchId = match.id,
                        homeMissingShare = shares.first,
                        awayMissingShare = shares.second,
                        updatedAt = now,
                    ),
                )
            }
        }
        if (entities.isNotEmpty()) {
            db.withTransaction { db.availabilityDao().upsertAll(entities) }
        }
    }

    /**
     * Downloads past seasons from the active fixture provider so the rating models
     * have data for teams that have not played in the current window yet.
     */
    suspend fun importProviderHistory(
        providerId: String,
        competitionKeys: List<String>,
        seasonsBack: Int,
    ): Outcome<SyncSummary> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        val currentSeason = currentSeasonStartYear()
        val seasons = (0 until seasonsBack.coerceIn(1, 5)).map { currentSeason - it }
        var summary = SyncSummary(requestsEstimated = competitionKeys.size * seasons.size)
        val errors = mutableListOf<String>()
        val now = clock.nowEpochMs()

        for (key in competitionKeys) {
            val competitionName = db.competitionDao()
                .byProviderKey(providerId, key)?.name
                ?: key
            for (season in seasons) {
                when (val result = provider.matches(key, season, fromEpochMs = null, toEpochMs = null)) {
                    is Outcome.Failure -> errors.add("$key/$season: ${result.error.code}")
                    is Outcome.Success -> {
                        val finished = result.value.filter { it.status == MatchStatus.FINISHED && it.scoreScope == "REGULATION" && it.homeGoals != null && it.awayGoals != null }
                        val inserted = persistHistory(
                            source = providerId,
                            competitionId = Ids.competition(providerId, key),
                            competitionName = competitionName,
                            rows = finished.map { it.toHistoryRow(key) },
                            now = now,
                        )
                        summary = summary + SyncSummary(historical = inserted)
                    }
                }
            }
        }
        settingsRepository.setLastHistoricalImport(now)
        ratingsRepository.invalidate()
        return Outcome.Success(summary.copy(errors = errors))
    }

    /** Copies locally stored finished matches into the training table. */
    suspend fun appendFinishedMatches(source: String): Int {
        val finished = db.matchDao().finishedSince(clock.nowEpochMs() - HISTORY_LOOKBACK_DAYS * TimeConstants.DAY_MS)
            .filter { it.providerId == source && it.scoreScope == "REGULATION" }
        if (finished.isEmpty()) return 0
        val teamIds = finished.flatMap { listOf(it.homeTeamId, it.awayTeamId) }.distinct()
        val teams = db.teamDao().byIds(teamIds).associateBy { it.id }
        val now = clock.nowEpochMs()
        val rows = finished.mapNotNull { match ->
            val homeGoals = match.homeGoals ?: return@mapNotNull null
            val awayGoals = match.awayGoals ?: return@mapNotNull null
            val home = teams[match.homeTeamId] ?: return@mapNotNull null
            val away = teams[match.awayTeamId] ?: return@mapNotNull null
            HistoricalMatchEntity(
                id = Ids.historical(source, match.competitionId, match.homeTeamId, match.awayTeamId, match.kickoffEpochMs),
                source = source,
                competitionId = match.competitionId,
                competitionName = "",
                homeTeamId = match.homeTeamId,
                awayTeamId = match.awayTeamId,
                homeTeamName = home.name,
                awayTeamName = away.name,
                homeGoals = homeGoals,
                awayGoals = awayGoals,
                kickoffEpochMs = match.kickoffEpochMs,
                homeOdds = match.oddsHome,
                drawOdds = match.oddsDraw,
                awayOdds = match.oddsAway,
                importedAt = now,
                // Keep the venue fact: a national-team result on neutral ground must not
                // be replayed as if the home side had its own crowd.
                neutralVenue = match.isNeutralVenue,
            )
        }
        if (rows.isEmpty()) return 0
        val inserted = db.historicalDao().insertAll(rows)
        return inserted.count { it != -1L }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun persistMatches(
        providerId: String,
        competitionKey: String,
        matches: List<RemoteMatch>,
        now: Long = clock.nowEpochMs(),
    ): Int {
        if (matches.isEmpty()) return 0
        val competitionId = Ids.competition(providerId, competitionKey)
        val existingCompetition = db.competitionDao().byId(competitionId)
        val competitionName = matches.firstOrNull()?.competitionName?.takeIf { it.isNotBlank() }
            ?: existingCompetition?.name
            ?: competitionKey
        val classification = CompetitionCatalog.classify(
            providerId = providerId,
            providerKey = competitionKey,
            name = competitionName,
            country = existingCompetition?.country,
        )
        val refreshed = existingCompetition?.copy(
            competitionClass = classification.label(),
            gender = classification.gender.name,
            classificationSource = classification.source.name,
            updatedAt = now,
        )
        val needsClassification = existingCompetition == null ||
            existingCompetition.classificationSource == ClassificationSource.UNKNOWN.name
        if (needsClassification) {
            db.competitionDao().upsertAll(
                listOf(
                    refreshed ?: CompetitionEntity(
                        id = competitionId,
                        providerId = providerId,
                        providerKey = competitionKey,
                        name = competitionName,
                        country = null,
                        emblemUrl = null,
                        season = matches.firstOrNull()?.season,
                        hasFullCoverage = registry.provider(providerId)?.info?.capabilities?.liveScores ?: false,
                        isFollowed = true,
                        updatedAt = now,
                        competitionClass = classification.label(),
                        gender = classification.gender.name,
                        classificationSource = classification.source.name,
                    ),
                ),
            )
        }

        db.withTransaction {
            val teamIds = matches.flatMap {
                listOf(Ids.team(providerId, it.home.providerKey), Ids.team(providerId, it.away.providerKey))
            }
            val knownTeams = db.teamDao().byIds(teamIds.distinct()).associateBy { it.id }
            val teams = LinkedHashMap<String, TeamEntity>()
            for (match in matches) {
                for (remote in listOf(match.home, match.away)) {
                    val id = Ids.team(providerId, remote.providerKey)
                    val entity = remote.toEntity(providerId, now, classification.teamKind)
                    // A team discovered as a national team keeps that identity; a club-league
                    // fixture can never silently downgrade it.
                    val previous = teams[id] ?: knownTeams[id]
                    val withKind = if (previous?.kind == TeamKind.NATIONAL.name && entity.kind != TeamKind.NATIONAL.name) {
                        entity.copy(kind = previous.kind, countryCode = entity.countryCode ?: previous.countryCode)
                    } else {
                        entity
                    }
                    // A source that publishes no badge must not take one away. The archive and
                    // the daily fixtures both arrive without crests, and overwriting the address
                    // a previous sync (or the crest backfill) had stored is what made a badge
                    // appear and then vanish the next time the screen was opened.
                    val previousCrest = previous?.crestUrl
                    val withCrest = if (
                        withKind.crestUrl.isNullOrBlank() && !previousCrest.isNullOrBlank()
                    ) {
                        withKind.copy(crestUrl = previousCrest)
                    } else {
                        withKind
                    }
                    // An ESPN club whose payload carried no logo still has a published address:
                    // the source's own crest folder, one per numeric club id. Storing it now
                    // means the team never waits for the backfill, and the backfill's queue
                    // stays for the archive clubs that have no such address.
                    teams[id] = if (withCrest.crestUrl.isNullOrBlank()) {
                        val fallback = com.footballai.app.domain.catalog.CrestUrls.forClub(
                            providerId,
                            withCrest.providerKey,
                            runCatching { TeamKind.valueOf(withCrest.kind) }.getOrDefault(TeamKind.CLUB),
                        )
                        if (fallback != null) withCrest.copy(crestUrl = fallback) else withCrest
                    } else {
                        withCrest
                    }
                }
            }
            db.teamDao().upsertAll(teams.values.toList())
        }

        val entities = matches.map { match ->
            val previous = if (providerId == FootballDataCoUkProvider.PROVIDER_ID && match.season != null) {
                db.matchDao().csvCandidates(providerId, competitionId, match.season,
                    Ids.team(providerId, match.home.providerKey), Ids.team(providerId, match.away.providerKey))
                    .firstOrNull { CsvFixtureIdentity.sameDate(it.kickoffEpochMs, match.kickoffEpochMs) }
            } else null
            val entity = match.toEntity(
                providerId = providerId,
                homeTeamId = Ids.team(providerId, match.home.providerKey),
                awayTeamId = Ids.team(providerId, match.away.providerKey),
                competitionId = competitionId,
                now = now,
            )
            when {
                previous?.status == MatchStatus.FINISHED.name -> previous
                previous != null -> entity.copy(id = previous.id, providerKey = previous.providerKey)
                else -> entity
            }
        }
        db.matchDao().mergeFixtures(entities)
        return entities.size
    }

    private suspend fun persistHistory(
        source: String,
        competitionId: String,
        competitionName: String,
        rows: List<RemoteHistoryMatch>,
        now: Long,
    ): Int {
        if (rows.isEmpty()) return 0
        val entities = rows.map { row ->
            HistoricalMatchEntity(
                id = Ids.historical(
                    source = source,
                    competitionId = competitionId,
                    homeId = Ids.team(source, row.homeTeamKey),
                    awayId = Ids.team(source, row.awayTeamKey),
                    kickoff = row.kickoffEpochMs,
                ),
                source = source,
                competitionId = competitionId,
                competitionName = competitionName,
                homeTeamId = Ids.team(source, row.homeTeamKey),
                awayTeamId = Ids.team(source, row.awayTeamKey),
                homeTeamName = row.homeTeamName,
                awayTeamName = row.awayTeamName,
                homeGoals = row.homeGoals,
                awayGoals = row.awayGoals,
                kickoffEpochMs = row.kickoffEpochMs,
                homeOdds = row.homeOdds,
                drawOdds = row.drawOdds,
                awayOdds = row.awayOdds,
                importedAt = now,
            )
        }
        return db.withTransaction {
            val inserted = db.historicalDao().insertAll(entities)
            if (source == FootballDataCoUkProvider.PROVIDER_ID) {
                for (row in rows) {
                    val season = row.season ?: continue
                    val candidates = db.matchDao().csvCandidates(
                        source, competitionId, season,
                        Ids.team(source, row.homeTeamKey), Ids.team(source, row.awayTeamKey),
                    )
                    // Never infer identity from team pair + season alone (e.g. Scotland
                    // can repeat a home/away pairing). Preserve dated fixture links.
                    for (match in candidates.filter { CsvFixtureIdentity.sameDate(it.kickoffEpochMs, row.kickoffEpochMs) }) {
                        db.matchDao().upsertAll(listOf(match.copy(
                            status = MatchStatus.FINISHED.name,
                            homeGoals = row.homeGoals, awayGoals = row.awayGoals,
                            kickoffEpochMs = row.kickoffEpochMs,
                            minute = null, lastSyncedAt = now, scoreScope = "REGULATION",
                        )))
                    }
                }
            }
            inserted.count { it != -1L }
        }
    }

    private suspend fun syncPendingCsvResults(): SyncSummary {
        val pending = db.matchDao().pendingResults(FootballDataCoUkProvider.PROVIDER_ID, clock.nowEpochMs())
        var summary = SyncSummary()
        for ((competitionId, season) in pending.mapNotNull { row -> row.season?.let { row.competitionId to it } }.distinct()) {
            when (val result = importCsvHistory(listOf(competitionId.substringAfterLast(':')), listOf(season))) {
                is Outcome.Success -> summary += result.value
                is Outcome.Failure -> summary += SyncSummary(errors = listOf(result.error.code.name))
            }
        }
        return summary
    }

    private fun RemoteMatch.toHistoryRow(competitionKey: String): RemoteHistoryMatch = RemoteHistoryMatch(
        competitionKey = competitionKey,
        competitionName = competitionName,
        season = season,
        homeTeamKey = home.providerKey,
        homeTeamName = home.name,
        awayTeamKey = away.providerKey,
        awayTeamName = away.name,
        homeGoals = homeGoals ?: 0,
        awayGoals = awayGoals ?: 0,
        kickoffEpochMs = kickoffEpochMs,
        homeOdds = odds?.home,
        drawOdds = odds?.draw,
        awayOdds = odds?.away,
    )

    private fun providerTeamKey(teamId: String): String = teamId.substringAfterLast(':')

    private fun teamIdForProviderKey(match: com.footballai.app.data.local.entity.MatchEntity, providerKey: String): String {
        val homeKey = providerTeamKey(match.homeTeamId)
        val awayKey = providerTeamKey(match.awayTeamId)
        return when (providerKey) {
            homeKey -> match.homeTeamId
            awayKey -> match.awayTeamId
            else -> match.homeTeamId
        }
    }

    private fun currentSeasonStartYear(): Int {
        val now = Instant.ofEpochMilli(clock.nowEpochMs()).atZone(ZoneOffset.UTC)
        return if (now.monthValue >= 7) now.year else now.year - 1
    }

    companion object {
        /** A table changes at most after a round of matches, so a day is generous. */
        private const val STANDINGS_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val CREST_BACKFILL_MARKER = "crest-backfill-v4"
        private const val CREST_RETRY_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L

        /**
         * TheSportsDB allows a few dozen lookups a minute; one batch takes a slice of them.
         * Each attempt is now at most three spellings, so a batch of 45 costs about what 25
         * used to, while a sweep of four batches reaches the whole day view instead of its
         * front quarter.
         */
        private const val SPORTSDB_ATTEMPTS_PER_BATCH = 45

        /** Where the last sweep stopped, and when; read by the crest diagnostic. */
        internal const val CREST_LAST_ERROR_MARKER = "crest-last-error"

        /** Batches a single sync works through; each one is bounded by the source's rate. */
        private const val CREST_BATCHES_PER_SYNC = 3

        /** Badges are repaired in batches, so the batch runs hourly until none are left. */
        private const val CREST_BACKFILL_INTERVAL_MS = 60L * 60L * 1000L
        private const val STANDINGS_MARKER = "standings"

        /** How often one competition's table is asked for when the reader opens its page. */
        private const val TABLE_ATTEMPT_MARKER = "standings-attempt:"
        private const val TABLE_ATTEMPT_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val DETAILS_TTL_MS = 90_000L
        private const val HISTORY_LOOKBACK_DAYS = 420L

        /** Abort a provider pass after this many identical consecutive failures. */
        private const val MAX_REPEATED_FAILURES = 3

        /**
         * How many competitions are fetched at once. Six keeps the request rate human on
         * a public endpoint while removing the idle time between documents.
         */
        private const val PARALLEL_SYNCS = 16

        /** Errors that mean "this provider cannot serve this request at all". */
        private val FATAL_CODES = setOf(
            ErrorCode.INVALID_API_KEY,
            ErrorCode.MISSING_API_KEY,
            ErrorCode.QUOTA_EXHAUSTED,
            ErrorCode.RATE_LIMITED,
            ErrorCode.ACCESS_DENIED,
            ErrorCode.SUBSCRIPTION_INACTIVE,
            ErrorCode.PLAN_RESTRICTED,
        )
    }
}
