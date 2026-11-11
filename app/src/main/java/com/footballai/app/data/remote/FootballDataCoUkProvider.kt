package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.provider.FootballDataProvider
import com.footballai.app.data.provider.HistoricalDataProvider
import com.footballai.app.data.provider.RemoteCompetition
import com.footballai.app.data.provider.RemoteEvent
import com.footballai.app.data.provider.RemoteHistoryMatch
import com.footballai.app.data.provider.RemoteLineup
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteStandingRow
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.data.provider.RemoteTeamStatistics
import com.footballai.app.domain.model.MatchOdds
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bulk historical results from football-data.co.uk.
 *
 * The site publishes plain CSV files per league and season (results, half-time
 * scores and closing odds). It needs no API key, which makes it the only source in
 * the app that works with zero configuration. Files are downloaded on demand and
 * never redistributed with the APK.
 */
class FootballDataCoUkProvider(
    private val client: ApiClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val fixturesUrl: String = FIXTURES_URL,
) : HistoricalDataProvider, FootballDataProvider {

    override val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "Open Matchday / football-data.co.uk",
        requiresApiKey = false,
        capabilities = ProviderCapabilities(
            liveScores = false,
            matchStatistics = false,
            events = false,
            lineups = false,
            injuries = false,
            odds = true,
            standings = false,
            historicalSeasons = 25,
        ),
        quota = QuotaPolicy(requestsPerMinute = 30, requestsPerDay = 0),
        attribution = "Current fixtures/results: open-matchday (github.com/oladeeayo/open-matchday). Historical results and odds: football-data.co.uk. Personal, non-commercial use; upstream terms apply.",
    )

    override fun availableCompetitions(): List<CsvCompetition> = COMPETITIONS

    override suspend fun history(competitionKey: String, seasonStartYear: Int): Outcome<List<RemoteHistoryMatch>> {
        val competition = COMPETITIONS.firstOrNull { it.key == competitionKey }
        val openDirectory = OPEN_MATCHDAY_LEAGUES[competitionKey]
        if (competition != null && openDirectory != null && seasonStartYear == currentSeasonStartYear()) {
            return when (val response = openMatchdayCsv(openDirectory, "results", seasonStartYear)) {
                is Outcome.Failure -> response
                is Outcome.Success -> Outcome.Success(
                    OpenMatchdayParser.history(response.value, competition, seasonStartYear),
                )
            }
        }
        val season = seasonCode(seasonStartYear)
        val mirrorUrl = if (competitionKey in HISTORY_MIRROR_LEAGUES) {
            "$HISTORY_MIRROR_BASE_URL/$season/$competitionKey.csv"
        } else {
            null
        }
        return when (val response = downloadCsv(
            primaryUrl = "$baseUrl/$season/$competitionKey.csv",
            alternateUrl = "$ALTERNATE_BASE_URL/$season/$competitionKey.csv",
            mirrorUrl = mirrorUrl,
        )) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val competition = COMPETITIONS.firstOrNull { it.key == competitionKey }
                    ?: CsvCompetition(competitionKey, competitionKey, "")
                try {
                    Outcome.Success(
                        CsvHistoryParser.parse(
                            csv = response.value,
                            competitionKey = competitionKey,
                            competitionName = competition.name,
                            seasonStartYear = seasonStartYear,
                        ),
                    )
                } catch (error: Exception) {
                    Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
                }
            }
        }
    }

    // ---- Fixtures (no key required) ----

    private val cacheMutex = Mutex()
    private var cachedFixtures: List<RemoteMatch> = emptyList()
    private var cachedAtMs: Long = 0L

    /**
     * Upcoming fixtures for the competitions this provider publishes.
     *
     * `fixtures.csv` is a single file covering every main league, so it is downloaded at
     * most once per [CACHE_TTL_MS] and then filtered locally for each competition.
     */
    private suspend fun fixtures(force: Boolean = false): Outcome<List<RemoteMatch>> = cacheMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && cachedFixtures.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) {
            return@withLock Outcome.Success(cachedFixtures)
        }
        when (val response = downloadCsv(fixturesUrl, ALTERNATE_FIXTURES_URL)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val parsed = try {
                    CsvFixturesParser.parse(response.value, COMPETITIONS)
                } catch (error: Exception) {
                    return@withLock Outcome.Failure(
                        AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error),
                    )
                }
                cachedFixtures = parsed
                cachedAtMs = System.currentTimeMillis()
                Outcome.Success(parsed)
            }
        }
    }

    /** Retries through the canonical non-www host when the www endpoint is unreachable. */
    private suspend fun downloadCsv(
        primaryUrl: String,
        alternateUrl: String,
        mirrorUrl: String? = null,
    ): Outcome<String> {
        val primary = client.get(PROVIDER_ID, info.quota, primaryUrl, headers = CSV_HEADERS)
        if (primary is Outcome.Success) return primary
        val alternate = if (primaryUrl == alternateUrl) primary else {
            client.get(PROVIDER_ID, info.quota, alternateUrl, headers = CSV_HEADERS)
        }
        if (alternate is Outcome.Success || mirrorUrl == null) return alternate
        return client.get(PROVIDER_ID, info.quota, mirrorUrl, headers = CSV_HEADERS)
    }

    private val openCacheMutex = Mutex()
    private val openCsvCache = HashMap<String, Pair<Long, String>>()

    private suspend fun openMatchdayCsv(
        directory: String,
        kind: String,
        seasonStartYear: Int,
    ): Outcome<String> = openCacheMutex.withLock {
        val cacheKey = "$directory/$kind/$seasonStartYear"
        val now = System.currentTimeMillis()
        openCsvCache[cacheKey]?.takeIf { now - it.first < CACHE_TTL_MS }?.let {
            return@withLock Outcome.Success(it.second)
        }
        val url = "$OPEN_MATCHDAY_BASE_URL/$directory/${kind}_${seasonCode(seasonStartYear)}.csv"
        when (val result = client.get(PROVIDER_ID, info.quota, url, headers = CSV_HEADERS)) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                openCsvCache[cacheKey] = now to result.value
                result
            }
        }
    }

    private suspend fun openMatches(competition: CsvCompetition, directory: String): Outcome<List<RemoteMatch>> {
        val season = currentSeasonStartYear()
        val fixturesResponse = openMatchdayCsv(directory, "fixtures", season)
        val resultsResponse = openMatchdayCsv(directory, "results", season)
        if (fixturesResponse is Outcome.Failure && resultsResponse is Outcome.Failure) return fixturesResponse
        return try {
            val fixtures = (fixturesResponse as? Outcome.Success<String>)?.value
                ?.let { OpenMatchdayParser.fixtures(it, competition, season) }
                .orEmpty()
            val results = (resultsResponse as? Outcome.Success<String>)?.value
                ?.let { OpenMatchdayParser.results(it, competition, season) }
                .orEmpty()
            Outcome.Success(fixtures + results)
        } catch (error: Exception) {
            Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
        }
    }

    override suspend fun competitions(): Outcome<List<RemoteCompetition>> = Outcome.Success(
        COMPETITIONS.map { competition ->
            RemoteCompetition(
                providerKey = competition.key,
                name = competition.name,
                country = competition.country,
                emblemUrl = null,
                currentSeason = currentSeasonStartYear(),
            )
        },
    )

    override suspend fun matches(
        competitionKey: String,
        season: Int?,
        fromEpochMs: Long?,
        toEpochMs: Long?,
    ): Outcome<List<RemoteMatch>> {
        val competition = COMPETITIONS.firstOrNull { it.key == competitionKey }
        val openDirectory = OPEN_MATCHDAY_LEAGUES[competitionKey]
        val all = if (competition != null && openDirectory != null && (season == null || season == currentSeasonStartYear())) {
            openMatches(competition, openDirectory)
        } else {
            fixtures()
        }
        return when (all) {
            is Outcome.Failure -> all
            is Outcome.Success -> Outcome.Success(
                all.value.filter { match ->
                    match.competitionKey == competitionKey &&
                        (fromEpochMs == null || match.kickoffEpochMs >= fromEpochMs) &&
                        (toEpochMs == null || match.kickoffEpochMs <= toEpochMs)
                },
            )
        }
    }

    override suspend fun liveMatches(): Outcome<List<RemoteMatch>> = unsupported("live scores")

    override suspend fun match(matchKey: String): Outcome<RemoteMatch?> = when (
        val all = matches(matchKey.substringBefore(':'), null, fromEpochMs = null, toEpochMs = null)
    ) {
        is Outcome.Failure -> all
        is Outcome.Success -> {
            val parts = matchKey.split(':')
            Outcome.Success(all.value.firstOrNull { candidate ->
                candidate.providerKey == matchKey || (parts.size == 4 &&
                    candidate.competitionKey == parts[0] && candidate.home.providerKey == parts[1] &&
                    candidate.away.providerKey == parts[2] && parts[3].toLongOrNull()?.let { stamp ->
                        // Backwards compatibility for kickoff-based v1 fixture IDs.
                        stamp > 10000 && CsvFixtureIdentity.sameDate(candidate.kickoffEpochMs, stamp)
                    } == true)
            })
        }
    }

    override suspend fun matchStatistics(matchKey: String): Outcome<List<RemoteTeamStatistics>> =
        unsupported("match statistics")

    override suspend fun matchEvents(matchKey: String): Outcome<List<RemoteEvent>> = unsupported("match events")

    override suspend fun lineups(matchKey: String): Outcome<List<RemoteLineup>> = unsupported("lineups")

    override suspend fun standings(competitionKey: String, season: Int): Outcome<List<RemoteStandingRow>> =
        unsupported("standings")

    override val defaultCompetitionKeys: List<String> = DEFAULT_FIXTURE_COMPETITIONS

    private fun <T> unsupported(feature: String): Outcome<T> = Outcome.Failure(
        AppError(
            code = ErrorCode.REQUEST_FAILED,
            detail = "football-data.co.uk does not provide $feature",
        ),
    )

    private fun currentSeasonStartYear(): Int {
        val now = LocalDate.now(ZoneId.of("Europe/London"))
        return if (now.monthValue >= 7) now.year else now.year - 1
    }

    companion object {
        const val PROVIDER_ID = "football-data-co-uk"
        const val DEFAULT_BASE_URL = "https://www.football-data.co.uk/mmz4281"
        const val FIXTURES_URL = "https://www.football-data.co.uk/fixtures.csv"
        internal const val ALTERNATE_BASE_URL = "https://football-data.co.uk/mmz4281"
        internal const val ALTERNATE_FIXTURES_URL = "https://football-data.co.uk/fixtures.csv"
        internal const val OPEN_MATCHDAY_BASE_URL =
            "https://raw.githubusercontent.com/oladeeayo/open-matchday/master/data"
        internal const val HISTORY_MIRROR_BASE_URL =
            "https://raw.githubusercontent.com/TuncErdoganlar/futbol-veri-aynasi/main/data/fd"
        internal val HISTORY_MIRROR_LEAGUES = setOf(
            "T1", "E0", "E1", "SP1", "D1", "I1", "F1", "N1", "P1", "B1", "G1", "SC0",
        )
        internal val OPEN_MATCHDAY_LEAGUES = mapOf(
            "E0" to "ENG_1_premier-league",
            "E1" to "ENG_2_championship",
            "E2" to "ENG_3_league-one",
            "SC0" to "SCO_1_premiership",
            "D1" to "GER_1_bundesliga",
            "D2" to "GER_2_2-bundesliga",
            "I1" to "ITA_1_serie-a",
            "I2" to "ITA_2_serie-b",
            "SP1" to "ESP_1_la-liga",
            "SP2" to "ESP_2_segunda-division",
            "F1" to "FRA_1_ligue-1",
            "F2" to "FRA_2_ligue-2",
            "N1" to "NED_1_eredivisie",
            "B1" to "BEL_1_pro-league",
            "P1" to "POR_1_primeira-liga",
            "T1" to "TUR_1_super-lig",
            "G1" to "GRE_1_super-league",
        )
        private val CSV_HEADERS = mapOf(
            "User-Agent" to "FootballAI/0.4 (Android; personal football CSV reader)",
            "Accept" to "text/csv,text/plain;q=0.9,*/*;q=0.8",
        )
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** Leagues synced when the user follows none of this provider's competitions. */
        val DEFAULT_FIXTURE_COMPETITIONS = listOf("E0", "SP1", "D1", "I1", "F1")

        fun seasonCode(startYear: Int): String = String.format(
            Locale.US,
            "%02d%02d",
            startYear % 100,
            (startYear + 1) % 100,
        )

        /** The main European divisions published by football-data.co.uk. */
        val COMPETITIONS: List<CsvCompetition> = listOf(
            CsvCompetition("E0", "Premier League", "England"),
            CsvCompetition("E1", "Championship", "England"),
            CsvCompetition("E2", "League One", "England"),
            CsvCompetition("SC0", "Premiership", "Scotland"),
            CsvCompetition("D1", "Bundesliga", "Germany"),
            CsvCompetition("D2", "2. Bundesliga", "Germany"),
            CsvCompetition("I1", "Serie A", "Italy"),
            CsvCompetition("I2", "Serie B", "Italy"),
            CsvCompetition("SP1", "La Liga", "Spain"),
            CsvCompetition("SP2", "Segunda Division", "Spain"),
            CsvCompetition("F1", "Ligue 1", "France"),
            CsvCompetition("F2", "Ligue 2", "France"),
            CsvCompetition("N1", "Eredivisie", "Netherlands"),
            CsvCompetition("B1", "Jupiler Pro League", "Belgium"),
            CsvCompetition("P1", "Primeira Liga", "Portugal"),
            CsvCompetition("T1", "Super Lig", "Turkey"),
            CsvCompetition("G1", "Super League", "Greece"),
        )
    }
}

