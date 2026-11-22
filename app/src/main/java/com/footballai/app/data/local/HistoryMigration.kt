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

    /** Preserve all existing forecasts; comparison snapshots start empty. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS late_forecasts (predictionId TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, kickoffEpochMs INTEGER NOT NULL, homeWin REAL NOT NULL, draw REAL NOT NULL, awayWin REAL NOT NULL, PRIMARY KEY(predictionId), FOREIGN KEY(predictionId) REFERENCES predictions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        }
    }

    /**
     * 4 -> 5: the columns a table is read by.
     *
     * Additive and nullable on purpose: a source that publishes no wins column leaves the
     * field empty, and an empty field is drawn as a dash. Storing a zero there would be a
     * number the source never said.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE standings ADD COLUMN wins INTEGER")
            db.execSQL("ALTER TABLE standings ADD COLUMN draws INTEGER")
            db.execSQL("ALTER TABLE standings ADD COLUMN losses INTEGER")
            db.execSQL("ALTER TABLE standings ADD COLUMN goalsFor INTEGER")
            db.execSQL("ALTER TABLE standings ADD COLUMN goalsAgainst INTEGER")
        }
    }

    /**
     * 3 -> 4: remember whether a historical result was played on neutral ground.
     *
     * Additive only. Existing rows keep their data and are treated as normal home/away
     * matches, which is what the app assumed before this column existed.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE historical_matches ADD COLUMN neutralVenue INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * 2 -> 3: entity classification (club vs national team, competition type, gender,
     * neutral venue).
     *
     * Additive only: no table is dropped and no row is deleted. Existing rows keep
     * their data and receive a conservative classification which is then refined by
     * the next sync from the provider catalogue.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE competitions ADD COLUMN competitionClass TEXT NOT NULL DEFAULT 'CLUB_LEAGUE'")
            db.execSQL("ALTER TABLE competitions ADD COLUMN gender TEXT NOT NULL DEFAULT 'MEN'")
            db.execSQL("ALTER TABLE competitions ADD COLUMN classificationSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE teams ADD COLUMN kind TEXT NOT NULL DEFAULT 'CLUB'")
            db.execSQL("ALTER TABLE teams ADD COLUMN countryCode TEXT")
            db.execSQL("ALTER TABLE matches ADD COLUMN isNeutralVenue INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE predictions ADD COLUMN confidenceLevel TEXT NOT NULL DEFAULT 'INSUFFICIENT'")
            db.execSQL("ALTER TABLE predictions ADD COLUMN confidenceJson TEXT")

            // A feed that only publishes men's club leagues is classified as such.
            db.execSQL(
                "UPDATE competitions SET competitionClass = 'CLUB_LEAGUE', gender = 'MEN', " +
                    "classificationSource = 'PROVIDER_DEFAULT' " +
                    "WHERE providerId IN ('football-data-co-uk', 'open-matchday')",
            )
            // Competitions whose provider key is a known international tournament.
            db.execSQL(
                "UPDATE competitions SET competitionClass = 'INTERNATIONAL_TOURNAMENT', " +
                    "classificationSource = 'CATALOG' WHERE providerKey IN ('WC', 'EC')",
            )
            db.execSQL(
                "UPDATE competitions SET competitionClass = 'CLUB_KNOCKOUT', " +
                    "classificationSource = 'CATALOG' WHERE providerKey = 'CL'",
            )
            // Teams that appear in an international competition are national teams.
            db.execSQL(
                "UPDATE teams SET kind = 'NATIONAL' WHERE id IN (" +
                    "SELECT homeTeamId FROM matches m INNER JOIN competitions c ON c.id = m.competitionId " +
                    "WHERE c.competitionClass LIKE 'INTERNATIONAL%' OR c.competitionClass = 'WOMEN_INTERNATIONAL')",
            )
            db.execSQL(
                "UPDATE teams SET kind = 'NATIONAL' WHERE id IN (" +
                    "SELECT awayTeamId FROM matches m INNER JOIN competitions c ON c.id = m.competitionId " +
                    "WHERE c.competitionClass LIKE 'INTERNATIONAL%' OR c.competitionClass = 'WOMEN_INTERNATIONAL')",
            )
        }
    }

    /**
     * 6 -> 7: per-match expected goals on the history table.
     *
     * Additive and nullable, like 4 -> 5: competitions the xG mirror does not
     * cover leave both columns empty, and an empty column means "goals-only
     * evidence" to the rating fit — never a zero the source did not publish.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE historical_matches ADD COLUMN homeXg REAL")
            db.execSQL("ALTER TABLE historical_matches ADD COLUMN awayXg REAL")
        }
    }
}
