package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.RemoteCompetition
import com.footballai.app.data.provider.RemoteEvent
import com.footballai.app.data.provider.RemoteLineup
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteStandingRow
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.data.provider.RemoteTeamStatistics
import com.footballai.app.data.remote.dto.AfEnvelope
import com.footballai.app.data.remote.dto.AfEventResponse
import com.footballai.app.data.remote.dto.AfFixtureResponse
import com.footballai.app.data.remote.dto.AfLineupResponse
import com.footballai.app.data.remote.dto.AfStandingsResponse
import com.footballai.app.data.remote.dto.AfStatisticsResponse
import com.footballai.app.domain.model.LineupPlayer
import com.footballai.app.domain.model.MatchEventType
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import com.footballai.app.domain.model.StatisticType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * API-Football provider (api-sports.io).
 *
 * Free plan reality (verified against the provider documentation):
 *  - 100 requests per day, 10 requests per minute,
 *  - all endpoints available, historical seasons limited,
 *  - live fixtures, events, statistics and lineups included.
 *
 * The API key is supplied by the user at runtime and never bundled in the APK.
 */
class ApiFootballProvider(
    private val client: ApiClient,
    private val apiKeyProvider: suspend () -> String?,
    private val json: Json = defaultJson(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : FootballDataProvider {

    override val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "API-Football",
        requiresApiKey = true,
        capabilities = ProviderCapabilities(
            liveScores = true,
            matchStatistics = true,
            events = true,
            lineups = true,
            injuries = true,
            odds = false,
            standings = true,
            historicalSeasons = 3,
        ),
        quota = QuotaPolicy(requestsPerMinute = 10, requestsPerDay = 100),
        attribution = "Data provided by API-Football (api-sports.io)",
    )

    override suspend fun competitions(): Outcome<List<RemoteCompetition>> =
        Outcome.Success(CURATED_LEAGUES)

    override suspend fun matches(
        competitionKey: String,
        season: Int?,
        fromEpochMs: Long?,
        toEpochMs: Long?,
    ): Outcome<List<RemoteMatch>> {
        val query = LinkedHashMap<String, String>()
        query["league"] = competitionKey
        query["season"] = (season ?: currentSeasonStartYear()).toString()
        if (fromEpochMs != null) query["from"] = isoDate(fromEpochMs)
        if (toEpochMs != null) query["to"] = isoDate(toEpochMs)
        return request("$baseUrl/fixtures", query) { body -> parseFixtures(body) }
    }

    override suspend fun liveMatches(): Outcome<List<RemoteMatch>> =
        request("$baseUrl/fixtures", mapOf("live" to "all")) { body -> parseFixtures(body) }

    override suspend fun matchStatistics(matchKey: String): Outcome<List<RemoteTeamStatistics>> =
        request("$baseUrl/fixtures/statistics", mapOf("fixture" to matchKey)) { body ->
            val envelope = json.decodeFromString<AfEnvelope<AfStatisticsResponse>>(body)
            envelope.response.mapNotNull { entry ->
                val teamKey = entry.team?.id?.toString() ?: return@mapNotNull null
                val values = LinkedHashMap<StatisticType, Double>()
                for (statistic in entry.statistics) {
                    val type = mapStatisticType(statistic.type) ?: continue
                    val value = parseNumeric(statistic.value) ?: continue
                    values[type] = value
                }
                RemoteTeamStatistics(teamKey = teamKey, values = values)
            }
        }

    override suspend fun matchEvents(matchKey: String): Outcome<List<RemoteEvent>> =
        request("$baseUrl/fixtures/events", mapOf("fixture" to matchKey)) { body ->
            val envelope = json.decodeFromString<AfEnvelope<AfEventResponse>>(body)
            envelope.response.map { event ->
                RemoteEvent(
                    providerKey = null,
                    minute = event.time?.elapsed ?: 0,
                    type = mapEventType(event.type, event.detail),
                    detail = event.detail,
                    teamKey = event.team?.id?.toString(),
                    playerName = event.player?.name,
                    assistName = event.assist?.name,
                )
            }
        }

    override suspend fun lineups(matchKey: String): Outcome<List<RemoteLineup>> =
        request("$baseUrl/fixtures/lineups", mapOf("fixture" to matchKey)) { body ->
            val envelope = json.decodeFromString<AfEnvelope<AfLineupResponse>>(body)
            envelope.response.mapNotNull { entry ->
                val teamKey = entry.team?.id?.toString() ?: return@mapNotNull null
                val starters = entry.startXi.mapNotNull { slot ->
                    val player = slot.player ?: return@mapNotNull null
                    LineupPlayer(
                        name = player.name ?: return@mapNotNull null,
                        number = player.number,
                        position = player.pos,
                        isStarter = true,
                    )
                }
                val substitutes = entry.substitutes.mapNotNull { slot ->
                    val player = slot.player ?: return@mapNotNull null
                    LineupPlayer(
                        name = player.name ?: return@mapNotNull null,
                        number = player.number,
                        position = player.pos,
                        isStarter = false,
                    )
                }
                RemoteLineup(teamKey = teamKey, formation = entry.formation, players = starters + substitutes)
            }
        }

    override suspend fun standings(competitionKey: String, season: Int): Outcome<List<RemoteStandingRow>> {
        val query = mapOf("league" to competitionKey, "season" to season.toString())
        return request("$baseUrl/standings", query) { body ->
            val envelope = json.decodeFromString<AfEnvelope<AfStandingsResponse>>(body)
            envelope.response.flatMap { entry ->
                entry.league?.standings?.flatten().orEmpty().mapNotNull { row ->
                    val team = row.team ?: return@mapNotNull null
                    RemoteStandingRow(
                        rank = row.rank ?: return@mapNotNull null,
                        teamKey = team.id?.toString() ?: return@mapNotNull null,
                        teamName = team.name ?: "",
                        crestUrl = team.logo,
                        points = row.points ?: 0,
                        played = row.all?.played ?: 0,
                        goalDifference = row.goalsDiff ?: 0,
                        form = row.form,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun <T> request(
        url: String,
        query: Map<String, String>,
        parser: (String) -> List<T>?,
    ): Outcome<List<T>> {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return Outcome.Failure(
                AppError(code = ErrorCode.MISSING_API_KEY, detail = "An API-Football key is required"),
            )
        }
        return when (val response = client.get(PROVIDER_ID, info.quota, url, headers(key), query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val envelopeErrors = extractErrors(response.value)
                if (envelopeErrors != null) {
                    Outcome.Failure(AppError(code = ErrorCode.INVALID_API_KEY, detail = envelopeErrors))
                } else {
                    try {
                        val parsed = parser(response.value)
                        if (parsed == null) {
                            Outcome.Failure(
                                AppError(code = ErrorCode.PARSE_ERROR, detail = "Unreadable API-Football response"),
                            )
                        } else {
                            Outcome.Success(parsed)
                        }
                    } catch (error: Exception) {
                        Outcome.Failure(
                            AppError(code = ErrorCode.PARSE_ERROR, detail = error.message, cause = error),
                        )
                    }
                }
            }
        }
    }

    private fun headers(key: String): Map<String, String> = mapOf("x-apisports-key" to key)

    /** API-Football reports logical failures inside a 200 response. */
    private fun extractErrors(body: String): String? = try {
        val root = json.parseToJsonElement(body)
        val errors = (root as? JsonObject)?.get("errors")
        when (errors) {
            null -> null
            is JsonObject -> if (errors.isEmpty()) null else errors.toString()
            else -> errors.toString().takeIf { it.length > 2 && it != "[]" && it != "{}" }
        }
    } catch (error: Exception) {
        null
    }

    private fun parseFixtures(body: String): List<RemoteMatch> {
        val envelope = json.decodeFromString<AfEnvelope<AfFixtureResponse>>(body)
        return envelope.response.mapNotNull { entry ->
            val fixture = entry.fixture ?: return@mapNotNull null
            val id = fixture.id?.toString() ?: return@mapNotNull null
            val home = entry.teams?.home ?: return@mapNotNull null
            val away = entry.teams?.away ?: return@mapNotNull null
            val kickoff = parseDate(fixture.date) ?: return@mapNotNull null
            val goals = entry.goals
            RemoteMatch(
                providerKey = id,
                competitionKey = entry.league?.id?.toString() ?: "",
                competitionName = entry.league?.name ?: "",
                season = entry.league?.season,
                matchday = null,
                kickoffEpochMs = kickoff,
                status = mapStatus(fixture.status?.short),
                minute = fixture.status?.elapsed,
                home = RemoteTeam(
                    providerKey = home.id?.toString() ?: return@mapNotNull null,
                    name = home.name ?: "",
                    shortName = null,
                    crestUrl = home.logo,
                ),
                away = RemoteTeam(
                    providerKey = away.id?.toString() ?: return@mapNotNull null,
                    name = away.name ?: "",
                    shortName = null,
                    crestUrl = away.logo,
                ),
                homeGoals = goals?.home,
                awayGoals = goals?.away,
                homeHalfTimeGoals = entry.score?.halftime?.home,
                awayHalfTimeGoals = entry.score?.halftime?.away,
                venue = fixture.venue?.name ?: fixture.venue?.city,
                odds = null,
            )
        }
    }

    private fun parseNumeric(element: JsonElement?): Double? {
        if (element == null) return null
        val primitive = element as? JsonPrimitive ?: return null
        val text = primitive.content.trim().removeSuffix("%").replace(",", "")
        return text.toDoubleOrNull()
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (error: Exception) {
            try {
                LocalDate.parse(value.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (inner: Exception) {
                null
            }
        }
    }

    private fun isoDate(epochMs: Long): String = DateTimeFormatter.ISO_LOCAL_DATE
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate())

    private fun currentSeasonStartYear(): Int {
        val now = java.time.ZonedDateTime.now(ZoneOffset.UTC)
        return if (now.monthValue >= 7) now.year else now.year - 1
    }

    private fun mapStatisticType(type: String?): StatisticType? = when (type?.lowercase()?.trim()) {
        "ball possession" -> StatisticType.POSSESSION
        "total shots" -> StatisticType.SHOTS
        "shots on goal", "shots on target" -> StatisticType.SHOTS_ON_TARGET
        "corner kicks" -> StatisticType.CORNERS
        "yellow cards" -> StatisticType.YELLOW_CARDS
        "red cards" -> StatisticType.RED_CARDS
        "expected_goals", "expected goals (xg)" -> StatisticType.EXPECTED_GOALS
        "fouls" -> StatisticType.FOULS
        "offsides" -> StatisticType.OFFSIDES
        "goalkeeper saves" -> StatisticType.SAVES
        else -> null
    }

    private fun mapEventType(type: String?, detail: String?): MatchEventType {
        val normalized = type?.lowercase().orEmpty()
        val detailNormalized = detail?.lowercase().orEmpty()
        return when {
            normalized == "goal" && detailNormalized.contains("own") -> MatchEventType.OWN_GOAL
            normalized == "goal" && detailNormalized.contains("penalty") -> MatchEventType.PENALTY_GOAL
            normalized == "goal" -> MatchEventType.GOAL
            normalized == "card" && detailNormalized.contains("red") -> MatchEventType.RED_CARD
            normalized == "card" && detailNormalized.contains("second") -> MatchEventType.SECOND_YELLOW
            normalized == "card" -> MatchEventType.YELLOW_CARD
            normalized == "subst" -> MatchEventType.SUBSTITUTION
            normalized == "var" -> MatchEventType.VAR
            normalized.contains("missed") -> MatchEventType.PENALTY_MISSED
            else -> MatchEventType.OTHER
        }
    }

    companion object {
        const val PROVIDER_ID = "api-football"
        const val DEFAULT_BASE_URL = "https://v3.football.api-sports.io"

        private fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        /**
         * Curated list of league ids that API-Football exposes. Fetching the complete
         * catalogue costs requests, so the app ships a practical subset and lets the
         * user add further league ids in the data-source screen.
         */
        val CURATED_LEAGUES: List<RemoteCompetition> = listOf(
            RemoteCompetition("39", "Premier League", "England", null, null),
            RemoteCompetition("40", "Championship", "England", null, null),
            RemoteCompetition("140", "La Liga", "Spain", null, null),
            RemoteCompetition("135", "Serie A", "Italy", null, null),
            RemoteCompetition("78", "Bundesliga", "Germany", null, null),
            RemoteCompetition("61", "Ligue 1", "France", null, null),
            RemoteCompetition("88", "Eredivisie", "Netherlands", null, null),
            RemoteCompetition("94", "Primeira Liga", "Portugal", null, null),
            RemoteCompetition("203", "Super Lig", "Turkey", null, null),
            RemoteCompetition("144", "Jupiler Pro League", "Belgium", null, null),
            RemoteCompetition("179", "Premiership", "Scotland", null, null),
            RemoteCompetition("2", "UEFA Champions League", "Europe", null, null),
            RemoteCompetition("3", "UEFA Europa League", "Europe", null, null),
            RemoteCompetition("848", "UEFA Europa Conference League", "Europe", null, null),
            RemoteCompetition("13", "CONMEBOL Libertadores", "South America", null, null),
            RemoteCompetition("71", "Serie A", "Brazil", null, null),
            RemoteCompetition("128", "Liga Profesional Argentina", "Argentina", null, null),
            RemoteCompetition("253", "Major League Soccer", "USA", null, null),
            RemoteCompetition("307", "Saudi Pro League", "Saudi Arabia", null, null),
            RemoteCompetition("98", "J1 League", "Japan", null, null),
            RemoteCompetition("292", "K League 1", "South Korea", null, null),
            RemoteCompetition("103", "Eliteserien", "Norway", null, null),
            RemoteCompetition("113", "Allsvenskan", "Sweden", null, null),
            RemoteCompetition("119", "Superliga", "Denmark", null, null),
            RemoteCompetition("262", "Liga MX", "Mexico", null, null),
            RemoteCompetition("169", "Super League", "China", null, null),
        )

        /** Human readable name for a curated league id, used when a payload omits it. */
        fun leagueName(leagueKey: String): String =
            CURATED_LEAGUES.firstOrNull { it.providerKey == leagueKey }?.name ?: "League $leagueKey"

        fun mapStatus(short: String?): MatchStatus = when (short?.uppercase()) {
            "TBD", "NS" -> MatchStatus.SCHEDULED
            "1H", "2H", "ET", "BT", "P", "LIVE", "INT" -> MatchStatus.LIVE
            "HT" -> MatchStatus.HALF_TIME
            "FT", "AET", "PEN", "AWD", "WO", "ABD" -> MatchStatus.FINISHED
            "PST" -> MatchStatus.POSTPONED
            "SUSP" -> MatchStatus.SUSPENDED
            "CANC" -> MatchStatus.CANCELLED
            else -> MatchStatus.UNKNOWN
        }

        val LIVE_POLL_INTERVAL_MS = TimeConstants.MINUTE_MS
    }
}
