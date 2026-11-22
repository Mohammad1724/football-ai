#!/usr/bin/env python3
"""Reproduce the research backtest. Needs JDK 17, kotlinc 1.9.24 and network access.

Data is downloaded to a temporary directory, not the repository or application. This is
not an evaluation of predictions on the user's device; read docs/forecast-audit-fa.md.
"""
import csv
import datetime as dt
import io
import json
import math
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SHA = 'e6744429ee395bc86f247348c6184bb08d4eb361'
SEASONS = ('2021-22', '2022-23', '2023-24', '2024-25', '2025-26')
LEAGUES = ('en.1', 'de.1', 'es.1', 'it.1', 'fr.1')


def prepare(archive, output):
    with tarfile.open(archive, 'r:gz') as tar, output.open('w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f, delimiter='\t', lineterminator='\n')
        for season in SEASONS:
            for league in LEAGUES:
                name = f'football.json-{SHA}/{season}/{league}.json'
                matches = json.load(tar.extractfile(name))['matches']
                for i, row in enumerate(matches):
                    # Explicit ft only. No guesses for incomplete or differently encoded scores.
                    score = row.get('score')
                    ft = score.get('ft') if isinstance(score, dict) else None
                    if not (isinstance(ft, list) and len(ft) == 2 and all(type(x) is int and 0 <= x <= 15 for x in ft)):
                        continue
                    try:
                        date = dt.datetime.fromisoformat(row['date']).replace(tzinfo=dt.timezone.utc)
                        home, away = row['team1'], row['team2']
                    except (KeyError, ValueError):
                        continue
                    if home == away or any('\t' in x or '\n' in x for x in (home, away)):
                        continue
                    writer.writerow([f'{season}/{league}/{i}', league, home, away, *ft, int(date.timestamp() * 1000)])


def metrics(rows, fields, temperature=1.0):
    total = len(rows)
    if not total:
        return 'no samples'
    hits = 0
    brier = loss = mean_top = 0.0
    for row in rows:
        p = [float(row[k]) ** (1 / temperature) for k in fields]
        p = [v / sum(p) for v in p]
        y = ('HOME_WIN', 'DRAW', 'AWAY_WIN').index(row['actual'])
        hits += max(range(3), key=lambda i: p[i]) == y
        brier += sum((v - (i == y)) ** 2 for i, v in enumerate(p))
        loss -= math.log(max(1e-9, p[y]))
        mean_top += max(p)
    return f'n={total} accuracy={hits/total:.4f} brier={brier/total:.4f} logloss={loss/total:.4f} mean_top={mean_top/total:.4f}'


def main():
    with tempfile.TemporaryDirectory(prefix='football-forecast-audit-') as tmp:
        tmp = Path(tmp)
        archive, records, jar = tmp / 'data.tar.gz', tmp / 'matches.tsv', tmp / 'audit.jar'
        with urllib.request.urlopen(f'https://codeload.github.com/openfootball/football.json/tar.gz/{SHA}', timeout=60) as response:
            archive.write_bytes(response.read())
        prepare(archive, records)
        kotlin = os.environ.get('KOTLINC', 'kotlinc')
        sources = sorted(str(p) for p in (ROOT / 'engine/src/main/kotlin').rglob('*.kt'))
        subprocess.run([kotlin, *sources, str(ROOT / 'scripts/forecast-audit/ForecastAudit.kt'), '-include-runtime', '-d', str(jar)], check=True)
        run = subprocess.run([os.environ.get('JAVA', 'java'), '-Xmx2g', '-jar', str(jar), str(records)], check=True, capture_output=True, text=True)
        print(run.stderr.strip())
        rows = list(csv.DictReader(io.StringIO(run.stdout), delimiter='\t'))
        for season in ('2024-25', '2025-26'):
            subset = [r for r in rows if r['id'].startswith(season + '/')]
            print(f'\n{season}:')
            for league in ('all', *LEAGUES):
                cohort = subset if league == 'all' else [r for r in subset if r['league'] == league]
                print(f'  {league} model:      {metrics(cohort, ("home", "draw", "away"))}')
                print(f'  {league} no context: {metrics(cohort, ("contextHome", "contextDraw", "contextAway"))}')
                if cohort:
                    print(f'  {league} always home accuracy={sum(r["actual"] == "HOME_WIN" for r in cohort)/len(cohort):.4f}')
            for temperature in (1.0, 1.1, 1.2, 1.3, 1.5):
                print(f'  hypothetical temperature {temperature}: {metrics(subset, ("home", "draw", "away"), temperature)}')
            for lo, hi in ((0.33, 0.4), (0.4, 0.5), (0.5, 0.6), (0.6, 0.7), (0.7, 0.8), (0.8, 0.9), (0.9, 1.01)):
                cohort = [r for r in subset if lo <= max(float(r[k]) for k in ('home', 'draw', 'away')) < hi]
                print(f'  top probability [{lo}, {hi}): {metrics(cohort, ("home", "draw", "away"))}')


if __name__ == '__main__':
    main()
