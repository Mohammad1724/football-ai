"""Exercise the actual Room crest queue SQL with SQLite. Run: python3 scripts/check_crest_queue.py"""
import re
import sqlite3
import unittest
from pathlib import Path

DAO = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/footballai/app/data/local/dao/Daos.kt').read_text()
QUEUE = re.search(r'@Query\("""(\s*SELECT teams\.\* FROM teams.*?)"""\)\s*suspend fun crestlessInFixtures', DAO, re.S).group(1)
DAY_LEAGUES = re.search(r'@Query\("""(\s*SELECT DISTINCT c\.\* FROM competitions c.*?)"""\)\s*suspend fun crestCompetitionsForDay', DAO, re.S).group(1)
DAY_TEAMS = re.search(r'@Query\("""(\s*SELECT \* FROM teams WHERE providerId = \'football-data-co-uk\'.*?)"""\)\s*suspend fun crestlessTeamsForCompetitionDay', DAO, re.S).group(1)
UPDATE = re.search(r'@Query\("(UPDATE teams SET crestUrl = :url.*?)"\)\s*suspend fun setCrestIfMissing', DAO).group(1)


class CrestQueueTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
            CREATE TABLE teams(id TEXT PRIMARY KEY, providerId TEXT, providerKey TEXT,
                name TEXT, crestUrl TEXT, kind TEXT);
            CREATE TABLE matches(homeTeamId TEXT, awayTeamId TEXT, kickoffEpochMs INTEGER, competitionId TEXT);
            CREATE TABLE competitions(id TEXT PRIMARY KEY, providerId TEXT, gender TEXT, name TEXT);
            CREATE TABLE sync_meta(key TEXT PRIMARY KEY, updatedAt INTEGER);
        ''')

    def team(self, key, provider='csv', provider_key=None, kind='CLUB', crest=None):
        self.db.execute('INSERT INTO teams VALUES (?,?,?,?,?,?)',
                        (key, provider, provider_key or key, key, crest, kind))

    def match(self, home, away, kickoff):
        self.db.execute('INSERT INTO matches VALUES (?,?,?,?)', (home, away, kickoff, 'league'))

    def queue(self, now=1000, retry_before=500, limit=200, start=None, end=None):
        return [row[0] for row in self.db.execute(QUEUE, dict(now=now, retryBefore=retry_before, limit=limit, fromEpochMs=start, toEpochMs=end))]

    def test_near_matches_first_and_no_duplicate_teams(self):
        for name in ('old', 'soon', 'other', 'unused'):
            self.team(name)
        self.match('old', 'other', 100)
        self.match('soon', 'other', 1010)
        self.assertEqual(self.queue(), ['other', 'soon', 'old'])
        self.assertEqual(self.queue(limit=1), ['other'])

    def test_selected_day_excludes_other_days(self):
        for name in ('yesterday', 'today', 'tomorrow', 'shared'):
            self.team(name)
        self.match('yesterday', 'shared', 900)
        self.match('today', 'shared', 1000)
        self.match('tomorrow', 'shared', 1100)
        self.assertEqual(self.queue(start=950, end=1050), ['shared', 'today'])

    def test_failed_searches_do_not_starve_next_batch_and_retry_later(self):
        for name in ('first', 'second', 'third'):
            self.team(name)
        self.match('first', 'second', 1000)
        self.match('third', 'first', 1010)
        self.db.executemany('INSERT INTO sync_meta VALUES (?, ?)',
                            [('crest-attempt-v4:first', 900), ('crest-attempt-v4:second', 900)])
        self.assertEqual(self.queue(limit=2), ['third'])
        self.assertEqual(self.queue(retry_before=900), ['first', 'second', 'third'])

    def test_only_clubs_needing_lookup_are_queued(self):
        self.team('national', kind='NATIONAL')
        self.team('espn', provider='espn-open', provider_key='132')
        self.team('espn-slug', provider='espn-open', provider_key='not-numeric')
        self.team('has-logo', crest='https://example.com/logo.png')
        self.match('national', 'espn', 1000)
        self.match('espn-slug', 'has-logo', 1000)
        self.assertEqual(self.queue(), ['espn-slug'])

    def test_bulk_repair_only_sees_mens_csv_day_teams_even_after_failed_lookup(self):
        self.db.executemany('INSERT INTO competitions VALUES (?,?,?,?)', [
            ('league', 'football-data-co-uk', 'MEN', 'A'),
            ('women', 'football-data-co-uk', 'WOMEN', 'B'),
            ('espn', 'espn-open', 'MEN', 'C'),
        ])
        for name in ('one', 'two', 'old', 'female'):
            self.team(name, provider='football-data-co-uk')
        self.match('one', 'two', 1000)
        self.match('old', 'one', 500)
        self.db.execute("INSERT INTO matches VALUES ('female','one',1000,'women')")
        self.db.execute("INSERT INTO matches VALUES ('female','one',1000,'espn')")
        self.db.execute("INSERT INTO sync_meta VALUES ('crest-attempt-v4:one', 999)")
        leagues = self.db.execute(DAY_LEAGUES, dict(fromEpochMs=900, toEpochMs=1100)).fetchall()
        self.assertEqual([row[0] for row in leagues], ['league'])
        teams = self.db.execute(DAY_TEAMS, dict(competitionId='league', fromEpochMs=900, toEpochMs=1100)).fetchall()
        self.assertEqual(set(row[0] for row in teams), {'one', 'two'})

    def test_repair_never_overwrites_newer_sync(self):
        self.team('club')
        self.db.execute("UPDATE teams SET crestUrl = 'new' WHERE id = 'club'")
        changed = self.db.execute(UPDATE, dict(id='club', url='old')).rowcount
        self.assertEqual(changed, 0)
        self.assertEqual(self.db.execute('SELECT crestUrl FROM teams').fetchone()[0], 'new')


if __name__ == '__main__':
    unittest.main()
