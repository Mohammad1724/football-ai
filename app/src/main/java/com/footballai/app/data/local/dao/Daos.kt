package com.footballai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.footballai.app.data.local.entity.CompetitionEntity
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.local.entity.LineupPlayerEntity
import com.footballai.app.data.local.entity.MatchEntity
import com.footballai.app.data.local.entity.MatchEventEntity
import com.footballai.app.data.local.entity.MatchStatisticsEntity
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
        WHERE id = :matchId OR (homeTeamId IN (:teamA, :teamB) AND awayTeamId IN (:teamA, :teamB))
        ORDER BY kickoffEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun headToHead(matchId: String, teamA: String, teamB: String, limit: Int): List<MatchEntity>

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
}

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

    @Query("SELECT * FROM predictions WHERE matchId = :matchId AND modelVersion = :version AND kind = 'PRE_MATCH' ORDER BY createdAtEpochMs ASC LIMIT 1")
    suspend fun firstPreMatch(matchId: String, version: String): PredictionEntity?

    @Query("SELECT p.* FROM predictions p INNER JOIN matches m ON m.id = p.matchId WHERE p.resolvedAtEpochMs IS NOT NULL AND p.kind = 'PRE_MATCH' AND p.modelVersion = :version AND p.createdAtEpochMs < m.kickoffEpochMs AND m.scoreScope = 'REGULATION' AND p.id = (SELECT p2.id FROM predictions p2 WHERE p2.matchId = p.matchId AND p2.kind = 'PRE_MATCH' AND p2.modelVersion = :version AND p2.createdAtEpochMs < m.kickoffEpochMs ORDER BY p2.createdAtEpochMs ASC, p2.id ASC LIMIT 1) ORDER BY p.createdAtEpochMs DESC LIMIT 500")
    suspend fun evaluatedPreMatch(version: String): List<PredictionEntity>

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

    @Query("SELECT * FROM predictions ORDER BY createdAtEpochMs DESC LIMIT :limit")
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
     * Chronologically ordered, one pre-match forecast per match, for the competition
     * class and model version. This is the only sample a calibration may be fitted on:
     * duplicate snapshots of one match, live forecasts and other model versions would
     * all bias the fit.
     */
    @Query(
        "SELECT p.* FROM predictions p " +
            "INNER JOIN matches m ON m.id = p.matchId " +
            "INNER JOIN competitions c ON c.id = m.competitionId " +
            "WHERE p.resolvedAtEpochMs IS NOT NULL AND p.kind = 'PRE_MATCH' " +
            "AND p.modelVersion = :version AND c.competitionClass = :competitionClass " +
            "AND m.scoreScope = 'REGULATION' " +
            "AND p.id = (SELECT p2.id FROM predictions p2 WHERE p2.matchId = p.matchId AND p2.kind = 'PRE_MATCH' " +
            "AND p2.modelVersion = :version AND p2.createdAtEpochMs < m.kickoffEpochMs " +
            "ORDER BY p2.createdAtEpochMs ASC, p2.id ASC LIMIT 1) " +
            "ORDER BY p.createdAtEpochMs ASC LIMIT :limit",
    )
    suspend fun resolvedForClass(version: String, competitionClass: String, limit: Int): List<PredictionEntity>

    @Query(
        "SELECT COUNT(*) FROM predictions p " +
            "INNER JOIN matches m ON m.id = p.matchId " +
            "INNER JOIN competitions c ON c.id = m.competitionId " +
            "WHERE p.resolvedAtEpochMs IS NOT NULL AND p.kind = 'PRE_MATCH' " +
            "AND p.modelVersion = :version AND c.competitionClass = :competitionClass " +
            "AND m.scoreScope = 'REGULATION'",
    )
    suspend fun resolvedCountForClass(version: String, competitionClass: String): Int

    @Query("SELECT * FROM predictions WHERE matchId = :matchId AND kind = :kind ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun historyForMatch(matchId: String, kind: String, limit: Int): List<PredictionEntity>
}

@Dao
interface HistoricalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(matches: List<HistoricalMatchEntity>): List<Long>

    @Query("SELECT * FROM historical_matches WHERE source = :source AND competitionId = :competitionId AND kickoffEpochMs <= :asOfEpochMs ORDER BY kickoffEpochMs ASC")
    suspend fun forCompetition(source: String, competitionId: String, asOfEpochMs: Long): List<HistoricalMatchEntity>

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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<StandingEntity>)

    @Query("SELECT * FROM standings WHERE competitionId = :competitionId AND season = :season ORDER BY rank ASC")
    fun observe(competitionId: String, season: Int): Flow<List<StandingEntity>>

    @Query("SELECT * FROM standings WHERE competitionId = :competitionId AND season = :season ORDER BY rank ASC")
    suspend fun byCompetition(competitionId: String, season: Int): List<StandingEntity>

    @Query("SELECT COUNT(*) FROM standings WHERE competitionId = :competitionId")
    suspend fun countForCompetition(competitionId: String): Int
}

@Dao
interface SyncMetaDao {
    @Upsert
    suspend fun put(entity: SyncMetaEntity)

    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sync_meta")
    fun observeAll(): Flow<List<SyncMetaEntity>>
}
