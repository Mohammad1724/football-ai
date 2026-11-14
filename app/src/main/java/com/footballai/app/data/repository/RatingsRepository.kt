package com.footballai.app.data.repository

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.domain.catalog.NationalTeamKeys
import com.footballai.engine.model.LambdaAdjustment
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.ratings.DixonColesFit
import com.footballai.engine.ratings.NationalMatch
import com.footballai.engine.ratings.NationalPrepared
import com.footballai.engine.ratings.NationalTeamRatingBook
import com.footballai.engine.ratings.RatingBook
import com.footballai.engine.ratings.RatingBookConfig
import com.footballai.engine.ratings.RatingMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds and caches the [RatingBook] for a competition.
 *
 * The book is rebuilt only when the underlying history changes, which keeps
 * predictions instant while the model always uses every result that happened
 * before the prediction timestamp.
 */
class RatingsRepository(
    private val db: FootballDatabase,
    private val clock: TimeProvider,
    private val config: RatingBookConfig = RatingBookConfig(),
) {

    private data class Key(val source: String, val competitionId: String)

    private class Entry(val historyCount: Int, val latestKickoff: Long, val book: RatingBook)

    /** Same idea as [Entry], but a national book is a different type, never mixed with it. */
    private class NationalEntry(
        val historyCount: Int,
        val latestKickoff: Long,
        val book: NationalTeamRatingBook,
    )

    private val cache = HashMap<Key, Entry>()

    /** The national archive is a single book, so it needs a single cache slot. */
    private var nationalCache: NationalEntry? = null

    private val mutex = Mutex()

    private suspend fun history(source: String, competitionId: String, asOf: Long): List<HistoricalMatchEntity> {
        val native = db.historicalDao().forCompetition(source, competitionId, asOf)
        if (source != "football-data-org") return native
        val code = HistoryBridge.leagues[competitionId.substringAfterLast(':')] ?: return native
        val csv = db.historicalDao().forCompetition("football-data-co-uk", "football-data-co-uk:league:$code", asOf)
        val mapped = HistoryBridge.map(csv, db.teamDao().forCompetition(competitionId))
        // Use one complete source, never double-count the same results across providers.
        return if (mapped.size > native.size) mapped else native
    }

    suspend fun book(source: String, competitionId: String, asOfEpochMs: Long = clock.nowEpochMs()): RatingBook? =
        mutex.withLock {
            val rows = history(source, competitionId, asOfEpochMs)
            if (rows.isEmpty()) return null
            val key = Key(source, competitionId)
            val latest = rows.last().kickoffEpochMs
            val cached = cache[key]
            if (source != "football-data-org" && cached != null && cached.historyCount == rows.size && cached.latestKickoff == latest) {
                return cached.book
            }
            val book = RatingBook(config)
            book.addAll(competitionId, rows.map { it.toRatingMatch() })
            cache[key] = Entry(rows.size, latest, book)
            return book
        }

    suspend fun strengths(
        source: String,
        competitionId: String,
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
    ): Pair<TeamStrength, TeamStrength> = withContext(Dispatchers.Default) {
        val book = book(source, competitionId, asOfEpochMs)
            ?: return@withContext Pair(TeamStrength.neutral(homeTeamId), TeamStrength.neutral(awayTeamId))
        book.strengths(competitionId, homeTeamId, awayTeamId, asOfEpochMs)
    }

    /**
     * National-team strengths for an international fixture.
     *
     * The ratings come from the imported international archive, not from the fixture
     * provider: no feed in this app publishes past national results, and a national
     * team's strength is built from every match it played, not from one competition.
     * Team identifiers are the canonical keys from [NationalTeamKeys], which the caller
     * derives from the published team names.
     *
     * The book is cached by (history size, latest kickoff) so repeated screen visits do
     * not replay thousands of matches.
     */
    suspend fun nationalPrepared(
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
        neutralVenue: Boolean,
    ): NationalPrepared? = withContext(Dispatchers.Default) {
        val book = nationalBook(asOfEpochMs) ?: return@withContext null
        // Each past result carries the weight it deserves, assigned when the archive was
        // imported; the fixture's own importance only matters for future results.
        book.prepare(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            asOfEpochMs = asOfEpochMs,
            neutralVenue = neutralVenue,
        )
    }

    /** How many international results are available at all (0 = archive not imported). */
    suspend fun nationalHistoryCount(): Int =
        withContext(Dispatchers.Default) { db.historicalDao().countForSource(NationalTeamKeys.SOURCE) }

    private suspend fun nationalBook(asOfEpochMs: Long): NationalTeamRatingBook? = mutex.withLock {
        val rows = db.historicalDao().forSource(NationalTeamKeys.SOURCE, asOfEpochMs)
        if (rows.isEmpty()) return null
        val latest = rows.last().kickoffEpochMs
        val cached = nationalCache
        if (cached != null && cached.historyCount == rows.size && cached.latestKickoff == latest) {
            return cached.book
        }
        val book = NationalTeamRatingBook()
        book.addAll(
            rows.map { row ->
                NationalMatch(
                    homeTeamId = row.homeTeamId,
                    awayTeamId = row.awayTeamId,
                    homeGoals = row.homeGoals,
                    awayGoals = row.awayGoals,
                    kickoffEpochMs = row.kickoffEpochMs,
                    importance = nationalImportance(row.competitionName),
                    neutralVenue = row.neutralVenue,
                )
            },
        )
        nationalCache = NationalEntry(rows.size, latest, book)
        return book
    }

    /**
     * Weight of a past international, taken from the tournament name the archive prints.
     * The scale matches the one used for fixtures: a friendly counts least, a qualifier
     * normally, a finals tournament a little more.
     */
    private fun nationalImportance(tournament: String): Double {
        val name = tournament.lowercase()
        return when {
            name.contains("friendly") -> 0.5
            name.contains("qualification") || name.contains("qualifier") -> 1.0
            name.contains("world cup") || name.contains("euro") || name.contains("copa") ||
                name.contains("cup of nations") || name.contains("gold cup") ||
                name.contains("nations league") || name.contains("confederations") -> 1.25
            else -> 1.0
        }
    }

    suspend fun adjustments(
        source: String,
        competitionId: String,
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
    ): List<LambdaAdjustment> = withContext(Dispatchers.Default) {
        book(source, competitionId, asOfEpochMs)?.adjustments(
            competitionId, homeTeamId, awayTeamId, asOfEpochMs,
        ).orEmpty()
    }

    suspend fun baseline(source: String, competitionId: String, asOfEpochMs: Long): LeagueBaseline {
        val book = book(source, competitionId, asOfEpochMs) ?: return LeagueBaseline.FALLBACK
        return book.baseline(competitionId, asOfEpochMs)
    }

    suspend fun fit(source: String, competitionId: String, asOfEpochMs: Long): DixonColesFit? =
        withContext(Dispatchers.Default) { book(source, competitionId, asOfEpochMs)?.fit(competitionId, asOfEpochMs) }

    suspend fun eloRating(source: String, competitionId: String, teamId: String, asOfEpochMs: Long): Double =
        withContext(Dispatchers.Default) { book(source, competitionId, asOfEpochMs)?.eloRating(teamId, asOfEpochMs) ?: TeamStrength.AVERAGE_ELO }

    suspend fun matchCount(source: String, competitionId: String): Int =
        history(source, competitionId, clock.nowEpochMs()).size

    /** Invalidates the cache, e.g. after a historical import or a fixture sync. */
    suspend fun invalidate() = mutex.withLock {
        cache.clear()
        nationalCache = null
    }
}

fun HistoricalMatchEntity.toRatingMatch(): RatingMatch = RatingMatch(
    homeTeamId = homeTeamId,
    awayTeamId = awayTeamId,
    homeGoals = homeGoals,
    awayGoals = awayGoals,
    kickoffEpochMs = kickoffEpochMs,
    weight = 1.0,
)
