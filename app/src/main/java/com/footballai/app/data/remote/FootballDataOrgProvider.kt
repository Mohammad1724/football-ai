package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.RemoteCompetition
import com.footballai.app.data.provider.RemoteEvent
import com.footballai.app.data.provider.RemoteLineup
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteStandingRow
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.data.provider.RemoteTeamStatistics
import com.footballai.app.data.remote.dto.FdoCompetitionsResponse
import com.footballai.app.data.remote.dto.FdoMatchesResponse
import com.footballai.app.data.remote.dto.FdoStandingsResponse
import com.footballai.app.domain.model.MatchOdds
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * football-data.org v4 provider.
 *
 * Free tier reality (verified against the provider documentation):
 *  - 10 requests per minute, 12 competitions,
 *  - fixtures, results and standings included,
 *  - scores are delayed, and there are no match statistics, lineups or events,
 *  - historical depth is limited on the free plan.
 *
 * The provider therefore advertises exactly what it can deliver; the UI hides live
 * analysis affordances when only this provider is configured.
 */
class FootballDataOrgProvider(
    private val client: ApiClient,
    private val apiKeyProvider: suspend () -> String?,
    private val json: Json = defaultJson(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : FootballDataProvider {

    override val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "football-data.org",
        requiresApiKey = true,
        capabilities = ProviderCapabilities(
            liveScores = false,
            matchStatistics = false,
            events = false,
            lineups = false,
            injuries = false,
            odds = false,
            standings = true,
            historicalSeasons = 1,
        ),
        quota = QuotaPolicy(requestsPerMinute = 10, requestsPerDay = 0),
        attribution = "Football data provided by the Football-Data.org API",
    )

    override suspend fun competitions(): Outcome<List<RemoteCompetition>> {
        val result = get("$baseUrl/competitions")
        return when (result) {
            is Outcome.Failure -> result
            is Outcome.Success -> try {
                val payload = json.decodeFromString<FdoCompetitionsResponse>(result.value)
                Outcome.Success(
                    payload.competitions.mapNotNull { competition ->
                        val code = competition.code ?: return@mapNotNull null
                        RemoteCompetition(
                            providerKey = code,
                            name = competition.name ?: code,
                            country = competition.area?.name,
                            emblemUrl = competition.emblem,
                            currentSeason = competition.currentSeason?.startDate?.take(4)?.toIntOrNull(),
                        )
                    },
                )
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
            }
        }
    }

    override suspend fun matches(
        competitionKey: String,
        season: Int?,
        fromEpochMs: Long?,
        toEpochMs: Long?,
    ): Outcome<List<RemoteMatch>> {
        val query = LinkedHashMap<String, String>()
        if (season != null) query["season"] = season.toString()
        if (fromEpochMs != null) query["dateFrom"] = isoDate(fromEpochMs)
        if (toEpochMs != null) query["dateTo"] = isoDate(toEpochMs)
        val result = get("$baseUrl/competitions/$competitionKey/matches", query)
        return when (result) {
            is Outcome.Failure -> result
            is Outcome.Success -> try {
                val payload = json.decodeFromString<FdoMatchesResponse>(result.value)
                Outcome.Success(payload.matches.mapNotNull { it.toRemoteMatch(competitionKey) })
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
            }
        }
    }

    override suspend fun liveMatches(): Outcome<List<RemoteMatch>> =
        Outcome.Failure(
            AppError(
                code = ErrorCode.REQUEST_FAILED,
                detail = "football-data.org does not serve real-time scores on the free plan",
            ),
        )

    override suspend fun matchStatistics(matchKey: String): Outcome<List<RemoteTeamStatistics>> =
        Outcome.Success(emptyList())

    override suspend fun matchEvents(matchKey: String): Outcome<List<RemoteEvent>> =
        Outcome.Success(emptyList())

    override suspend fun lineups(matchKey: String): Outcome<List<RemoteLineup>> =
        Outcome.Success(emptyList())

    override suspend fun standings(competitionKey: String, season: Int): Outcome<List<RemoteStandingRow>> {
        val result = get("$baseUrl/competitions/$competitionKey/standings", mapOf("season" to season.toString()))
        return when (result) {
            is Outcome.Failure -> result
            is Outcome.Success -> try {
                val payload = json.decodeFromString<FdoStandingsResponse>(result.value)
                val rows = payload.standings
                    .firstOrNull { it.type.equals("TOTAL", ignoreCase = true) }
                    ?.table
                    ?: payload.standings.firstOrNull()?.table.orEmpty()
                Outcome.Success(
                    rows.mapNotNull { row ->
                        val team = row.team ?: return@mapNotNull null
                        val teamKey = team.id?.toString() ?: return@mapNotNull null
                        RemoteStandingRow(
                            rank = row.position ?: return@mapNotNull null,
                            teamKey = teamKey,
                            teamName = team.name ?: team.shortName ?: "",
                            crestUrl = team.crest,
                            points = row.points ?: 0,
                            played = row.playedGames ?: 0,
                            goalDifference = row.goalDifference ?: 0,
                            form = row.form,
                        )
                    },
                )
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
            }
        }
    }

    private suspend fun get(url: String, query: Map<String, String> = emptyMap()): Outcome<String> {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return Outcome.Failure(
                AppError(code = ErrorCode.MISSING_API_KEY, detail = "A football-data.org token is required"),
            )
        }
        return client.get(PROVIDER_ID, info.quota, url, mapOf("X-Auth-Token" to key), query)
    }

    private fun isoDate(epochMs: Long): String = DateTimeFormatter.ISO_LOCAL_DATE
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneOffset.UTC).toLocalDate())

    private fun com.footballai.app.data.remote.dto.FdoMatch.toRemoteMatch(competitionKey: String): RemoteMatch? {
        val home = homeTeam ?: return null
        val away = awayTeam ?: return null
        val homeKey = home.id?.toString() ?: return null
        val awayKey = away.id?.toString() ?: return null
        val kickoff = parseDate(utcDate) ?: return null
        val fullTime = score?.fullTime
        return RemoteMatch(
            providerKey = id?.toString() ?: return null,
            competitionKey = competition?.code ?: competitionKey,
            competitionName = competition?.name ?: "",
            season = competition?.currentSeason?.startDate?.take(4)?.toIntOrNull(),
            matchday = matchday,
            kickoffEpochMs = kickoff,
            status = mapStatus(status),
            minute = null,
            home = RemoteTeam(providerKey = homeKey, name = home.name ?: "", shortName = home.shortName, crestUrl = home.crest),
            away = RemoteTeam(providerKey = awayKey, name = away.name ?: "", shortName = away.shortName, crestUrl = away.crest),
            homeGoals = fullTime?.home,
            awayGoals = fullTime?.away,
            homeHalfTimeGoals = score?.halfTime?.home,
            awayHalfTimeGoals = score?.halfTime?.away,
            venue = venue,
            odds = null as MatchOdds?,
        )
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (error: Exception) {
            try {
                LocalDate.parse(value.take(10)).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (inner: Exception) {
                null
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "football-data-org"
        const val DEFAULT_BASE_URL = "https://api.football-data.org/v4"

        private fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun mapStatus(status: String?): MatchStatus = when (status?.uppercase()) {
            "SCHEDULED", "TIMED" -> MatchStatus.SCHEDULED
            "IN_PLAY" -> MatchStatus.LIVE
            "PAUSED" -> MatchStatus.HALF_TIME
            "FINISHED", "AWARDED" -> MatchStatus.FINISHED
            "POSTPONED" -> MatchStatus.POSTPONED
            "SUSPENDED" -> MatchStatus.SUSPENDED
            "CANCELLED" -> MatchStatus.CANCELLED
            else -> MatchStatus.UNKNOWN
        }
    }
}
