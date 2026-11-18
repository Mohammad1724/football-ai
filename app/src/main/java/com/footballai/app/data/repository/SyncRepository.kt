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
                    )
                }
                if (rows.isNotEmpty()) db.standingDao().insertAll(rows)
                Outcome.Success(rows.size)
            }
        }
    }

    /** Repair crestless clubs in fixtures, prioritising matches closest to today.
     * A completed search without a match cools down per team so it cannot starve the queue.
     * Network/quota errors stop the batch without marking the team as searched.
     */
    suspend fun backfillCrests(
        providerId: String = EspnOpenProvider.PROVIDER_ID,
        limit: Int = 60,
        force: Boolean = false,
        priorityEpochMs: Long? = null,
    ): Int = crestBackfillBatch(providerId, limit, force, priorityEpochMs, null, null).filled

    /** Continue through multiple bounded batches for the selected day, not just the first 30. */
    suspend fun backfillCrestsForDay(fromEpochMs: Long, toEpochMs: Long) {
        prefillLeagueCrests(fromEpochMs, toEpochMs)
        repeat(4) {
            val batch = crestBackfillBatch(
                EspnOpenProvider.PROVIDER_ID, 40, true,
                fromEpochMs + (toEpochMs - fromEpochMs) / 2, fromEpochMs, toEpochMs,
            )
            if (batch.failed || batch.attempted < 40) return
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

    private data class CrestBatch(val filled: Int, val attempted: Int, val failed: Boolean = false)

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
        for (team in db.teamDao().crestlessInFixtures(
            priorityEpochMs ?: now, now - CREST_RETRY_INTERVAL_MS, limit, fromEpochMs, toEpochMs,
        )) {
            val competitionKeys = db.teamDao().fixtureCompetitionKeys(team.id)
            val slugs = competitionKeys
                .mapNotNull { key -> com.footballai.app.data.remote.LeagueCrestIndex.LEAGUES[key] }
                .distinct()
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
            val searchWorthwhile = url == null && !roster.matched
            for (query in if (searchWorthwhile) queries else emptyList()) {
                when (val result = provider.crestForTeamName(query, slugs.firstOrNull())) {
                    is Outcome.Success -> url = result.value
                    // In particular, do not consume the rest of the batch on quota errors.
                    is Outcome.Failure -> return@withLock CrestBatch(filled, attempted, failed = true)
                }
                if (url != null) break
            }
            // This source is rate limited to a few dozen lookups a minute, so a batch takes
            // a bounded slice and the rest of the queue continues on the next run — the team
            // keeps its place instead of being marked as tried.
            if (url == null && sportsDbAttempts >= SPORTSDB_ATTEMPTS_PER_BATCH) break
            // Only a verified name or published alias, with unambiguous gender.
            if (url == null) {
                val gender = db.teamDao().fixtureGenders(team.id).singleOrNull()
                if (gender != null) {
                    sportsDbAttempts++
                    for (query in (listOfNotNull(alias, team.name) + queries).distinct()) {
                        when (val result = sportsDbCrests.find(query, gender)) {
                            is Outcome.Success -> url = result.value
                            is Outcome.Failure -> return@withLock CrestBatch(filled, attempted, failed = true)
                        }
                        if (url != null) break
                    }
                }
            }
            if (url != null) filled += db.teamDao().setCrestIfMissing(team.id, url)
            // Bumped to v7 with the fallback source's name rules: a club that was refused
            // because its published name was `Billericay Town` and the feed says `Billericay`
            // is asked once more instead of cooling down for a week.
            db.syncMetaDao().put(SyncMetaEntity(CREST_ATTEMPT_PREFIX + team.id, "checked", clock.nowEpochMs()))
            attempted++
        }
        if (priorityEpochMs == null) {
            db.syncMetaDao().put(SyncMetaEntity(CREST_BACKFILL_MARKER, "ok", clock.nowEpochMs()))
        }
        CrestBatch(filled, attempted)
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
                        val inserted = persistHistory(
                            source = com.footballai.app.data.remote.FootballDataCoUkProvider.PROVIDER_ID,
                            competitionId = Ids.competition(provider.info.id, key),
                            competitionName = competitionName,
                            rows = result.value,
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

        /** TheSportsDB allows a few dozen lookups a minute; one batch takes a slice of them. */
        private const val SPORTSDB_ATTEMPTS_PER_BATCH = 25

        /** Kept in step with the `crest-attempt-v7:` literal in [com.footballai.app.data.local.dao.TeamDao]. */
        private const val CREST_ATTEMPT_PREFIX = "crest-attempt-v7:"

        /** Badges are repaired in batches, so the batch runs hourly until none are left. */
        private const val CREST_BACKFILL_INTERVAL_MS = 60L * 60L * 1000L
        private const val STANDINGS_MARKER = "standings"
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
