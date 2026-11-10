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
import com.footballai.app.data.local.entity.TeamEntity
import com.footballai.app.data.local.mapper.Ids
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.data.local.mapper.toEntity
import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.RemoteHistoryMatch
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.StatisticType
import androidx.room.withTransaction
import com.footballai.app.data.remote.CsvFixtureIdentity
import com.footballai.app.data.remote.FootballDataCoUkProvider
import java.time.Instant
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
) {

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
                    )
                }
                db.competitionDao().upsertAll(entities)
                settingsRepository.setLastSync(now)
                Outcome.Success(entities.size)
            }
        }
    }

    suspend fun syncFixtures(
        providerId: String,
        competitionKeys: List<String>? = null,
        daysBack: Int = 7,
        daysAhead: Int = 21,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome<SyncSummary> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        val now = clock.nowEpochMs()
        val from = now - daysBack * TimeConstants.DAY_MS
        val to = now + daysAhead * TimeConstants.DAY_MS

        var summary = syncWithProvider(provider, competitionKeys, from, to, now, onProgress)
        var fallbackProviderId: String? = null

        // An authenticated provider can legitimately return nothing (plan restrictions,
        // exhausted quota, a rejected season). The keyless CSV source is then used so the
        // user still sees fixtures, and the UI explains where they came from.
        if (summary.fixtures == 0) {
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
                        errors = summary.errors,
                    )
                    appendFinishedMatches(keyless.info.id)
                }
            }
        }

        if (summary.fixtures > 0 || summary.errors.isEmpty()) {
            settingsRepository.setLastSync(now)
        }
        val appended = appendFinishedMatches(provider.info.id)
        summary += syncPendingCsvResults()
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
        val errors = mutableListOf<String>()
        var lastErrorCode: ErrorCode? = null
        var repeatedFailures = 0
        onProgress(0, keys.size)
        for ((index, key) in keys.withIndex()) {
            when (val result = provider.matches(key, null, fromEpochMs, toEpochMs)) {
                is Outcome.Failure -> {
                    errors.add("${provider.info.displayName}/$key: ${result.error.code}")
                    repeatedFailures = if (result.error.code == lastErrorCode) repeatedFailures + 1 else 1
                    lastErrorCode = result.error.code
                }
                is Outcome.Success -> {
                    repeatedFailures = 0
                    lastErrorCode = null
                    val written = persistMatches(provider.info.id, key, result.value, now)
                    summary = summary + SyncSummary(fixtures = written)
                }
            }
            // Free providers space requests out, so a full run can take a while; the
            // caller wants to show which competition it is on.
            onProgress(index + 1, keys.size)
            // A plan restriction fails for every competition in exactly the same way, so
            // stop instead of spending minutes (and quota) on a provider that cannot serve
            // this request at all.
            if (repeatedFailures >= MAX_REPEATED_FAILURES) break
        }
        return summary.copy(errors = errors)
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
        if (existingCompetition == null) {
            db.competitionDao().upsertAll(
                listOf(
                    CompetitionEntity(
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
                    ),
                ),
            )
        }

        val teams = LinkedHashMap<String, TeamEntity>()
        for (match in matches) {
            teams[Ids.team(providerId, match.home.providerKey)] = match.home.toEntity(providerId, now)
            teams[Ids.team(providerId, match.away.providerKey)] = match.away.toEntity(providerId, now)
        }
        db.teamDao().upsertAll(teams.values.toList())

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
        private const val DETAILS_TTL_MS = 90_000L
        private const val HISTORY_LOOKBACK_DAYS = 420L

        /** Abort a provider pass after this many identical consecutive failures. */
        private const val MAX_REPEATED_FAILURES = 3
    }
}
