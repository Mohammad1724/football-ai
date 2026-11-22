package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.provider.RemoteMatchXg
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import java.time.LocalDate
import java.util.Locale

/**
 * Per-match expected goals from the football-ai-data mirror.
 *
 * The mirror (github.com/Mohammad1724/football-ai-data) republishes FotMob's
 * Opta-fed match xG for the big-5 leagues as one CSV per league and season,
 * using football-data.co.uk team naming — the same naming the history table
 * already carries — so rows join onto stored history by date and team names.
 * It exists because no xG source offers the app a bulk, keyless feed directly:
 * Understat has no API and blocks automated access, and the other publishers
 * are per-match APIs. The mirror is a personal, non-commercial republication;
 * see the mirror's README for the licensing note.
 *
 * Coverage is deliberately narrow: only the competitions in
 * [COVERED_COMPETITIONS] have files. Everywhere else the rating fit simply
 * keeps using goals-only evidence, exactly as before this provider existed.
 */
class XgHistoryProvider(
    private val client: ApiClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "football-ai-data xG mirror",
        requiresApiKey = false,
        capabilities = ProviderCapabilities(
            liveScores = false,
            singleMatchRefresh = false,
            matchStatistics = false,
            events = false,
            lineups = false,
            injuries = false,
            odds = false,
            standings = false,
            historicalSeasons = 6,
        ),
        quota = QuotaPolicy(requestsPerMinute = 480, requestsPerDay = 0),
        attribution = "Expected goals: FotMob (data: Opta/Stats Perform), mirrored for personal, non-commercial use by football-ai-data (github.com/Mohammad1724/football-ai-data). Fixture naming: football-data.co.uk.",
    )

    /**
     * xG rows for one competition and season, or an empty success when the
     * competition is not covered. A download failure is a [Outcome.Failure]:
     * callers treat xG as an enhancement and must not fail an import over it.
     */
    suspend fun xg(competitionKey: String, seasonStartYear: Int): Outcome<List<RemoteMatchXg>> {
        if (competitionKey !in COVERED_COMPETITIONS) return Outcome.Success(emptyList())
        val url = "$baseUrl/${fileName(competitionKey, seasonStartYear)}"
        return when (val response = client.get(PROVIDER_ID, info.quota, url, headers = CSV_HEADERS)) {
            is Outcome.Failure -> response
            is Outcome.Success -> try {
                Outcome.Success(XgCsvParser.parse(response.value))
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "football-ai-data"
        const val DEFAULT_BASE_URL =
            "https://raw.githubusercontent.com/Mohammad1724/football-ai-data/main/data/xg"

        /** Competitions the mirror publishes: the big five, football-data.co.uk codes. */
        val COVERED_COMPETITIONS = setOf("E0", "SP1", "D1", "I1", "F1")

        private val CSV_HEADERS = mapOf(
            "User-Agent" to "FootballAI/0.4 (Android; personal football CSV reader)",
            "Accept" to "text/csv,text/plain;q=0.9,*/*;q=0.8",
        )

        /** `E0` + 2021 -> `E0-2021-22.csv`, mirroring the data repo's layout. */
        fun fileName(competitionKey: String, seasonStartYear: Int): String = String.format(
            Locale.US,
            "%s-%d-%02d.csv",
            competitionKey,
            seasonStartYear,
            (seasonStartYear + 1) % 100,
        )
    }
}

/**
 * Parses the mirror's CSV format. Kept separate from the network code so it
 * can be unit tested with small fixtures, like [CsvHistoryParser].
 *
 * Malformed rows are skipped rather than failing the file: one bad line in a
 * mirror must not cost a whole season of xG.
 */
object XgCsvParser {

    fun parse(csv: String): List<RemoteMatchXg> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()
        val header = CsvHistoryParser.splitLine(lines.first()).map { it.trim() }
        val dateIndex = header.indexOf("date")
        val homeIndex = header.indexOf("home_team")
        val awayIndex = header.indexOf("away_team")
        val homeXgIndex = header.indexOf("home_xg")
        val awayXgIndex = header.indexOf("away_xg")
        if (listOf(dateIndex, homeIndex, awayIndex, homeXgIndex, awayXgIndex).any { it < 0 }) {
            return emptyList()
        }
        val results = ArrayList<RemoteMatchXg>(lines.size - 1)
        for (index in 1 until lines.size) {
            val columns = CsvHistoryParser.splitLine(lines[index])
            if (columns.size <= maxOf(dateIndex, homeIndex, awayIndex, homeXgIndex, awayXgIndex)) continue
            val date = try {
                LocalDate.parse(columns[dateIndex].trim())
            } catch (error: Exception) {
                continue
            }
            val homeTeam = columns[homeIndex].trim()
            val awayTeam = columns[awayIndex].trim()
            val homeXg = columns[homeXgIndex].trim().toDoubleOrNull()
            val awayXg = columns[awayXgIndex].trim().toDoubleOrNull()
            if (homeTeam.isEmpty() || awayTeam.isEmpty() || homeXg == null || awayXg == null) continue
            if (homeXg < 0.0 || awayXg < 0.0) continue
            results.add(RemoteMatchXg(date, homeTeam, awayTeam, homeXg, awayXg))
        }
        return results
    }
}
