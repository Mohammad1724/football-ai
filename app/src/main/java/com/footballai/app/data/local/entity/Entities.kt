package com.footballai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "competitions")
data class CompetitionEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val providerKey: String,
    val name: String,
    val country: String?,
    val emblemUrl: String?,
    val season: Int?,
    val hasFullCoverage: Boolean,
    val isFollowed: Boolean,
    val updatedAt: Long,
    /** CompetitionType name: CLUB_LEAGUE, CLUB_KNOCKOUT, INTERNATIONAL_* , WOMEN_*. */
    @ColumnInfo(defaultValue = "'CLUB_LEAGUE'")
    val competitionClass: String = "CLUB_LEAGUE",
    /** MEN or WOMEN. Never inferred from a team name. */
    @ColumnInfo(defaultValue = "'MEN'")
    val gender: String = "MEN",
    /** CATALOG / PROVIDER_DEFAULT / NAME_RULE / UNKNOWN, so the UI can be honest. */
    @ColumnInfo(defaultValue = "'UNKNOWN'")
    val classificationSource: String = "UNKNOWN",
)

@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val providerKey: String,
    val name: String,
    val shortName: String?,
    val crestUrl: String?,
    val updatedAt: Long,
    /** CLUB or NATIONAL. National teams are a distinct entity, never a club. */
    @ColumnInfo(defaultValue = "'CLUB'")
    val kind: String = "CLUB",
    /** ISO-3166 alpha-2 when the source publishes it; null otherwise (never guessed). */
    val countryCode: String? = null,
)

@Entity(
    tableName = "matches",
    indices = [
        Index("kickoffEpochMs"),
        Index("competitionId"),
        Index("status"),
        Index(value = ["providerId", "providerKey"], unique = true),
    ],
)
data class MatchEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val providerKey: String,
    val competitionId: String,
    val season: Int?,
    val matchday: Int?,
    val homeTeamId: String,
    val awayTeamId: String,
    val kickoffEpochMs: Long,
    val status: String,
    val minute: Int?,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val homeHalfTimeGoals: Int?,
    val awayHalfTimeGoals: Int?,
    val venue: String?,
    val oddsHome: Double?,
    val oddsDraw: Double?,
    val oddsAway: Double?,
    val hasStatistics: Boolean,
    val hasEvents: Boolean,
    val hasLineups: Boolean,
    val statisticsSyncedAt: Long?,
    val lastSyncedAt: Long,
    @ColumnInfo(defaultValue = "'REGULATION'")
    val scoreScope: String = "REGULATION",
    /** Only true when the source states the fixture is at a neutral ground. */
    @ColumnInfo(defaultValue = "0")
    val isNeutralVenue: Boolean = false,
)

@Entity(tableName = "match_statistics", primaryKeys = ["matchId"])
data class MatchStatisticsEntity(
    val matchId: String,
    val homeShots: Int?,
    val awayShots: Int?,
    val homeShotsOnTarget: Int?,
    val awayShotsOnTarget: Int?,
    val homePossession: Double?,
    val awayPossession: Double?,
    val homeCorners: Int?,
    val awayCorners: Int?,
    val homeYellowCards: Int?,
    val awayYellowCards: Int?,
    val homeRedCards: Int?,
    val awayRedCards: Int?,
    val homeExpectedGoals: Double?,
    val awayExpectedGoals: Double?,
    val updatedAt: Long,
)

@Entity(tableName = "match_events", indices = [Index("matchId")])
data class MatchEventEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val minute: Int,
    val type: String,
    val detail: String?,
    val teamId: String?,
    val playerName: String?,
    val assistName: String?,
)

@Entity(tableName = "lineup_players", indices = [Index("matchId"), Index("teamId")])
data class LineupPlayerEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val teamId: String,
    val name: String,
    val number: Int?,
    val position: String?,
    val isStarter: Boolean,
    val formation: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "predictions",
    indices = [
        Index("matchId"),
        Index("createdAtEpochMs"),
        Index("kind"),
    ],
)
data class PredictionEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val kind: String,
    val createdAtEpochMs: Long,
    val asOfEpochMs: Long,
    val modelName: String,
    val modelVersion: String,
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val over25: Double,
    val bttsYes: Double,
    val fittedMatches: Int,
    val rho: Double,
    val qualityLevel: String,
    val qualityCompleteness: Double,
    val qualityJson: String,
    val explanationsJson: String,
    val liveSnapshotJson: String?,
    val minute: Int?,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val oddsHome: Double?,
    val oddsDraw: Double?,
    val oddsAway: Double?,
    val resolvedAtEpochMs: Long?,
    val actualHomeGoals: Int?,
    val actualAwayGoals: Int?,
    val outcomeCorrect: Boolean?,
    val brier: Double?,
    val logLoss: Double?,
    /** Evidence-based confidence stored with the snapshot (never recomputed from the margin). */
    @ColumnInfo(defaultValue = "'INSUFFICIENT'")
    val confidenceLevel: String = "INSUFFICIENT",
    val confidenceJson: String? = null,
)

@Entity(
    tableName = "prediction_markets",
    primaryKeys = ["predictionId", "sortOrder"],
    indices = [Index("predictionId")],
    foreignKeys = [
        ForeignKey(
            entity = PredictionEntity::class,
            parentColumns = ["id"],
            childColumns = ["predictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PredictionMarketEntity(
    val predictionId: String,
    val marketType: String,
    val selectionKey: String,
    val line: Double?,
    val probability: Double,
    val sortOrder: Int,
)

@Entity(
    tableName = "historical_matches",
    indices = [
        Index("competitionId"),
        Index("kickoffEpochMs"),
        Index("source"),
        Index(value = ["source", "competitionId", "homeTeamId", "awayTeamId", "kickoffEpochMs"], unique = true),
    ],
)
data class HistoricalMatchEntity(
    @PrimaryKey val id: String,
    val source: String,
    val competitionId: String,
    val competitionName: String,
    val homeTeamId: String,
    val awayTeamId: String,
    val homeTeamName: String,
    val awayTeamName: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val kickoffEpochMs: Long,
    val homeOdds: Double?,
    val drawOdds: Double?,
    val awayOdds: Double?,
    val importedAt: Long,
    /**
     * True when the archive states the match was played on neutral ground.
     *
     * National-team results are full of neutral venues (tournaments, play-offs) and the
     * Elo replay would award a home advantage that did not exist without this flag.
     */
    val neutralVenue: Boolean = false,
)

@Entity(tableName = "standings", primaryKeys = ["competitionId", "season", "teamId"])
data class StandingEntity(
    val competitionId: String,
    val season: Int,
    val teamId: String,
    val teamName: String,
    val crestUrl: String?,
    val rank: Int,
    val points: Int,
    val played: Int,
    val goalDifference: Int,
    val form: String?,
    val updatedAt: Long,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
