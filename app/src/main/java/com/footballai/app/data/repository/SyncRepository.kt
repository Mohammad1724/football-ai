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
import com.footballai.app.data.provider.RemoteHistoryMatch
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.StatisticType
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
    ): Outcome<SyncSummary> {
        val provider = registry.provider(providerId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown provider $providerId"))
        val settings = settingsRepository.current()
        val keys = competitionKeys?.takeIf { it.isNotEmpty() }
            ?: settings.followedCompetitionKeys.toList()
        if (keys.isEmpty()) {
            return Outcome.Failure(
                AppError(ErrorCode.EMPTY_RESPONSE, detail = "No followed competitions to synchronise"),
            )
        }

        val now = clock.nowEpochMs()
        val from = now - daysBack * TimeConstants.DAY_MS
        val to = now + daysAhead * TimeConstants.DAY_MS
        var summary = SyncSummary(requestsEstimated = keys.size)
        val errors = mutableListOf<String>()

        for (key in keys) {
            when (val result = provider.matches(key, season = null, fromEpochMs = from, toEpochMs = to)) {
                is Outcome.Failure -> errors.add("${provider.info.displayName}/$key: ${result.error.code}")
                is Outcome.Success -> {
                    val written = persistMatches(providerId, key, result.value)
                    summary = summary + SyncSummary(fixtures = written)
                }
            }
        }

        settingsRepository.setLastSync(now)
        val appended = appendFinishedMatches(providerId)
        ratingsRepository.invalidate()
        return Outcome.Success(summary.copy(historical = appended, errors = errors))
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
        return when (val result = provider.match(match.providerKey)) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val remote = result.value
                    ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Fixture not found upstream"))
                val competitionKey = match.competitionId.substringAfterLast(':')
                val written = persistMatches(match.providerId, competitionKey, listOf(remote))
                appendFinishedMatches(match.providerId)
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
                            competitionId = "csv:$key",
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
                        val finished = result.value.filter { it.status == MatchStatus.FINISHED }
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
            .filter { it.providerId == source }
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
            match.toEntity(
                providerId = providerId,
                homeTeamId = Ids.team(providerId, match.home.providerKey),
                awayTeamId = Ids.team(providerId, match.away.providerKey),
                competitionId = competitionId,
                now = now,
            )
        }
        db.matchDao().upsertAll(entities)
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
                    homeId = row.homeTeamKey,
                    awayId = row.awayTeamKey,
                    kickoff = row.kickoffEpochMs,
                ),
                source = source,
                competitionId = competitionId,
                competitionName = competitionName,
                homeTeamId = row.homeTeamKey,
                awayTeamId = row.awayTeamKey,
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
        val inserted = db.historicalDao().insertAll(entities)
        return inserted.count { it != -1L }
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
    }
}