/**
 * Parses the football-data.co.uk CSV format.
 *
 * Kept separate from the network code so it can be unit tested with small fixtures.
 * Missing values (a very common case for odds and half-time scores) are mapped to
 * null instead of zero.
 */
object CsvHistoryParser {

    private val DATE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yy", Locale.US),
    )
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm", Locale.US)
    private val LONDON: ZoneId = ZoneId.of("Europe/London")

    private val HOME_ODDS_COLUMNS = listOf("B365H", "AvgH", "PSH", "WHH", "BFH", "BbAvH")
    private val DRAW_ODDS_COLUMNS = listOf("B365D", "AvgD", "PSD", "WHD", "BFD", "BbAvD")
    private val AWAY_ODDS_COLUMNS = listOf("B365A", "AvgA", "PSA", "WHA", "BFA", "BbAvA")

    fun parse(
        csv: String,
        competitionKey: String,
        competitionName: String,
        seasonStartYear: Int,
    ): List<RemoteHistoryMatch> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()

        val header = splitLine(lines.first()).mapIndexed { index, name -> name.trim() to index }.toMap()
        val homeIndex = header["HomeTeam"] ?: return emptyList()
        val awayIndex = header["AwayTeam"] ?: return emptyList()
        val homeGoalsIndex = header["FTHG"] ?: return emptyList()
        val awayGoalsIndex = header["FTAG"] ?: return emptyList()
        val dateIndex = header["Date"] ?: return emptyList()
        val timeIndex = header["Time"]

        val results = ArrayList<RemoteHistoryMatch>(lines.size - 1)
        for (index in 1 until lines.size) {
            val columns = splitLine(lines[index])
            if (columns.size <= maxOf(homeIndex, awayIndex, homeGoalsIndex, awayGoalsIndex, dateIndex)) continue
            val homeTeam = columns[homeIndex].trim()
            val awayTeam = columns[awayIndex].trim()
            if (homeTeam.isEmpty() || awayTeam.isEmpty()) continue
            val homeGoals = columns[homeGoalsIndex].trim().toIntOrNull() ?: continue
            val awayGoals = columns[awayGoalsIndex].trim().toIntOrNull() ?: continue
            val kickoff = parseKickoff(
                date = columns[dateIndex].trim(),
                time = timeIndex?.let { columns.getOrNull(it)?.trim() },
                fallbackSeasonStart = seasonStartYear,
            ) ?: continue

            results.add(
                RemoteHistoryMatch(
                    competitionKey = competitionKey,
                    competitionName = competitionName,
                    season = seasonStartYear,
                    homeTeamKey = slug(homeTeam),
                    homeTeamName = homeTeam,
                    awayTeamKey = slug(awayTeam),
                    awayTeamName = awayTeam,
                    homeGoals = homeGoals,
                    awayGoals = awayGoals,
                    kickoffEpochMs = kickoff,
                    homeOdds = firstDouble(columns, header, HOME_ODDS_COLUMNS),
                    drawOdds = firstDouble(columns, header, DRAW_ODDS_COLUMNS),
                    awayOdds = firstDouble(columns, header, AWAY_ODDS_COLUMNS),
                ),
            )
        }
        return results
    }

    internal fun firstDouble(columns: List<String>, header: Map<String, Int>, candidates: List<String>): Double? {
        for (name in candidates) {
            val index = header[name] ?: continue
            val value = columns.getOrNull(index)?.trim().orEmpty()
            val parsed = value.toDoubleOrNull()
            if (parsed != null && parsed > 1.0) return parsed
        }
        return null
    }

    /** Closing odds of the 1X2 market, or null when the file has no usable prices. */
    internal fun parseOdds(columns: List<String>, header: Map<String, Int>): MatchOdds? {
        val home = firstDouble(columns, header, HOME_ODDS_COLUMNS) ?: return null
        val draw = firstDouble(columns, header, DRAW_ODDS_COLUMNS) ?: return null
        val away = firstDouble(columns, header, AWAY_ODDS_COLUMNS) ?: return null
        return MatchOdds(home = home, draw = draw, away = away, bookmaker = "football-data.co.uk")
    }

    /** Kick-off instant in league local time (Europe/London), or null when unparseable. */
    internal fun parseKickoff(date: String, time: String?, fallbackSeasonStart: Int): Long? {
        val localDate = DATE_FORMATTERS.firstNotNullOfOrNull { formatter ->
            try {
                LocalDate.parse(date, formatter)
            } catch (error: Exception) {
                null
            }
        } ?: run {
            // Some files use a two digit year without a separator variant; skip when unknown.
            if (date.isBlank()) null else null
        } ?: return null
        val localTime = time?.takeIf { it.isNotBlank() }?.let {
            try {
                LocalTime.parse(it, TIME_FORMATTER)
            } catch (error: Exception) {
                null
            }
        } ?: LocalTime.NOON
        val validDate = if (localDate.year < 1990) {
            // Two digit years are already resolved by the formatter; guard against oddities.
            localDate.withYear(fallbackSeasonStart)
        } else {
            localDate
        }
        return validDate.atTime(localTime).atZone(LONDON).toInstant().toEpochMilli()
    }

    fun slug(name: String): String = name
        .lowercase(Locale.US)
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    /** RFC4180-ish splitter: supports quoted fields and embedded commas. */
    fun splitLine(line: String): List<String> {
        val result = ArrayList<String>(24)
        val builder = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    builder.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(builder.toString())
                    builder.clear()
                }
                else -> builder.append(char)
            }
            index++
        }
        result.add(builder.toString())
        return result
    }
}

