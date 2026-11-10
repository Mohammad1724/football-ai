package com.footballai.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve user records; quarantine old API results whose 90-minute scope was lost. */
object HistoryMigration {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE matches ADD COLUMN scoreScope TEXT NOT NULL DEFAULT 'REGULATION'")
            db.execSQL("UPDATE matches SET scoreScope = 'LEGACY_UNVERIFIED' WHERE providerId != 'football-data-co-uk'")
            db.execSQL("CREATE TEMP TABLE history_v1 AS SELECT * FROM historical_matches")
            db.execSQL("DELETE FROM historical_matches")
            // Canonical IDs are shared by fixture and history writers. Insert oldest first
            // so a newer duplicate replaces the old copy under the existing unique index.
            db.execSQL("""
                INSERT OR REPLACE INTO historical_matches
                SELECT 'hist:' || source || ':' || competitionId || ':' || homeTeamId || ':' || awayTeamId || ':' || kickoffEpochMs,
                       source, competitionId, competitionName, homeTeamId, awayTeamId,
                       homeTeamName, awayTeamName, homeGoals, awayGoals, kickoffEpochMs,
                       homeOdds, drawOdds, awayOdds, importedAt
                FROM (
                    SELECT CASE WHEN source = 'football-data-co-uk' THEN source ELSE 'legacy-unverified:' || source END AS source,
                           CASE WHEN competitionId LIKE 'csv:%' THEN 'football-data-co-uk:league:' || substr(competitionId, 5) ELSE competitionId END AS competitionId,
                           competitionName,
                           CASE WHEN instr(homeTeamId, ':team:') > 0 THEN homeTeamId ELSE source || ':team:' || homeTeamId END AS homeTeamId,
                           CASE WHEN instr(awayTeamId, ':team:') > 0 THEN awayTeamId ELSE source || ':team:' || awayTeamId END AS awayTeamId,
                           homeTeamName, awayTeamName, homeGoals, awayGoals, kickoffEpochMs,
                           homeOdds, drawOdds, awayOdds, importedAt
                    FROM history_v1
                ) ORDER BY importedAt ASC
            """.trimIndent())
            db.execSQL("DROP TABLE history_v1")
            // Different goal lines share selectionKey (OVER/UNDER); the old primary
            // key silently replaced 0.5/1.5/2.5 with 3.5. Preserve legacy surviving rows.
            db.execSQL("ALTER TABLE prediction_markets RENAME TO prediction_markets_v1")
            db.execSQL("DROP INDEX IF EXISTS index_prediction_markets_predictionId")
            db.execSQL("CREATE TABLE prediction_markets (predictionId TEXT NOT NULL, marketType TEXT NOT NULL, selectionKey TEXT NOT NULL, line REAL, probability REAL NOT NULL, sortOrder INTEGER NOT NULL, PRIMARY KEY(predictionId, sortOrder), FOREIGN KEY(predictionId) REFERENCES predictions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO prediction_markets SELECT * FROM prediction_markets_v1")
            db.execSQL("DROP TABLE prediction_markets_v1")
            db.execSQL("CREATE INDEX index_prediction_markets_predictionId ON prediction_markets(predictionId)")
        }
    }
}
