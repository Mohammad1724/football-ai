package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.provider.RemoteMatchAvailability
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import java.time.LocalDate
import java.util.Locale

/**
 * Per-fixture player availability from the football-ai-data mirror.
 *
 * The mirror (github.com/Mohammad1724/football-ai-data) republishes FotMob's
 * pre-match unavailable lists for the big-5 leagues as one CSV per league and
 * season, using football-data.co.uk team naming. Each row carries the share
 * of a team's season minutes — computed by the publisher from lineups of
 * earlier matches only — belonging to players listed unavailable for that
 * fixture (injured, suspended, or otherwise out). The shares feed
 * [com.footballai.engine.ratings.AvailabilityImpact]; a fixture the mirror
 * does not cover simply gets no adjustment, exactly as competitions without
 * xG keep a goals-only fit.
 *
 * Coverage is deliberately narrow: only the competitions in
 * [COVERED_COMPETITIONS] have files.
 */
class AvailabilityHistoryProvider(
    private val client: ApiClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    val info: ProviderInfo = ProviderInfo(
        id = PROVIDER_ID,
        displayName = "football-ai-data availability mirror",
        requiresApiKey = false,
        capabilities = ProviderCapabilities(
            liveScores = false,
            singleMatchRefresh = false,
            matchStatistics = false,
            events = false,
            lineups = false,
            injuries = true,
            odds = false,
            standings = false,
            historicalSeasons = 6,
        ),
        quota = QuotaPolicy(requestsPerMinute = 480, requestsPerDay = 0),
        attribution = "Player availability: FotMob, mirrored for personal, non-commercial use by football-ai-data (github.com/Mohammad1724/football-ai-data). Fixture naming: football-data.co.uk.",
    )

    /**
     * Availability rows for one competition and season, or an empty success
     * when the competition is not covered. A download failure is a
     * [Outcome.Failure]: callers treat availability as an enhancement and
     * must not fail an import over it.
     */
    suspend fun availability(competitionKey: String, seasonStartYear: Int): Outcome<List<RemoteMatchAvailability>> {
        if (competitionKey !in COVERED_COMPETITIONS) return Outcome.Success(emptyList())
        val url = "$baseUrl/${fileName(competitionKey, seasonStartYear)}"
        return when (val response = client.get(PROVIDER_ID, info.quota, url, headers = CSV_HEADERS)) {
            is Outcome.Failure -> response
            is Outcome.Success -> try {
                Outcome.Success(AvailabilityCsvParser.parse(response.value))
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error))
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "football-ai-data-availability"
        const val DEFAULT_BASE_URL =
            "https://raw.githubusercontent.com/Mohammad1724/football-ai-data/main/data/availability"

        /** Competitions the mirror publishes: the big five, football-data.co.uk codes. */
        val COVERED_COMPETITIONS = XgHistoryProvider.COVERED_COMPETITIONS

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
 * Parses the mirror's availability CSV format. Kept separate from the
 * network code so it can be unit tested with small fixtures, like
 * [XgCsvParser].
 *
 * Rows whose shares are absent or malformed are skipped rather than failing
 * the file: an absent share means "the source published no usable list for
 * that team", which must never be read as a zero.
 */
object AvailabilityCsvParser {

    fun parse(csv: String): List<RemoteMatchAvailability> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()
        val header = CsvHistoryParser.splitLine(lines.first()).map { it.trim() }
        val dateIndex = header.indexOf("date")
        val homeIndex = header.indexOf("home_team")
        val awayIndex = header.indexOf("away_team")
        val homeShareIndex = header.indexOf("missing_home")
        val awayShareIndex = header.indexOf("missing_away")
        if (listOf(dateIndex, homeIndex, awayIndex, homeShareIndex, awayShareIndex).any { it < 0 }) {
            return emptyList()
        }
        val results = ArrayList<RemoteMatchAvailability>(lines.size - 1)
        for (index in 1 until lines.size) {
            val columns = CsvHistoryParser.splitLine(lines[index])
            if (columns.size <= maxOf(dateIndex, homeIndex, awayIndex, homeShareIndex, awayShareIndex)) continue
            val date = try {
                LocalDate.parse(columns[dateIndex].trim())
            } catch (error: Exception) {
                continue
            }
            val homeTeam = columns[homeIndex].trim()
            val awayTeam = columns[awayIndex].trim()
            val homeShare = columns[homeShareIndex].trim().toDoubleOrNull()
            val awayShare = columns[awayShareIndex].trim().toDoubleOrNull()
            if (homeTeam.isEmpty() || awayTeam.isEmpty() || homeShare == null || awayShare == null) continue
            if (homeShare !in 0.0..1.0 || awayShare !in 0.0..1.0) continue
            results.add(RemoteMatchAvailability(date, homeTeam, awayTeam, homeShare, awayShare))
        }
        return results
    }
}
