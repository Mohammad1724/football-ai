package com.footballai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.footballai.app.data.local.LateForecastPolicy
import com.footballai.app.data.local.entity.CompetitionEntity
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.local.entity.LineupPlayerEntity
import com.footballai.app.data.local.entity.MatchAvailabilityEntity
import com.footballai.app.data.local.entity.MatchEntity
import com.footballai.app.data.local.entity.MatchEventEntity
import com.footballai.app.data.local.entity.MatchStatisticsEntity
import com.footballai.app.data.local.entity.LateForecastEntity
import com.footballai.app.data.local.entity.PredictionEntity
import com.footballai.app.data.local.entity.PredictionMarketEntity
import com.footballai.app.data.local.entity.StandingEntity
import com.footballai.app.data.local.entity.SyncMetaEntity
import com.footballai.app.data.local.entity.TeamEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CompetitionDao {
    @Query("SELECT * FROM competitions ORDER BY isFollowed DESC, name ASC")
    fun observeAll(): Flow<List<CompetitionEntity>>

    @Query("SELECT * FROM competitions WHERE isFollowed = 1 ORDER BY name ASC")
    suspend fun followed(): List<CompetitionEntity>

    @Query("SELECT * FROM competitions WHERE id = :id")
    suspend fun byId(id: String): CompetitionEntity?

    @Query("SELECT * FROM competitions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<CompetitionEntity>

    @Query("SELECT * FROM competitions ORDER BY isFollowed DESC, name ASC")
    suspend fun allOnce(): List<CompetitionEntity>

    @Query("SELECT * FROM competitions WHERE providerId = :providerId AND providerKey = :providerKey")
    suspend fun byProviderKey(providerId: String, providerKey: String): CompetitionEntity?

    @Upsert
    suspend fun upsertAll(competitions: List<CompetitionEntity>)

    @Query("UPDATE competitions SET isFollowed = :followed WHERE id = :id")
    suspend fun setFollowed(id: String, followed: Boolean)

    @Query("SELECT COUNT(*) FROM competitions")
    suspend fun count(): Int
}

/**
 * Card totals over the stored match statistics.
 *
 * [matches] counts every match with a statistics row, [cardMatches] only those whose card
 * columns were published — the average is taken over the latter, because a source that files
 * shots but not cards must not be read as a league that never books anyone.
 */
data class LeagueStatisticsSummary(
    val matches: Int,
    val cardMatches: Int,
    val yellowCards: Int?,
    val redCards: Int?,
)

/** How many stored teams of one source carry a badge address. */
data class TeamCrestCount(
    val providerId: String,
    val total: Int,
    val withCrest: Int,
)

@Dao
interface TeamDao {
    @Query("SELECT * FROM teams WHERE id IN (SELECT homeTeamId FROM matches WHERE competitionId = :competition UNION SELECT awayTeamId FROM matches WHERE competitionId = :competition)")
    suspend fun forCompetition(competition: String): List<TeamEntity>

    @Upsert
    suspend fun upsertAll(teams: List<TeamEntity>)

    @Query("SELECT * FROM teams WHERE id = :id")
    suspend fun byId(id: String): TeamEntity?

    @Query("SELECT * FROM teams WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TeamEntity>

    @Query("SELECT COUNT(*) FROM teams")
    suspend fun count(): Int

    /** How many stored teams actually carry a badge address. */
    @Query("SELECT COUNT(*) FROM teams WHERE crestUrl IS NOT NULL AND crestUrl != ''")
    suspend fun countWithCrest(): Int

    /** The team the last sync touched: the row a badge would be drawn from. */
    @Query("SELECT * FROM teams ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecentlyUpdated(): TeamEntity?

    /** Recent fixtures first; unsuccessful searches cool down individually, not the whole queue. */
    @Query("""
        SELECT teams.* FROM teams
        JOIN (
            SELECT teamId, MIN(ABS(kickoffEpochMs - :now)) AS distance FROM (
                SELECT homeTeamId AS teamId, kickoffEpochMs FROM matches
                WHERE (:fromEpochMs IS NULL OR kickoffEpochMs >= :fromEpochMs)
                  AND (:toEpochMs IS NULL OR kickoffEpochMs < :toEpochMs)
                UNION ALL
                SELECT awayTeamId AS teamId, kickoffEpochMs FROM matches
                WHERE (:fromEpochMs IS NULL OR kickoffEpochMs >= :fromEpochMs)
                  AND (:toEpochMs IS NULL OR kickoffEpochMs < :toEpochMs)
            ) GROUP BY teamId
        ) fixtures ON fixtures.teamId = teams.id
        LEFT JOIN sync_meta attempt ON attempt.key = 'crest-attempt-v8:' || teams.id
        WHERE (teams.crestUrl IS NULL OR TRIM(teams.crestUrl) = '')
          AND teams.kind = 'CLUB'
          AND NOT (teams.providerId = 'espn-open' AND TRIM(teams.providerKey) != ''
                   AND TRIM(teams.providerKey) NOT GLOB '*[^0-9]*')
          AND (attempt.updatedAt IS NULL OR attempt.updatedAt <= :retryBefore)
        ORDER BY fixtures.distance ASC, teams.id ASC LIMIT :limit
    """)
    suspend fun crestlessInFixtures(
        now: Long, retryBefore: Long, limit: Int, fromEpochMs: Long?, toEpochMs: Long?,
    ): List<TeamEntity>

    @Query("""
        SELECT DISTINCT competitions.gender FROM competitions
        JOIN matches ON matches.competitionId = competitions.id
        WHERE matches.homeTeamId = :teamId OR matches.awayTeamId = :teamId
    """)
    suspend fun fixtureGenders(teamId: String): List<String>

    @Query("""
        SELECT DISTINCT competitions.providerKey FROM competitions
        JOIN matches ON matches.competitionId = competitions.id
        WHERE matches.homeTeamId = :teamId OR matches.awayTeamId = :teamId
    """)
    suspend fun fixtureCompetitionKeys(teamId: String): List<String>

    /** Men's CSV leagues represented in this day, independent of search-attempt cooldown. */
    @Query("""
        SELECT DISTINCT c.* FROM competitions c JOIN matches m ON c.id = m.competitionId
        WHERE c.providerId = 'football-data-co-uk' AND c.gender = 'MEN'
          AND m.kickoffEpochMs >= :fromEpochMs AND m.kickoffEpochMs < :toEpochMs
        ORDER BY c.name
    """)
    suspend fun crestCompetitionsForDay(fromEpochMs: Long, toEpochMs: Long): List<CompetitionEntity>

    @Query("""
        SELECT * FROM teams WHERE providerId = 'football-data-co-uk' AND kind = 'CLUB'
          AND (crestUrl IS NULL OR TRIM(crestUrl) = '') AND id IN (
            SELECT homeTeamId FROM matches WHERE competitionId = :competitionId
              AND kickoffEpochMs >= :fromEpochMs AND kickoffEpochMs < :toEpochMs
            UNION
            SELECT awayTeamId FROM matches WHERE competitionId = :competitionId
              AND kickoffEpochMs >= :fromEpochMs AND kickoffEpochMs < :toEpochMs
          )
    """)
    suspend fun crestlessTeamsForCompetitionDay(
        competitionId: String, fromEpochMs: Long, toEpochMs: Long,
    ): List<TeamEntity>

    /** Do not overwrite a crest delivered by a concurrent fixture sync. */
    @Query("UPDATE teams SET crestUrl = :url WHERE id = :id AND (crestUrl IS NULL OR TRIM(crestUrl) = '')")
    suspend fun setCrestIfMissing(id: String, url: String): Int

    @Query(
        "SELECT * FROM teams WHERE (crestUrl IS NULL OR crestUrl = '') AND id IN " +
            "(SELECT homeTeamId FROM matches UNION SELECT awayTeamId FROM matches) " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun crestlessInFixtures(limit: Int): List<TeamEntity>

    /**
     * Clubs still waiting for their first attempt under the current marker.
     *
     * The predicate is the one [crestlessInFixtures] selects with, so the number is the work
     * the next sync can actually do — not every crestless row in the database.
     */
    @Query(
        "SELECT COUNT(*) FROM teams t LEFT JOIN sync_meta a ON a.key = :prefix || t.id " +
            "WHERE (t.crestUrl IS NULL OR TRIM(t.crestUrl) = '') AND t.kind = 'CLUB' " +
            "AND t.id IN (SELECT homeTeamId FROM matches UNION SELECT awayTeamId FROM matches) " +
            "AND NOT (t.providerId = 'espn-open' AND TRIM(t.providerKey) != '' " +
            "AND TRIM(t.providerKey) NOT GLOB '*[^0-9]*') " +
            "AND a.updatedAt IS NULL",
    )
    suspend fun waitingForCrestAttempt(prefix: String): Int

    /** Clubs that were asked under the current marker and are still on initials. */
    @Query(
        "SELECT COUNT(*) FROM teams t JOIN sync_meta a ON a.key = :prefix || t.id " +
            "WHERE (t.crestUrl IS NULL OR TRIM(t.crestUrl) = '') AND t.kind = 'CLUB' " +
            "AND t.id IN (SELECT homeTeamId FROM matches UNION SELECT awayTeamId FROM matches)",
    )
    suspend fun askedWithoutCrest(prefix: String): Int

    @Query(
        "SELECT providerId AS providerId, COUNT(*) AS total, " +
            "SUM(CASE WHEN crestUrl IS NULL OR crestUrl = '' THEN 0 ELSE 1 END) AS withCrest " +
            "FROM teams GROUP BY providerId ORDER BY total DESC",
    )
    suspend fun crestCountsByProvider(): List<TeamCrestCount>
}

@Dao
interface MatchDao {
    @Upsert
    suspend fun upsertAll(matches: List<MatchEntity>)

    @Transaction
    suspend fun mergeFixtures(matches: List<MatchEntity>) {
        upsertAll(matches.map { incoming ->
            val old = byId(incoming.id)
            incoming.copy(
                hasStatistics = old?.hasStatistics ?: false,
                hasEvents = old?.hasEvents ?: false,
                hasLineups = old?.hasLineups ?: false,
                statisticsSyncedAt = old?.statisticsSyncedAt,
            )
        })
    }

    @Query("SELECT * FROM matches WHERE providerId = :provider AND competitionId = :competition AND season = :season AND homeTeamId = :home AND awayTeamId = :away")
    suspend fun csvCandidates(provider: String, competition: String, season: Int, home: String, away: String): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE providerId = :provider AND kickoffEpochMs <= :now AND status = 'SCHEDULED'")
    suspend fun pendingResults(provider: String, now: Long): List<MatchEntity>

    @Query("SELECT COUNT(*) FROM matches WHERE kickoffEpochMs BETWEEN :fromEpochMs AND :toEpochMs")
    suspend fun countBetween(fromEpochMs: Long, toEpochMs: Long): Int

    @Query("SELECT * FROM matches WHERE id = :id")
    suspend fun byId(id: String): MatchEntity?

    @Query("SELECT * FROM matches WHERE id = :id")
    fun byIdFlow(id: String): Flow<MatchEntity?>

    @Query("SELECT * FROM matches WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MatchEntity>

    @Query("SELECT * FROM matches ORDER BY kickoffEpochMs DESC")
    fun observeAll(): Flow<List<MatchEntity>>

    @Query(
        """
        SELECT * FROM matches
        WHERE kickoffEpochMs BETWEEN :fromEpochMs AND :toEpochMs
        ORDER BY kickoffEpochMs ASC
        """
    )
    fun observeBetween(fromEpochMs: Long, toEpochMs: Long): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE status IN ('LIVE', 'HALF_TIME') ORDER BY kickoffEpochMs ASC")
    fun observeLive(): Flow<List<MatchEntity>>

    @Query(
        """
        SELECT * FROM matches
        WHERE status IN ('SCHEDULED', 'LIVE', 'HALF_TIME') AND kickoffEpochMs >= :fromEpochMs
        ORDER BY kickoffEpochMs ASC
        LIMIT :limit
        """
    )
    fun observeUpcoming(fromEpochMs: Long, limit: Int): Flow<List<MatchEntity>>

    @Query(
        """
        SELECT * FROM matches
        WHERE competitionId = :competitionId
        ORDER BY kickoffEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun recentForCompetition(competitionId: String, limit: Int): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE competitionId = :competitionId")
    suspend fun forCompetition(competitionId: String): List<MatchEntity>

    @Query(
        """
        SELECT * FROM matches
        WHERE (homeTeamId = :teamId OR awayTeamId = :teamId)
        ORDER BY kickoffEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun recentForTeam(teamId: String, limit: Int): List<MatchEntity>

    @Query(
        """
        SELECT * FROM matches
        WHERE status = 'SCHEDULED' AND kickoffEpochMs BETWEEN :fromEpochMs AND :toEpochMs
        ORDER BY kickoffEpochMs ASC
        """
    )
    suspend fun scheduledBetween(fromEpochMs: Long, toEpochMs: Long): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE status IN ('LIVE', 'HALF_TIME')")
    suspend fun liveMatches(): List<MatchEntity>

    @Query(
        """
        SELECT * FROM matches
        WHERE id != :matchId AND status = 'FINISHED'
          AND homeGoals IS NOT NULL AND awayGoals IS NOT NULL
          AND kickoffEpochMs < :beforeEpochMs
          AND ((homeTeamId = :teamA AND awayTeamId = :teamB)
            OR (homeTeamId = :teamB AND awayTeamId = :teamA))
        ORDER BY kickoffEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun headToHead(matchId: String, teamA: String, teamB: String, beforeEpochMs: Long, limit: Int): List<MatchEntity>

    @Query("SELECT COUNT(*) FROM matches")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM matches
        WHERE status = 'FINISHED' AND kickoffEpochMs >= :fromEpochMs
        ORDER BY kickoffEpochMs ASC
        """
    )
    suspend fun finishedSince(fromEpochMs: Long): List<MatchEntity>

    @Query(
        """
        UPDATE matches
        SET status = :status, minute = :minute, homeGoals = :homeGoals, awayGoals = :awayGoals,
            lastSyncedAt = :syncedAt
        WHERE id = :id
        """
    )
    suspend fun updateLiveState(
        id: String,
        status: String,
        minute: Int?,
        homeGoals: Int?,
        awayGoals: Int?,
        syncedAt: Long,
    )

    @Query("UPDATE matches SET hasStatistics = 1, statisticsSyncedAt = :syncedAt WHERE id = :id")
    suspend fun markStatisticsSynced(id: String, syncedAt: Long)

    @Query("UPDATE matches SET hasEvents = 1 WHERE id = :id")
    suspend fun markEventsSynced(id: String)

    @Query("UPDATE matches SET hasLineups = 1 WHERE id = :id")
    suspend fun markLineupsSynced(id: String)

    @Query("DELETE FROM matches WHERE kickoffEpochMs < :cutoffEpochMs AND status IN ('FINISHED', 'CANCELLED')")
    suspend fun pruneOldMatches(cutoffEpochMs: Long): Int

    /**
     * Finished matches of one competition, with a score, for the league's own statistics.
     *
     * A match without both goals is not a result and would skew every average, so it is not
     * in the list at all.
     */
    @Query(
        "SELECT * FROM matches WHERE competitionId = :competitionId " +
            "AND status = 'FINISHED' " +
            "AND homeGoals IS NOT NULL AND awayGoals IS NOT NULL " +
            "AND (:season IS NULL OR season = :season) " +
            "ORDER BY kickoffEpochMs ASC",
    )
    suspend fun finishedMatches(competitionId: String, season: Int?): List<MatchEntity>

    /**
     * What the stored per-match statistics add up to.
     *
     * Only matches whose card figures were actually downloaded are counted, and the caller is
     * told how many those are: an average over four matches must not read as if it were the
     * whole season.
     */
    @Query(
        "SELECT COUNT(*) AS matches, " +
            "SUM(CASE WHEN s.homeYellowCards IS NOT NULL OR s.awayYellowCards IS NOT NULL " +
            "OR s.homeRedCards IS NOT NULL OR s.awayRedCards IS NOT NULL THEN 1 ELSE 0 END) AS cardMatches, " +
            "SUM(COALESCE(s.homeYellowCards, 0) + COALESCE(s.awayYellowCards, 0)) AS yellowCards, " +
            "SUM(COALESCE(s.homeRedCards, 0) + COALESCE(s.awayRedCards, 0)) AS redCards " +
            "FROM match_statistics s JOIN matches m ON m.id = s.matchId " +
            "WHERE m.competitionId = :competitionId AND m.status = 'FINISHED' " +
            "AND (:season IS NULL OR m.season = :season)",
    )
    suspend fun statisticsSummary(competitionId: String, season: Int?): LeagueStatisticsSummary
}

@Dao
interface StatisticsDao {
    @Upsert
    suspend fun upsert(entity: MatchStatisticsEntity)

    @Query("SELECT * FROM match_statistics WHERE matchId = :matchId")
    suspend fun byMatch(matchId: String): MatchStatisticsEntity?

    @Query("SELECT * FROM match_statistics WHERE matchId = :matchId")
    fun observeByMatch(matchId: String): Flow<MatchStatisticsEntity?>
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<MatchEventEntity>)

    @Query("DELETE FROM match_events WHERE matchId = :matchId")
    suspend fun deleteForMatch(matchId: String)

    @Transaction
    suspend fun replaceForMatch(matchId: String, events: List<MatchEventEntity>) {
        deleteForMatch(matchId)
        insertAll(events)
    }

    @Query("SELECT * FROM match_events WHERE matchId = :matchId ORDER BY minute ASC")
    fun observeForMatch(matchId: String): Flow<List<MatchEventEntity>>

    @Query("SELECT * FROM match_events WHERE matchId = :matchId ORDER BY minute ASC")
    suspend fun forMatchNow(matchId: String): List<MatchEventEntity>

    /**
     * Goals this club has actually scored, attributed to the player who scored them, over
     * the window the caller asks for. Only matches whose events were synced contribute, so
     * an empty result means "not recorded", never "nobody scores".
     */
    @Query(
        """
        SELECT e.playerName AS playerName, COUNT(*) AS goals
        FROM match_events e INNER JOIN matches m ON m.id = e.matchId
        WHERE e.teamId = :teamId
          AND e.type IN ('GOAL', 'PENALTY_GOAL')
          AND e.playerName IS NOT NULL AND e.playerName != ''
          AND m.kickoffEpochMs >= :sinceEpochMs
        GROUP BY e.playerName
        """,
    )
    suspend fun goalsByPlayerForTeamSince(teamId: String, sinceEpochMs: Long): List<PlayerGoalCount>
}

/** One row of [EventDao.goalsByPlayerForTeamSince]. */
data class PlayerGoalCount(val playerName: String, val goals: Int)

@Dao
interface LineupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(players: List<LineupPlayerEntity>)

    @Query("DELETE FROM lineup_players WHERE matchId = :matchId")
    suspend fun deleteForMatch(matchId: String)

    @Transaction
    suspend fun replaceForMatch(matchId: String, players: List<LineupPlayerEntity>) {
        deleteForMatch(matchId)
        insertAll(players)
    }

    @Query("SELECT * FROM lineup_players WHERE matchId = :matchId ORDER BY teamId, isStarter DESC, number ASC")
    fun observeForMatch(matchId: String): Flow<List<LineupPlayerEntity>>

    @Query("SELECT * FROM lineup_players WHERE matchId = :matchId ORDER BY teamId, isStarter DESC, number ASC")
    suspend fun forMatchNow(matchId: String): List<LineupPlayerEntity>
}

@Dao
interface PredictionDao {
    @Upsert
    suspend fun upsert(prediction: PredictionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarkets(markets: List<PredictionMarketEntity>)

    @Transaction
    suspend fun save(prediction: PredictionEntity, markets: List<PredictionMarketEntity>) {
        if (prediction.kind == "PRE_MATCH" && firstPreMatch(prediction.matchId, prediction.modelVersion) != null) return
        upsert(prediction)
        insertMarkets(markets)
    }

    @Query("SELECT * FROM predictions WHERE matchId = :matchId AND modelVersion = :version AND kind = 'PRE_MATCH' ORDER BY createdAtEpochMs ASC, id ASC LIMIT 1")
    suspend fun firstPreMatch(matchId: String, version: String): PredictionEntity?

    @Query("SELECT p.* FROM predictions p INNER JOIN matches m ON m.id = p.matchId WHERE p.resolvedAtEpochMs IS NOT NULL AND p.kind = 'PRE_MATCH' AND p.modelVersion = :version AND p.createdAtEpochMs < m.kickoffEpochMs AND m.scoreScope = 'REGULATION' AND m.status = 'FINISHED' AND m.homeGoals = p.actualHomeGoals AND m.awayGoals = p.actualAwayGoals AND p.id = (SELECT p2.id FROM predictions p2 WHERE p2.matchId = p.matchId AND p2.kind = 'PRE_MATCH' AND p2.modelVersion = :version AND p2.createdAtEpochMs < m.kickoffEpochMs ORDER BY p2.createdAtEpochMs ASC, p2.id ASC LIMIT 1) ORDER BY p.createdAtEpochMs DESC LIMIT 500")
    suspend fun evaluatedPreMatch(version: String): List<PredictionEntity>

    @Query("SELECT COUNT(*) FROM late_forecasts l INNER JOIN predictions p ON p.id = l.predictionId INNER JOIN matches m ON m.id = p.matchId WHERE p.modelVersion = :version AND p.kind = 'PRE_MATCH' AND l.kickoffEpochMs = m.kickoffEpochMs")
    suspend fun lateForecastCount(version: String): Int

    @Query("SELECT * FROM late_forecasts WHERE predictionId = :predictionId")
    suspend fun lateForecast(predictionId: String): LateForecastEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLateForecast(snapshot: LateForecastEntity)

    /** Check kickoff/status again in the same transaction as the insert. */
    @Transaction
    suspend fun saveLateForecast(snapshot: LateForecastEntity, matchId: String, version: String, finishedAt: Long) {
        val first = firstPreMatch(matchId, version) ?: return
        val match = comparisonMatch(matchId) ?: return
        if (!LateForecastPolicy.eligible(first.id, snapshot.predictionId, match.status,
                match.kickoffEpochMs, snapshot.kickoffEpochMs, first.createdAtEpochMs,
                snapshot.createdAtEpochMs, finishedAt)) return
        insertLateForecast(snapshot)
    }

    @Query("SELECT * FROM matches WHERE id = :matchId")
    suspend fun comparisonMatch(matchId: String): MatchEntity?

    @Query("SELECT * FROM predictions WHERE id = :id")
    suspend fun byId(id: String): PredictionEntity?

    @Query(
        """
        SELECT * FROM predictions
        WHERE matchId = :matchId
        ORDER BY createdAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun latestForMatch(matchId: String): PredictionEntity?

    @Query(
        """
        SELECT * FROM predictions
        WHERE matchId = :matchId AND kind = :kind
        ORDER BY createdAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun latestForMatch(matchId: String, kind: String): PredictionEntity?

    @Query("SELECT * FROM prediction_markets WHERE predictionId = :predictionId ORDER BY sortOrder ASC")
    suspend fun markets(predictionId: String): List<PredictionMarketEntity>

    // Include matches in Room's invalidation set: the match can finish without any
    // prediction row changing, and the history must then update its status.
    @Query("SELECT p.* FROM predictions p LEFT JOIN matches m ON m.id = p.matchId ORDER BY p.createdAtEpochMs DESC, p.id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PredictionEntity>>

    @Query("SELECT COUNT(*) FROM predictions")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM predictions")
    suspend fun count(): Int

    @Query(
        "SELECT * FROM predictions WHERE matchId IN (:matchIds) AND kind = 'PRE_MATCH' " +
            "AND modelVersion = :version ORDER BY createdAtEpochMs ASC",
    )
    suspend fun preMatchForMatches(matchIds: List<String>, version: String): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE resolvedAtEpochMs IS NULL")
    suspend fun unresolved(): List<PredictionEntity>

    @Query("UPDATE predictions SET resolvedAtEpochMs = :resolvedAt, actualHomeGoals = :homeGoals, actualAwayGoals = :awayGoals, outcomeCorrect = :correct, brier = :brier, logLoss = :logLoss WHERE id = :id")
    suspend fun resolve(
        id: String,
        resolvedAt: Long,
        homeGoals: Int,
        awayGoals: Int,
        correct: Boolean,
        brier: Double,
        logLoss: Double,
    )

    @Query("SELECT * FROM predictions WHERE resolvedAtEpochMs IS NOT NULL ORDER BY resolvedAtEpochMs DESC LIMIT :limit")
    suspend fun resolved(limit: Int): List<PredictionEntity>

    /**
     * Resolved pre-match forecasts for the competition class and model version, newest
     * match first. One forecast per match: duplicate snapshots of one match, live
     * forecasts and other model versions would all bias a fit.
     *
     * Two details matter more than they look.
     *
     * The window is the newest [limit] rows *by match time*, not the oldest: with
     * `ASC ... LIMIT` the sample was the first few thousand forecasts ever resolved, so
     * once that many existed no new result could ever enter the calibration.
     *
     * The sort key is the match clock, not the moment the forecast was written. A
     * forecast written ten days before kickoff used to sort ahead of a match played last
     * night; a caller that treats this list as a chronology — the calibrator withholds
     * the most recent slice — was therefore training on matches played after the ones it
     * judged itself on. Callers reverse this list to read it in playing order.
     */
    @Query(
        "SELECT p.* FROM predictions p " +
            "INNER JOIN matches m ON m.id = p.matchId " +
            "INNER JOIN competitions c ON c.id = m.competitionId " +
            "WHERE p.resolvedAtEpochMs IS NOT NULL AND p.kind = 'PRE_MATCH' " +
            "AND p.modelVersion = :version AND c.competitionClass = :competitionClass " +
            "AND m.scoreScope = 'REGULATION' AND m.status = 'FINISHED' " +
            "AND m.homeGoals = p.actualHomeGoals AND m.awayGoals = p.actualAwayGoals " +
            "AND p.id = (SELECT p2.id FROM predictions p2 WHERE p2.matchId = p.matchId AND p2.kind = 'PRE_MATCH' " +
            "AND p2.modelVersion = :version AND p2.createdAtEpochMs < m.kickoffEpochMs " +
            "ORDER BY p2.createdAtEpochMs ASC, p2.id ASC LIMIT 1) " +
            "ORDER BY m.kickoffEpochMs DESC, p.createdAtEpochMs DESC LIMIT :limit",
    )
    suspend fun resolvedForClass(version: String, competitionClass: String, limit: Int): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE matchId = :matchId AND kind = :kind ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun historyForMatch(matchId: String, kind: String, limit: Int): List<PredictionEntity>
}

@Dao
interface HistoricalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(matches: List<HistoricalMatchEntity>): List<Long>

    @Query("SELECT * FROM historical_matches WHERE competitionId = :competitionId AND kickoffEpochMs <= :asOfEpochMs ORDER BY kickoffEpochMs ASC")
    suspend fun forTable(competitionId: String, asOfEpochMs: Long): List<HistoricalMatchEntity>

    @Query("SELECT * FROM historical_matches WHERE source = :source AND competitionId = :competitionId AND kickoffEpochMs <= :asOfEpochMs ORDER BY kickoffEpochMs ASC")
    suspend fun forCompetition(source: String, competitionId: String, asOfEpochMs: Long): List<HistoricalMatchEntity>

    /** Fills the xG columns of one stored history row from the xG mirror. */
    @Query("UPDATE historical_matches SET homeXg = :homeXg, awayXg = :awayXg WHERE id = :id")
    suspend fun updateXg(id: String, homeXg: Double, awayXg: Double)

    @Query("SELECT * FROM historical_matches WHERE source = :source AND kickoffEpochMs <= :asOfEpochMs ORDER BY kickoffEpochMs ASC")
    suspend fun forSource(source: String, asOfEpochMs: Long): List<HistoricalMatchEntity>

    @Query("SELECT * FROM historical_matches ORDER BY kickoffEpochMs ASC")
    suspend fun all(): List<HistoricalMatchEntity>

    @Query("SELECT competitionId, competitionName, source, COUNT(*) AS matches, MIN(kickoffEpochMs) AS fromEpochMs, MAX(kickoffEpochMs) AS toEpochMs FROM historical_matches GROUP BY source, competitionId, competitionName ORDER BY matches DESC")
    fun observeSummary(): Flow<List<HistoricalSummaryRow>>

    @Query("SELECT COUNT(*) FROM historical_matches")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM historical_matches")
    suspend fun count(): Int

    @Query("DELETE FROM historical_matches WHERE source = :source")
    suspend fun deleteForSource(source: String): Int

    @Query("SELECT COUNT(*) FROM historical_matches WHERE source = :source")
    suspend fun countForSource(source: String): Int

    /**
     * The archive rows that involve one of these team ids, newest first.
     *
     * A caller passes more than one id when a team is known under two names: the id the
     * fixture uses and the id the archive uses. Nothing is matched on a guess — the query
     * only returns what the ids point at.
     */
    @Query(
        "SELECT * FROM historical_matches WHERE " +
            "(homeTeamId IN (:teamIds) OR awayTeamId IN (:teamIds)) " +
            "AND kickoffEpochMs <= :asOfEpochMs ORDER BY kickoffEpochMs DESC LIMIT :limit",
    )
    suspend fun forTeamIds(teamIds: List<String>, asOfEpochMs: Long, limit: Int): List<HistoricalMatchEntity>

    /** Past meetings between two sides, whatever name each side is recorded under. */
    @Query(
        "SELECT * FROM historical_matches WHERE " +
            "((homeTeamId IN (:homeIds) AND awayTeamId IN (:awayIds)) OR " +
            "(homeTeamId IN (:awayIds) AND awayTeamId IN (:homeIds))) " +
            "AND kickoffEpochMs <= :asOfEpochMs ORDER BY kickoffEpochMs DESC LIMIT :limit",
    )
    suspend fun between(homeIds: List<String>, awayIds: List<String>, asOfEpochMs: Long, limit: Int): List<HistoricalMatchEntity>

    @Query("SELECT MIN(kickoffEpochMs) FROM historical_matches WHERE source = :source")
    suspend fun earliestForSource(source: String): Long?

    @Query("SELECT MAX(kickoffEpochMs) FROM historical_matches WHERE source = :source")
    suspend fun latestForSource(source: String): Long?

    @Query(
        "SELECT COUNT(*) FROM (" +
            "SELECT homeTeamId AS teamId FROM historical_matches WHERE source = :source " +
            "UNION SELECT awayTeamId FROM historical_matches WHERE source = :source)",
    )
    suspend fun teamCountForSource(source: String): Int
}

data class HistoricalSummaryRow(
    val competitionId: String,
    val competitionName: String,
    val source: String,
    val matches: Int,
    val fromEpochMs: Long,
    val toEpochMs: Long,
)

@Dao
interface StandingDao {
    @Query("DELETE FROM standings WHERE competitionId = :competitionId AND season = :season")
    suspend fun deleteSeason(competitionId: String, season: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<StandingEntity>)

    @Query("SELECT * FROM standings WHERE competitionId = :competitionId AND season = :season ORDER BY rank ASC")
    fun observe(competitionId: String, season: Int): Flow<List<StandingEntity>>

    @Query("SELECT * FROM standings WHERE competitionId = :competitionId AND season = :season ORDER BY rank ASC")
    suspend fun byCompetition(competitionId: String, season: Int): List<StandingEntity>

    @Query("SELECT COUNT(*) FROM standings WHERE competitionId = :competitionId")
    suspend fun countForCompetition(competitionId: String): Int

    /** The newest season stored for a competition: the table to fall back to, never a guess. */
    @Query("SELECT MAX(season) FROM standings WHERE competitionId = :competitionId")
    suspend fun latestSeason(competitionId: String): Int?
}

@Dao
interface AvailabilityDao {
    @Upsert
    suspend fun upsertAll(rows: List<MatchAvailabilityEntity>)

    @Query("SELECT * FROM match_availability WHERE matchId = :matchId")
    suspend fun forMatch(matchId: String): MatchAvailabilityEntity?
}

@Dao
interface SyncMetaDao {
    @Upsert
    suspend fun put(entity: SyncMetaEntity)

    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun get(key: String): String?

    /** The whole row, for the few callers that need when it was written. */
    @Query("SELECT * FROM sync_meta WHERE key = :key")
    suspend fun entity(key: String): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta")
    fun observeAll(): Flow<List<SyncMetaEntity>>
}
