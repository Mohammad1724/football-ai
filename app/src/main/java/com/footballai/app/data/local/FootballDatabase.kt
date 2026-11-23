package com.footballai.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.footballai.app.data.local.dao.AvailabilityDao
import com.footballai.app.data.local.dao.CompetitionDao
import com.footballai.app.data.local.dao.EventDao
import com.footballai.app.data.local.dao.HistoricalDao
import com.footballai.app.data.local.dao.LineupDao
import com.footballai.app.data.local.dao.MatchDao
import com.footballai.app.data.local.dao.PredictionDao
import com.footballai.app.data.local.dao.StandingDao
import com.footballai.app.data.local.dao.StatisticsDao
import com.footballai.app.data.local.dao.SyncMetaDao
import com.footballai.app.data.local.dao.TeamDao
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

@Database(
    entities = [
        CompetitionEntity::class,
        TeamEntity::class,
        MatchEntity::class,
        MatchAvailabilityEntity::class,
        MatchStatisticsEntity::class,
        MatchEventEntity::class,
        LineupPlayerEntity::class,
        PredictionEntity::class,
        LateForecastEntity::class,
        PredictionMarketEntity::class,
        HistoricalMatchEntity::class,
        StandingEntity::class,
        SyncMetaEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class FootballDatabase : RoomDatabase() {
    abstract fun competitionDao(): CompetitionDao
    abstract fun teamDao(): TeamDao
    abstract fun matchDao(): MatchDao
    abstract fun availabilityDao(): AvailabilityDao
    abstract fun statisticsDao(): StatisticsDao
    abstract fun eventDao(): EventDao
    abstract fun lineupDao(): LineupDao
    abstract fun predictionDao(): PredictionDao
    abstract fun historicalDao(): HistoricalDao
    abstract fun standingDao(): StandingDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        private const val DATABASE_NAME = "football-ai.db"

        fun create(context: Context): FootballDatabase = Room.databaseBuilder(
            context.applicationContext,
            FootballDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            HistoryMigration.MIGRATION_1_2,
            HistoryMigration.MIGRATION_2_3,
            HistoryMigration.MIGRATION_3_4,
            HistoryMigration.MIGRATION_4_5,
            HistoryMigration.MIGRATION_5_6,
            HistoryMigration.MIGRATION_6_7,
            HistoryMigration.MIGRATION_7_8,
        ).build()
    }
}
