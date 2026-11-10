"""Exercise the migration SQL and DAO queries in SQLite, without an Android SDK.
Run: python3 scripts/check_review_sql.py
This supplements (does not replace) Room's on-device migration validation.
"""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / 'app/src/main/java/com/footballai/app/data/local'


def database():
    db = sqlite3.connect(':memory:')
    db.executescript('''
    PRAGMA foreign_keys=ON;
    CREATE TABLE matches(id TEXT PRIMARY KEY, providerId TEXT, kickoffEpochMs INTEGER);
    CREATE TABLE predictions(id TEXT PRIMARY KEY);
    CREATE TABLE historical_matches(
      id TEXT PRIMARY KEY, source TEXT, competitionId TEXT, competitionName TEXT,
      homeTeamId TEXT, awayTeamId TEXT, homeTeamName TEXT, awayTeamName TEXT,
      homeGoals INTEGER, awayGoals INTEGER, kickoffEpochMs INTEGER,
      homeOdds REAL, drawOdds REAL, awayOdds REAL, importedAt INTEGER);
    CREATE UNIQUE INDEX historical_unique ON historical_matches(source, competitionId, homeTeamId, awayTeamId, kickoffEpochMs);
    CREATE TABLE prediction_markets(predictionId TEXT NOT NULL, marketType TEXT NOT NULL,
      selectionKey TEXT NOT NULL, line REAL, probability REAL NOT NULL, sortOrder INTEGER NOT NULL,
      PRIMARY KEY(predictionId, marketType, selectionKey),
      FOREIGN KEY(predictionId) REFERENCES predictions(id) ON DELETE CASCADE);
    CREATE INDEX index_prediction_markets_predictionId ON prediction_markets(predictionId);
    ''')
    return db


def migrate(db):
    code = (LOCAL / 'HistoryMigration.kt').read_text()
    statements = re.findall(r'db\.execSQL\(("""[\s\S]*?"""\.trimIndent\(\)|"[^"\n]*")\)', code)
    assert len(statements) == 12, f'Migration changed: review extraction ({len(statements)} statements)'
    with db:
        for statement in statements:
            sql = statement[3:-len('""".trimIndent()')] if statement.startswith('"""') else statement[1:-1]
            db.execute(sql)


def history(db, id, source, league, home, away, imported, goals=1):
    db.execute('INSERT INTO historical_matches VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
               (id, source, league, 'League', home, away, 'Home', 'Away', goals, 0, 1234, 2., 3., 4., imported))


class MigrationTests(unittest.TestCase):
    def test_csv_ids_match_fixtures_and_newer_duplicate_wins(self):
        db = database()
        source = 'football-data-co-uk'
        history(db, 'old', source, 'csv:E0', 'arsenal', 'chelsea', 1)
        history(db, 'new', source, source + ':league:E0', source + ':team:arsenal', source + ':team:chelsea', 2, 3)
        migrate(db)
        rows = db.execute('SELECT source,competitionId,homeTeamId,homeGoals FROM historical_matches').fetchall()
        self.assertEqual(rows, [(source, source + ':league:E0', source + ':team:arsenal', 3)])

    def test_unverifiable_api_history_is_retained_but_quarantined(self):
        db = database()
        history(db, 'old', 'api-football', 'api-football:league:39', '42', '49', 1)
        db.execute("INSERT INTO matches VALUES ('m', 'api-football', 1234)")
        migrate(db)
        self.assertEqual(db.execute('SELECT source FROM historical_matches').fetchone()[0], 'legacy-unverified:api-football')
        self.assertEqual(db.execute('SELECT scoreScope FROM matches').fetchone()[0], 'LEGACY_UNVERIFIED')

    def test_all_goal_lines_survive_and_cascade_delete_still_works(self):
        db = database()
        db.execute("INSERT INTO predictions VALUES ('p')")
        db.execute("INSERT INTO prediction_markets VALUES ('p','OVER_UNDER','OVER',3.5,0.2,3)")
        migrate(db)
        for i, line in enumerate([0.5, 1.5, 2.5]):
            db.execute("INSERT INTO prediction_markets VALUES ('p','OVER_UNDER','OVER',?,?,?)", (line, .5, i))
        self.assertEqual(db.execute('SELECT COUNT(*) FROM prediction_markets').fetchone()[0], 4)
        self.assertFalse(db.execute('PRAGMA foreign_key_check').fetchall())
        db.execute("DELETE FROM predictions WHERE id='p'")
        self.assertEqual(db.execute('SELECT COUNT(*) FROM prediction_markets').fetchone()[0], 0)

    def test_stats_use_one_valid_prematch_per_match_and_current_version(self):
        db = sqlite3.connect(':memory:')
        db.executescript('''
        CREATE TABLE matches(id TEXT PRIMARY KEY, kickoffEpochMs INTEGER, scoreScope TEXT);
        CREATE TABLE predictions(id TEXT PRIMARY KEY, matchId TEXT, resolvedAtEpochMs INTEGER,
          kind TEXT, modelVersion TEXT, createdAtEpochMs INTEGER);
        INSERT INTO matches VALUES ('m',1000,'REGULATION'),('n',1000,'ADMINISTRATIVE');
        INSERT INTO predictions VALUES
          ('first','m',2000,'PRE_MATCH','v2',100),
          ('repeat','m',2000,'PRE_MATCH','v2',200),
          ('late','m',2000,'PRE_MATCH','v2',1100),
          ('live','m',2000,'LIVE','v2',1200),
          ('old','m',2000,'PRE_MATCH','v1',50),
          ('admin','n',2000,'PRE_MATCH','v2',100);
        ''')
        code = (LOCAL / 'dao/Daos.kt').read_text()
        query = re.search(r'@Query\("(SELECT p\.\*.*?)"\)', code).group(1)
        self.assertEqual([r[0] for r in db.execute(query, {'version': 'v2'})], ['first'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
