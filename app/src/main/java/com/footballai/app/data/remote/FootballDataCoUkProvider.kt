package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.provider.HistoricalDataProvider
import com.footballai.app.data.provider.RemoteHistoryMatch
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import java.time.LocalDate
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
) : HistoricalDataProvider {

    override val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "football-data.co.uk (historical CSV)",
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
        attribution = "Historical results and odds courtesy of football-data.co.uk",
    )

    override fun availableCompetitions(): List<CsvCompetition> = COMPETITIONS

    override suspend fun history(competitionKey: String, seasonStartYear: Int): Outcome<List<RemoteHistoryMatch>> {
        val url = "$baseUrl/${seasonCode(seasonStartYear)}/$competitionKey.csv"
        return when (val response = client.get(PROVIDER_ID, info.quota, url)) {
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

    companion object {
        const val PROVIDER_ID = "football-data-co-uk"
        const val DEFAULT_BASE_URL = "https://www.football-data.co.uk/mmz4281"

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

    private fun firstDouble(columns: List<String>, header: Map<String, Int>, candidates: List<String>): Double? {
        for (name in candidates) {
            val index = header[name] ?: continue
            val value = columns.getOrNull(index)?.trim().orEmpty()
            val parsed = value.toDoubleOrNull()
            if (parsed != null && parsed > 1.0) return parsed
        }
        return null
    }

    private fun parseKickoff(date: String, time: String?, fallbackSeasonStart: Int): Long? {
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
