package com.footballai.app.data.repository

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.InternationalResult
import com.footballai.app.data.remote.InternationalResultsCsv
import com.footballai.app.domain.catalog.NationalTeamKeys
import com.footballai.app.domain.model.QuotaPolicy
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the one history this app keeps for national teams.
 *
 * No fixture provider in the app publishes past international results: ESPN gives the
 * schedule and the score of the day, football-data.org's free plan covers a dozen club
 * competitions, and the CSV archive is English and Scottish clubs. Without a history the
 * national model has nothing to learn from, which is why a Nations League fixture used to
 * answer "not enough history" — a true statement, but useless.
 *
 * The archive imported here is `martj42/international_results` (CC0, ~50 000 matches,
 * updated continuously). It is downloaded once, parsed on the device, and stored in the
 * same `historical_matches` table as every other history, under its own source name so it
 * can be replaced or removed without touching anything else.
 *
 * Honesty rules:
 * - only finished matches with integer scores enter the table;
 * - a match is placed at 00:00 UTC of its date, the earliest instant it could have been
 *   played, so a rating "as of now" can never see a result before it happened;
 * - the archive is men's international football. It is never used for women's fixtures.
 */
class NationalHistoryRepository(
    private val db: FootballDatabase,
    private val client: ApiClient,
    private val clock: TimeProvider,
    private val ratingsRepository: RatingsRepository,
) {

    data class State(
        val matches: Int,
        val teams: Int,
        val fromEpochMs: Long?,
        val toEpochMs: Long?,
    ) {
        val isEmpty: Boolean get() = matches == 0
    }

    suspend fun state(): State = withContext(Dispatchers.IO) {
        val dao = db.historicalDao()
        State(
            matches = dao.countForSource(NationalTeamKeys.SOURCE),
            teams = dao.teamCountForSource(NationalTeamKeys.SOURCE),
            fromEpochMs = dao.earliestForSource(NationalTeamKeys.SOURCE),
            toEpochMs = dao.latestForSource(NationalTeamKeys.SOURCE),
        )
    }

    /**
     * Downloads and replaces the archive.
     *
     * The previous copy is deleted first so a failed import cannot leave two generations
     * of results in the table and double-count a match in the ratings.
     */
    suspend fun importArchive(onProgress: (String) -> Unit = {}): Outcome<State> {
        onProgress("DOWNLOAD")
        val downloaded = client.get(PROVIDER_ID, QUOTA, URL)
        val csv = when (downloaded) {
            is Outcome.Failure -> return downloaded
            is Outcome.Success -> downloaded.value
        }
        onProgress("PARSE")
        val parsed = withContext(Dispatchers.Default) {
            InternationalResultsCsv.parse(csv, CUTOFF_EPOCH_MS)
        }
        if (parsed.rows.isEmpty()) {
            return Outcome.Failure(
                AppError(
                    code = ErrorCode.EMPTY_RESPONSE,
                    detail = "international history: no usable rows in ${parsed.total} lines",
                ),
            )
        }
        onProgress("STORE")
        val now = clock.nowEpochMs()
        withContext(Dispatchers.IO) {
            db.historicalDao().deleteForSource(NationalTeamKeys.SOURCE)
            parsed.rows.chunked(2000).forEach { chunk ->
                db.historicalDao().insertAll(chunk.map { it.toEntity(now) })
            }
        }
        ratingsRepository.invalidate()
        onProgress("DONE")
        return Outcome.Success(state())
    }

    /** Removes the imported history; predictions fall back to "no evidence" honestly. */
    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        val removed = db.historicalDao().deleteForSource(NationalTeamKeys.SOURCE)
        ratingsRepository.invalidate()
        removed
    }

    private fun InternationalResult.toEntity(importedAt: Long): HistoricalMatchEntity =
        HistoricalMatchEntity(
            id = "hist:${NationalTeamKeys.SOURCE}:${NationalTeamKeys.COMPETITION_ID}:" +
                "$homeTeamId:$awayTeamId:$kickoffEpochMs",
            source = NationalTeamKeys.SOURCE,
            competitionId = NationalTeamKeys.COMPETITION_ID,
            competitionName = tournament,
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            homeTeamName = homeName,
            awayTeamName = awayName,
            homeGoals = homeGoals,
            awayGoals = awayGoals,
            kickoffEpochMs = kickoffEpochMs,
            homeOdds = null,
            drawOdds = null,
            awayOdds = null,
            importedAt = importedAt,
            neutralVenue = neutralVenue,
        )

    companion object {
        /** Registered like a provider so the same quota guard and retry rules apply. */
        const val PROVIDER_ID = "international-history"
        const val URL =
            "https://raw.githubusercontent.com/martj42/international_results/master/results.csv"

        /**
         * Results since 2000. The archive starts in 1872; a century of matches would
         * multiply the import without adding information the model still weights, since
         * the Elo regression half-life is two years and goals are weighted by 400 days.
         */
        val CUTOFF_EPOCH_MS: Long =
            LocalDate.of(2000, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        /** One large file, fetched rarely; the guard still spaces and counts requests. */
        val QUOTA = QuotaPolicy(requestsPerMinute = 6, requestsPerDay = 20)
    }
}