/**
 * Parser for `fixtures.csv`, the keyless weekly list of upcoming matches.
 *
 * The file shares the column vocabulary of the historical files (Div, Date, Time,
 * HomeTeam, AwayTeam and closing odds), so team keys and competition codes stay
 * consistent with the history importer and the ratings built from it.
 */
object CsvFixturesParser {

    private val LONDON: ZoneId = ZoneId.of("Europe/London")

    fun parse(csv: String, competitions: List<CsvCompetition>): List<RemoteMatch> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()

        val header = CsvHistoryParser.splitLine(lines.first())
            .mapIndexed { index, name -> name.trim() to index }
            .toMap()
        val divisionIndex = header["Div"] ?: return emptyList()
        val dateIndex = header["Date"] ?: return emptyList()
        val homeIndex = header["HomeTeam"] ?: return emptyList()
        val awayIndex = header["AwayTeam"] ?: return emptyList()
        val timeIndex = header["Time"]
        val fallbackYear = LocalDate.now(LONDON).year

        val matches = ArrayList<RemoteMatch>(lines.size - 1)
        val seen = HashSet<String>()
        for (index in 1 until lines.size) {
            val columns = CsvHistoryParser.splitLine(lines[index])
            if (columns.size <= maxOf(divisionIndex, dateIndex, homeIndex, awayIndex)) continue
            val division = columns[divisionIndex].trim()
            val competition = competitions.firstOrNull { it.key == division } ?: continue
            val homeTeam = columns[homeIndex].trim()
            val awayTeam = columns[awayIndex].trim()
            if (homeTeam.isEmpty() || awayTeam.isEmpty()) continue
            val kickoff = CsvHistoryParser.parseKickoff(
                date = columns[dateIndex].trim(),
                time = timeIndex?.let { columns.getOrNull(it)?.trim() },
                fallbackSeasonStart = fallbackYear,
            ) ?: continue

            val homeKey = CsvHistoryParser.slug(homeTeam)
            val awayKey = CsvHistoryParser.slug(awayTeam)
            val fixtureDate = Instant.ofEpochMilli(kickoff).atZone(LONDON).toLocalDate()
            val providerKey = "$division:$homeKey:$awayKey:$fixtureDate"
            if (!seen.add(providerKey)) continue

            matches.add(
                RemoteMatch(
                    providerKey = providerKey,
                    competitionKey = division,
                    competitionName = competition.name,
                    season = seasonStartYear(kickoff),
                    matchday = null,
                    kickoffEpochMs = kickoff,
                    status = MatchStatus.SCHEDULED,
                    minute = null,
                    home = RemoteTeam(providerKey = homeKey, name = homeTeam),
                    away = RemoteTeam(providerKey = awayKey, name = awayTeam),
                    homeGoals = null,
                    awayGoals = null,
                    homeHalfTimeGoals = null,
                    awayHalfTimeGoals = null,
                    venue = null,
                    odds = CsvHistoryParser.parseOdds(columns, header),
                ),
            )
        }
        return matches.sortedBy { it.kickoffEpochMs }
    }

    private fun seasonStartYear(epochMs: Long): Int {
        val date = Instant.ofEpochMilli(epochMs).atZone(LONDON).toLocalDate()
        return if (date.monthValue >= 7) date.year else date.year - 1
    }
}

/** CSV has no immutable match ID. Only reconcile the same dated home/away fixture.
 * Some leagues meet more than once at the same venue in one season, so season-only
 * matching is unsafe. A postponement to another day requires explicit reconciliation.
 */
object CsvFixtureIdentity {
    private val zone = ZoneId.of("Europe/London")
    fun sameDate(first: Long, second: Long): Boolean =
        Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
}
