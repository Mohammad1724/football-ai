package com.footballai.app.data.repository

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.ratings.DixonColesFit
import com.footballai.engine.ratings.RatingBook
import com.footballai.engine.ratings.RatingBookConfig
import com.footballai.engine.ratings.RatingMatch
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

    private val cache = HashMap<Key, Entry>()
    private val mutex = Mutex()

    suspend fun book(source: String, competitionId: String, asOfEpochMs: Long = clock.nowEpochMs()): RatingBook? =
        mutex.withLock {
            val rows = db.historicalDao().forCompetition(source, competitionId, asOfEpochMs)
            if (rows.isEmpty()) return null
            val key = Key(source, competitionId)
            val latest = rows.last().kickoffEpochMs
            val cached = cache[key]
            if (cached != null && cached.historyCount == rows.size && cached.latestKickoff == latest) {
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
    ): Pair<TeamStrength, TeamStrength> {
        val book = book(source, competitionId, asOfEpochMs)
            ?: return Pair(TeamStrength.neutral(homeTeamId), TeamStrength.neutral(awayTeamId))
        return book.strengths(competitionId, homeTeamId, awayTeamId, asOfEpochMs)
    }

    suspend fun baseline(source: String, competitionId: String, asOfEpochMs: Long): LeagueBaseline {
        val book = book(source, competitionId, asOfEpochMs) ?: return LeagueBaseline.FALLBACK
        return book.baseline(competitionId, asOfEpochMs)
    }

    suspend fun fit(source: String, competitionId: String, asOfEpochMs: Long): DixonColesFit? =
        book(source, competitionId, asOfEpochMs)?.fit(competitionId, asOfEpochMs)

    suspend fun eloRating(source: String, competitionId: String, teamId: String, asOfEpochMs: Long): Double =
        book(source, competitionId, asOfEpochMs)?.eloRating(teamId, asOfEpochMs) ?: TeamStrength.AVERAGE_ELO

    suspend fun matchCount(source: String, competitionId: String): Int =
        db.historicalDao().forCompetition(source, competitionId, clock.nowEpochMs()).size

    /** Invalidates the cache, e.g. after a historical import or a fixture sync. */
    suspend fun invalidate() = mutex.withLock { cache.clear() }
}

fun HistoricalMatchEntity.toRatingMatch(): RatingMatch = RatingMatch(
    homeTeamId = homeTeamId,
    awayTeamId = awayTeamId,
    homeGoals = homeGoals,
    awayGoals = awayGoals,
    kickoffEpochMs = kickoffEpochMs,
    weight = 1.0,
)
