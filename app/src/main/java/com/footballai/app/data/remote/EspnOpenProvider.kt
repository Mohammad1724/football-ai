package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.RemoteCompetition
import com.footballai.app.data.provider.RemoteEvent
import com.footballai.app.data.provider.RemoteLineup
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteStandingRow
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.data.provider.RemoteTeamStatistics
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Keyless, worldwide fixture source.
 *
 * The free providers in this app are limited: football-data.co.uk only publishes
 * English and Scottish club leagues, football-data.org's free plan has twelve
 * competitions, and API-Football's free plan cannot serve the current season at all.
 * None of them can answer "what is on today" for national teams or women's football.
 * ESPN's public scoreboard endpoint can, without a key and without a quota, so it is
 * offered as an explicit, clearly attributed source.
 *
 * Honesty rules kept here:
 * - The competition list below is fixed and named; nothing is discovered by guessing.
 * - A missing score is `null`, never 0.
 * - A knockout tie whose score may include extra time or penalties is marked
 *   `UNVERIFIED`, so it can never train or settle a 90-minute market.
 * - Statistics, lineups, standings and live polling are not offered, and the provider
 *   advertises exactly that.
 */
class EspnOpenProvider(
    private val client: ApiClient,
    private val clock: TimeProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : FootballDataProvider {

    companion object {
        const val PROVIDER_ID = "espn-open"
        const val DEFAULT_BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/soccer"

        /**
         * Days fetched per competition per sync: yesterday, today and the next two.
         *
         * The endpoint has no date-range form, so one request covers one day of one
         * competition. The window is deliberately small and bounded; asking for the
         * whole ten-day strip would multiply the request count for no benefit.
         */
        const val WINDOW_DAYS = 4

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * Curated international and club competitions, with the names ESPN publishes.
         *
         * `knockout = true` marks competitions whose later rounds carry extra-time and
         * penalty scores; those events are stored as unverified instead of pretending
         * the score is a 90-minute result.
         */
        val COMPETITIONS: List<EspnCompetitionInfo> = listOf(
            // National teams first: this is the coverage no other free source in the
            // app can provide. Every slug below was verified against the live endpoint.
            EspnCompetitionInfo("uefa.nations", "UEFA Nations League", "Europe", null, knockout = false),
            EspnCompetitionInfo("uefa.w.nations", "UEFA Women's Nations League", "Europe", null, knockout = false),
            EspnCompetitionInfo("fifa.friendly", "International Friendlies", "World", null, knockout = false),
            EspnCompetitionInfo("fifa.worldq.uefa", "World Cup Qualifiers (UEFA)", "Europe", null, knockout = true),
            EspnCompetitionInfo("fifa.world", "FIFA World Cup", "World", null, knockout = true),
            EspnCompetitionInfo("uefa.euro", "UEFA European Championship", "Europe", null, knockout = true),
            EspnCompetitionInfo("concacaf.nations.league", "CONCACAF Nations League", "North America", null, knockout = false),
            EspnCompetitionInfo("fifa.wwc", "FIFA Women's World Cup", "World", null, knockout = true),
            EspnCompetitionInfo("uefa.weuro", "UEFA Women's Euro", "Europe", null, knockout = true),
            // Clubs.
            EspnCompetitionInfo("uefa.champions", "UEFA Champions League", "Europe", null, knockout = true),
            EspnCompetitionInfo("uefa.europa", "UEFA Europa League", "Europe", null, knockout = true),
            EspnCompetitionInfo("uefa.wchampions", "UEFA Women's Champions League", "Europe", null, knockout = true),
            EspnCompetitionInfo("eng.1", "Premier League", "England", null, knockout = false),
            EspnCompetitionInfo("esp.1", "LaLiga", "Spain", null, knockout = false),
            EspnCompetitionInfo("ita.1", "Serie A", "Italy", null, knockout = false),
            EspnCompetitionInfo("ger.1", "Bundesliga", "Germany", null, knockout = false),
            EspnCompetitionInfo("fra.1", "Ligue 1", "France", null, knockout = false),
            EspnCompetitionInfo("usa.1", "Major League Soccer", "USA", null, knockout = true),
            EspnCompetitionInfo("eng.w.1", "Women's Super League", "England", null, knockout = false),
        )

        private val byKey: Map<String, EspnCompetitionInfo> = COMPETITIONS.associateBy { it.key }

        private fun defaultJson(): Json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    }

    override val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "ESPN (public scoreboard)",
        requiresApiKey = false,
        capabilities = ProviderCapabilities(
            // Fixtures, results and the current live state of a fixture are published,
            // but this provider is not polled, so no live-match endpoint is offered.
            liveScores = false,
            // One request returns one day of one competition; a single fixture cannot be
            // re-read, so the detail screen must not pretend it can.
            singleMatchRefresh = false,
            matchStatistics = false,
            events = false,
            lineups = false,
            injuries = false,
            odds = false,
            standings = false,
            historicalSeasons = 0,
        ),
        // Self-imposed, not published by the provider: one request covers one day of one
        // competition, so a full 19-competition, four-day sync is 76 requests. The rate is
        // an average over a minute — the guard allows a small burst and then paces the
        // rest — which keeps the app a light client without making a refresh wait.
        quota = QuotaPolicy(requestsPerMinute = 150, requestsPerDay = 3000),
        attribution = "Fixture data provided by ESPN (unofficial public endpoint)",
    )

    /** National-team football first: it is the part no other free source in the app has. */
    override val defaultCompetitionKeys: List<String> =
        listOf("uefa.nations", "uefa.w.nations", "fifa.friendly", "uefa.champions")

    private val json = defaultJson()

    override suspend fun competitions(): Outcome<List<RemoteCompetition>> = Outcome.Success(
        COMPETITIONS.map { competition ->
            RemoteCompetition(
                providerKey = competition.key,
                name = competition.name,
                country = competition.country,
                emblemUrl = null,
                currentSeason = LocalDate.now(ZoneId.systemDefault()).year,
            )
        },
    )

    override suspend fun matches(
        competitionKey: String,
        season: Int?,
        fromEpochMs: Long?,
        toEpochMs: Long?,
    ): Outcome<List<RemoteMatch>> = withContext(Dispatchers.IO) {
        val competition = byKey[competitionKey]
            ?: return@withContext Outcome.Failure(
                AppError(ErrorCode.NOT_FOUND, detail = "Unknown ESPN competition $competitionKey"),
            )
        val dates = windowDates(fromEpochMs, toEpochMs)
        // The endpoint has no date-range form, so the days are separate documents; they
        // are fetched together rather than one after another. The quota guard still
        // paces them.
        val results = coroutineScope {
            dates.map { date -> async { fetchDay(competition, date) } }.awaitAll()
        }
        val matches = ArrayList<RemoteMatch>()
        // One bad day must not erase the other days: a rate limit or a transient error
        // on Tuesday cannot hide the fixtures of Wednesday. A source error is only
        // reported when nothing at all could be read.
        var firstFailure: Outcome.Failure? = null
        for (result in results) {
            when (result) {
                is Outcome.Failure -> if (firstFailure == null) firstFailure = result
                is Outcome.Success -> matches.addAll(result.value)
            }
        }
        if (matches.isEmpty() && firstFailure != null) return@withContext firstFailure
        Outcome.Success(matches)
    }

    /**
     * Live polling is intentionally not implemented: it would cost one request per
     * competition per minute on an endpoint with no published limits. The status of a
     * fixture is refreshed by the regular sync instead.
     */
    override suspend fun liveMatches(): Outcome<List<RemoteMatch>> = Outcome.Success(emptyList())

    override suspend fun match(matchKey: String): Outcome<RemoteMatch?> = Outcome.Success(null)

    override suspend fun matchStatistics(matchKey: String): Outcome<List<RemoteTeamStatistics>> =
        Outcome.Success(emptyList())

    override suspend fun matchEvents(matchKey: String): Outcome<List<RemoteEvent>> =
        Outcome.Success(emptyList())

    override suspend fun lineups(matchKey: String): Outcome<List<RemoteLineup>> =
        Outcome.Success(emptyList())

    override suspend fun standings(competitionKey: String, season: Int): Outcome<List<RemoteStandingRow>> =
        Outcome.Success(emptyList())

    /** One request per day, bounded to [WINDOW_DAYS] days around today. */
    private fun windowDates(fromEpochMs: Long?, toEpochMs: Long?): List<LocalDate> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(clock.nowEpochMs()).atZone(zone).toLocalDate()
        val windowStart = today.minusDays(1)
        val windowEnd = today.plusDays((WINDOW_DAYS - 2).toLong())
        val requestedStart = fromEpochMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val requestedEnd = toEpochMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val start = maxOf(windowStart, requestedStart ?: windowStart)
        val end = minOf(windowEnd, requestedEnd ?: windowEnd)
        if (end.isBefore(start)) return emptyList()
        val dates = ArrayList<LocalDate>()
        var cursor = start
        while (!cursor.isAfter(end) && dates.size < WINDOW_DAYS) {
            dates.add(cursor)
            cursor = cursor.plusDays(1)
        }
        return dates
    }

    private suspend fun fetchDay(
        competition: EspnCompetitionInfo,
        date: LocalDate,
    ): Outcome<List<RemoteMatch>> {
        val url = "$baseUrl/${competition.key}/scoreboard"
        val query = mapOf("dates" to date.format(DATE_FORMAT))
        return when (val response = client.get(PROVIDER_ID, info.quota, url, query = query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> parse(response.value, competition)
        }
    }

    /**
     * Pure translation of one scoreboard payload. Separated from the HTTP call so the
     * mapping can be tested against a captured response without a network client.
     */
    internal fun parse(payload: String, competition: EspnCompetitionInfo): Outcome<List<RemoteMatch>> {
        val decoded = try {
            json.decodeFromString(EspnScoreboard.serializer(), payload)
        } catch (error: Exception) {
            return Outcome.Failure(
                AppError(ErrorCode.PARSE_ERROR, detail = "ESPN scoreboard ${competition.key}", cause = error),
            )
        }
        return Outcome.Success(decoded.events.mapNotNull { it.toRemoteMatch(competition) })
    }

    private fun EspnEvent.toRemoteMatch(competition: EspnCompetitionInfo): RemoteMatch? {
        val id = id ?: return null
        val event = competitions.firstOrNull() ?: return null
        // ESPN publishes "2026-11-15T14:00Z" without seconds, which Instant.parse
        // rejects; OffsetDateTime handles both forms and still yields a UTC instant.
        val kickoff = (event.date ?: date)?.let { timestamp ->
            runCatching { java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(timestamp).toEpochMilli() }
                .getOrNull()
        } ?: return null
        val home = event.competitors.firstOrNull { it.homeAway.equals("home", ignoreCase = true) } ?: return null
        val away = event.competitors.firstOrNull { it.homeAway.equals("away", ignoreCase = true) } ?: return null
        val homeTeam = home.team ?: return null
        val awayTeam = away.team ?: return null
        val statusType = event.status?.type
        val status = mapStatus(statusType)
        val hasScore = status.isFinished || status.isLive
        val regulation = scoreIsRegulation(statusType, season?.slug ?: "", competition.knockout)
        val homeGoals = if (hasScore) home.score?.toIntOrNull() else null
        val awayGoals = if (hasScore) away.score?.toIntOrNull() else null

        return RemoteMatch(
            providerKey = id,
            competitionKey = competition.key,
            competitionName = competition.name,
            season = season?.year,
            matchday = null,
            kickoffEpochMs = kickoff,
            status = status,
            minute = minuteOf(event.status),
            home = RemoteTeam(
                providerKey = homeTeam.id ?: return null,
                name = homeTeam.displayName ?: homeTeam.shortDisplayName ?: return null,
                shortName = homeTeam.abbreviation ?: homeTeam.shortDisplayName,
                crestUrl = homeTeam.logo,
            ),
            away = RemoteTeam(
                providerKey = awayTeam.id ?: return null,
                name = awayTeam.displayName ?: awayTeam.shortDisplayName ?: return null,
                shortName = awayTeam.abbreviation ?: awayTeam.shortDisplayName,
                crestUrl = awayTeam.logo,
            ),
            homeGoals = homeGoals,
            awayGoals = awayGoals,
            homeHalfTimeGoals = if (hasScore && regulation == "REGULATION") {
                home.linescores.firstOrNull()?.value?.toInt()
            } else {
                null
            },
            awayHalfTimeGoals = if (hasScore && regulation == "REGULATION") {
                away.linescores.firstOrNull()?.value?.toInt()
            } else {
                null
            },
            venue = event.venue?.fullName,
            odds = null,
            scoreScope = regulation,
            // ESPN does not state neutrality for every competition, so the app never
            // assumes it; a neutral flag must come from a source that says so.
            isNeutralVenue = false,
        )
    }

    private fun mapStatus(type: EspnStatusType?): MatchStatus = when {
        type == null -> MatchStatus.UNKNOWN
        type.name == "STATUS_POSTPONED" -> MatchStatus.POSTPONED
        type.name == "STATUS_CANCELED" || type.name == "STATUS_CANCELLED" -> MatchStatus.CANCELLED
        type.name == "STATUS_SUSPENDED" -> MatchStatus.SUSPENDED
        type.name == "STATUS_HALFTIME" -> MatchStatus.HALF_TIME
        type.completed == true || type.state == "post" -> MatchStatus.FINISHED
        type.state == "in" -> MatchStatus.LIVE
        type.state == "pre" -> MatchStatus.SCHEDULED
        else -> MatchStatus.UNKNOWN
    }

    /** Elapsed minute, only while the match is running and only from the published clock. */
    private fun minuteOf(status: EspnStatus?): Int? {
        if (status?.type?.state != "in") return null
        val seconds = status.clock ?: return null
        val minute = (seconds / 60.0).toInt()
        return minute.coerceIn(1, 120).takeIf { it > 0 }
    }

    /**
     * True when the published score is a 90-minute result.
     *
     * Extra time, penalties and any knockout round where the tie can be decided after
     * 90 minutes are marked `UNVERIFIED` so they never enter training or settlement.
     */
    private fun scoreIsRegulation(type: EspnStatusType?, seasonSlug: String, knockout: Boolean): String {
        val name = type?.name.orEmpty()
        val decidedLate = name.contains("AET") || name.contains("PEN") || name.contains("SHOOTOUT")
        val round = seasonSlug.lowercase()
        val lateRound = knockout && listOf("knockout", "playoff", "play-off", "quarterfinal", "semifinal", "final", "third")
            .any { round.contains(it) }
        return if (decidedLate || lateRound) "UNVERIFIED" else "REGULATION"
    }
}

/** One competition the keyless source publishes, with its honest attributes. */
data class EspnCompetitionInfo(
    val key: String,
    val name: String,
    val country: String?,
    val emblemUrl: String?,
    /** Later rounds can include extra time and penalties. */
    val knockout: Boolean,
)
