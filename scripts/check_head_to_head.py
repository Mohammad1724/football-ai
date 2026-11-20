#!/usr/bin/env python3
"""Exercise the actual Room head-to-head SELECT against an in-memory SQLite database."""
from pathlib import Path
import re
import sqlite3

source = Path('app/src/main/java/com/footballai/app/data/local/dao/Daos.kt').read_text()
pattern = r'@Query\(\s*"""((?:(?!""").)*)"""\s*\)\s*suspend fun headToHead\('
match = re.search(pattern, source, re.S)
assert match, 'Head-to-head Room query was not found'
query = match.group(1)
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE matches (id TEXT, homeTeamId TEXT, awayTeamId TEXT, status TEXT, homeGoals INTEGER, awayGoals INTEGER, kickoffEpochMs INTEGER)')
rows = [
    ('opened', 'a', 'b', 'SCHEDULED', None, None, 1000),
    ('future', 'b', 'a', 'SCHEDULED', None, None, 1100),
    ('past-scheduled', 'a', 'b', 'SCHEDULED', None, None, 500),
    ('past-missing', 'a', 'b', 'FINISHED', None, 1, 600),
    ('past-other', 'a', 'c', 'FINISHED', 2, 0, 700),
    ('past-one', 'a', 'b', 'FINISHED', 1, 0, 800),
    ('past-two', 'b', 'a', 'FINISHED', 2, 3, 900),
    ('after', 'a', 'b', 'FINISHED', 1, 0, 1200),
]
db.executemany('INSERT INTO matches VALUES (?,?,?,?,?,?,?)', rows)
args = dict(matchId='opened', teamA='a', teamB='b', beforeEpochMs=1000, limit=6)
assert [r[0] for r in db.execute(query, args)] == ['past-two', 'past-one']
args['beforeEpochMs'] = 850
assert [r[0] for r in db.execute(query, args)] == ['past-one']
args.update(beforeEpochMs=1000, limit=1)
assert [r[0] for r in db.execute(query, args)] == ['past-two']
print('Head-to-head SQLite regression tests passed')
