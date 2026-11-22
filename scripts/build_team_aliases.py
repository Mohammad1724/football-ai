#!/usr/bin/env python3
"""Rebuild the club-name alias table used by `TeamAliases.kt`.

Why this exists
---------------
The app reads two different publishers of the same matches:

* **open-matchday** (GitHub) publishes the current season and uses the naming of the
  live feed, e.g. ``FC Bayern München``, ``1. FC Köln``, ``Athletic Club``.
* **football-data.co.uk** publishes the archive of past seasons and uses its own
  short names, e.g. ``Bayern Munich``, ``FC Koln``, ``Athletic``.

A team is identified by a slug of its published name, so the same club gets two
different ids: the fixture keeps the current-season id, while every archived result
sits under the archive id. For most continental leagues that meant the model had no
history at all for the club (measured: 44 % of the current teams in the leagues this
app can archive for).

The fix is a table that maps an archived name to the name the live feed uses. This
script builds that table **from evidence only**: a pair is accepted when the same
date and the same final score appear in both publishers and that combination is
unique inside the league. Nothing is guessed, transliterated or matched fuzzily.
Ambiguous rows are dropped, never resolved by a heuristic.

Run it when the season's naming changes (promotions, renames):

    python3 scripts/build_team_aliases.py > /tmp/table.txt

then paste the emitted block into
``app/src/main/java/com/footballai/app/data/remote/TeamAliases.kt``.
The script is offline-friendly about failures: a league whose files cannot be
downloaded is reported and skipped instead of being written half-mapped.
"""

from __future__ import annotations

import json
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from datetime import datetime

OPEN_MATCHDAY_BASE = "https://raw.githubusercontent.com/oladeeayo/open-matchday/master/data"
FD_MAIN_BASE = "https://raw.githubusercontent.com/TuncErdoganlar/futbol-veri-aynasi/main/data/fd"
FD_EXTRA_BASE = "https://raw.githubusercontent.com/huhao930422-debug/football-odds-mirror/main/data"

# Leagues whose archive is a per-season football-data.co.uk file (mmz4281 contract).
MAIN_LEAGUES = {
    "E0": "ENG_1_premier-league",
    "E1": "ENG_2_championship",
    "E2": "ENG_3_league-one",
    "E3": "ENG_4_league-two",
    "SC0": "SCO_1_premiership",
    "SC1": "SCO_2_championship",
    "SC2": "SCO_3_league-one",
    "SC3": "SCO_4_league-two",
    "D1": "GER_1_bundesliga",
    "D2": "GER_2_2-bundesliga",
    "I1": "ITA_1_serie-a",
    "I2": "ITA_2_serie-b",
    "SP1": "ESP_1_la-liga",
    "SP2": "ESP_2_segunda-division",
    "F1": "FRA_1_ligue-1",
    "F2": "FRA_2_ligue-2",
    "N1": "NED_1_eredivisie",
    "B1": "BEL_1_pro-league",
    "P1": "POR_1_primeira-liga",
    "T1": "TUR_1_super-lig",
    "G1": "GRE_1_super-league",
}

# Leagues whose archive is the cumulative "new" file of football-data.co.uk.
EXTRA_LEAGUES = {
    "BRA1": ("BRA_1_serie-a", "brazil"),
    "USA1": ("USA_1_mls", "usa"),
    "DEN1": ("DEN_1_superliga", "denmark"),
    "FIN1": ("FIN_1_veikkausliiga", "finland"),
    "JPN1": ("JPN_1_j1-league", "japan"),
    "NOR1": ("NOR_1_eliteserien", "norway"),
    "SWE1": ("SWE_1_allsvenskan", "sweden"),
}

# Must stay identical to `ExpandedFootballCatalog.CROSS_SOURCE_LEAGUES`; those leagues
# key teams with a diacritic-free, punctuation-free normal form instead of a slug.
CROSS_SOURCE_LEAGUES = set(EXTRA_LEAGUES) | {"E3", "EC", "SC1", "SC2", "SC3"}


def fetch(url: str) -> str | None:
    request = urllib.request.Request(url, headers={"User-Agent": "FootballAI-alias-builder"})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.read().decode("utf-8-sig", "replace")
    except (urllib.error.URLError, TimeoutError) as error:  # pragma: no cover - network
        print(f"# skipped {url}: {error}", file=sys.stderr)
        return None


def split_line(line: str) -> list[str]:
    """RFC4180-ish splitter, same rules as `CsvHistoryParser.splitLine`."""
    out: list[str] = []
    buffer = ""
    quoted = False
    index = 0
    while index < len(line):
        char = line[index]
        if char == '"' and quoted and index + 1 < len(line) and line[index + 1] == '"':
            buffer += '"'
            index += 1
        elif char == '"':
            quoted = not quoted
        elif char == "," and not quoted:
            out.append(buffer)
            buffer = ""
        else:
            buffer += char
        index += 1
    out.append(buffer)
    return out


def slug(name: str) -> str:
    """Identical to `CsvHistoryParser.slug`."""
    value = name.lower().replace("&", "and")
    return re.sub(r"[^a-z0-9]+", "-", value).strip("-")


def normalize(name: str) -> str:
    """Identical to `ExpandedTeamIdentity.normalize` (NFD, marks dropped)."""
    decomposed = unicodedata.normalize("NFD", name)
    stripped = "".join(char for char in decomposed if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", "", stripped.lower())


def load_curated_aliases(
    path: str = "app/src/main/java/com/footballai/app/data/remote/ExpandedFootballCatalog.kt",
) -> dict[str, dict[str, str]]:
    """The hand-curated `ExpandedTeamIdentity` table, read from its single source.

    Those leagues already have a small alias list, and it is applied *while parsing*, so
    the id that reaches the database is already the curated one. Deriving new pairs on
    the un-curated key would produce a table that never matches, which is why the
    existing table is read here instead of being copied.
    """
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        print(f"# warning: {path} not readable; curated aliases ignored", file=sys.stderr)
        return {}
    table: dict[str, dict[str, str]] = {}
    for league, body in re.findall(r'"([A-Z0-9]+)" to aliases\((.*?)\n        \),', text, re.S):
        table[league] = {
            normalize(source): normalize(target)
            for source, target in re.findall(r'"([^"]+)" to "([^"]+)"', body)
        }
    return table


CURATED: dict[str, dict[str, str]] = {}


def team_key(league: str, name: str) -> str:
    if league in CROSS_SOURCE_LEAGUES:
        normalised = normalize(name)
        return CURATED.get(league, {}).get(normalised, normalised)
    return slug(name)


def _rows(csv: str) -> list[dict[str, str]]:
    lines = [line for line in csv.splitlines() if line.strip()]
    if not lines:
        return []
    header = [cell.strip().lstrip("\ufeff") for cell in split_line(lines[0])]
    index = {name: position for position, name in enumerate(header)}
    out: list[dict[str, str]] = []
    for line in lines[1:]:
        cells = split_line(line)
        if len(cells) < len(header):
            continue
        out.append({name: cells[index[name]].strip() for name in header if name in index})
    return out


def parse_open_matchday_results(csv: str) -> list[dict]:
    out = []
    for row in _rows(csv):
        try:
            out.append({
                "date": row["date"],
                "home": row["home"],
                "away": row["away"],
                "home_goals": int(row["ft_home"]),
                "away_goals": int(row["ft_away"]),
            })
        except (KeyError, ValueError):
            continue
    return out


def parse_open_matchday_fixtures(csv: str) -> list[dict]:
    out = []
    for row in _rows(csv):
        try:
            out.append({"home": row["home"], "away": row["away"]})
        except KeyError:
            continue
    return out


def parse_fd_main(csv: str) -> list[dict]:
    out = []
    for row in _rows(csv):
        try:
            out.append({
                "date": datetime.strptime(row["Date"], "%d/%m/%Y").strftime("%Y-%m-%d"),
                "home": row["HomeTeam"],
                "away": row["AwayTeam"],
                "home_goals": int(row["FTHG"]),
                "away_goals": int(row["FTAG"]),
            })
        except (KeyError, ValueError):
            continue
    return out


def parse_fd_extra(csv: str, season: int) -> list[dict]:
    accepted = {str(season), f"{season}/{season + 1}"}
    out = []
    for row in _rows(csv):
        if row.get("Season") not in accepted:
            continue
        try:
            out.append({
                "date": datetime.strptime(row["Date"], "%d/%m/%Y").strftime("%Y-%m-%d"),
                "home": row["Home"],
                "away": row["Away"],
                "home_goals": int(row["HG"]),
                "away_goals": int(row["AG"]),
            })
        except (KeyError, ValueError):
            continue
    return out


def _unique_by_scoreline(rows: list[dict]) -> dict[tuple, dict]:
    buckets: dict[tuple, list[dict]] = {}
    for row in rows:
        buckets.setdefault((row["date"], row["home_goals"], row["away_goals"]), []).append(row)
    return {key: value[0] for key, value in buckets.items() if len(value) == 1}


def derive_pairs(league: str, current: list[dict], archive: list[dict]) -> dict[str, str]:
    """Archived team key -> current team key, from exact date + score evidence."""
    current_by_score = _unique_by_scoreline(current)
    archive_by_score = _unique_by_scoreline(archive)
    pairs: dict[str, str] = {}
    conflicted: set[str] = set()
    for shared in set(current_by_score) & set(archive_by_score):
        fresh = current_by_score[shared]
        old = archive_by_score[shared]
        for source_name, target_name in ((old["home"], fresh["home"]), (old["away"], fresh["away"])):
            source = team_key(league, source_name)
            target = team_key(league, target_name)
            if source == target:
                continue
            if source in pairs and pairs[source] != target:
                conflicted.add(source)
            else:
                pairs[source] = target
    for source in conflicted:
        pairs.pop(source, None)
    return pairs


def safe_pairs(league: str, pairs: dict[str, str], live_keys: set[str]) -> dict[str, str]:
    """Drop pairs that could rewrite a key the live feed still uses.

    A history row from the current season is stored under the live id. If an archived
    name happened to collapse onto the same key, mapping it would detach this season's
    own results from the club, which is worse than leaving the old name unlinked. A
    target that no current team uses is equally useless, so both are removed.
    """
    return {
        source: target
        for source, target in pairs.items()
        if source not in live_keys and target in live_keys
    }


def season_code(start_year: int) -> str:
    return f"{start_year % 100:02d}{(start_year + 1) % 100:02d}"


def collect(season_start_year: int) -> dict[str, dict[str, str]]:
    code = season_code(season_start_year)
    table: dict[str, dict[str, str]] = {}
    measurement: list[tuple[str, int, int, int]] = []

    for league, directory in MAIN_LEAGUES.items():
        results_csv = fetch(f"{OPEN_MATCHDAY_BASE}/{directory}/results_{code}.csv")
        fixtures_csv = fetch(f"{OPEN_MATCHDAY_BASE}/{directory}/fixtures_{code}.csv")
        archive_csv = fetch(f"{FD_MAIN_BASE}/{code}/{league}.csv")
        if not results_csv or not archive_csv:
            print(f"# {league}: skipped (missing current-season file)", file=sys.stderr)
            continue
        current = parse_open_matchday_results(results_csv)
        fixtures = parse_open_matchday_fixtures(fixtures_csv or "")
        archive = parse_fd_main(archive_csv)
        live_keys = {team_key(league, row["home"]) for row in current + fixtures}
        live_keys |= {team_key(league, row["away"]) for row in current + fixtures}
        archived_keys = {team_key(league, row["home"]) for row in archive}
        archived_keys |= {team_key(league, row["away"]) for row in archive}
        pairs = safe_pairs(league, derive_pairs(league, current, archive), live_keys)
        table[league] = pairs
        mapped = {pairs.get(key, key) for key in archived_keys}
        measurement.append((league, len(live_keys), len(live_keys - archived_keys), len(live_keys - mapped)))

    for league, (directory, folder) in EXTRA_LEAGUES.items():
        results_csv = fetch(f"{OPEN_MATCHDAY_BASE}/{directory}/results_{code}.csv")
        fixtures_csv = fetch(f"{OPEN_MATCHDAY_BASE}/{directory}/fixtures_{code}.csv")
        archive_csv = fetch(f"{FD_EXTRA_BASE}/{folder}/all-seasons.csv")
        if not results_csv or not archive_csv:
            print(f"# {league}: skipped (missing current-season file)", file=sys.stderr)
            continue
        current = parse_open_matchday_results(results_csv)
        fixtures = parse_open_matchday_fixtures(fixtures_csv or "")
        archive = parse_fd_extra(archive_csv, season_start_year)
        live_keys = {team_key(league, row["home"]) for row in current + fixtures}
        live_keys |= {team_key(league, row["away"]) for row in current + fixtures}
        archived_keys = {team_key(league, row["home"]) for row in archive}
        archived_keys |= {team_key(league, row["away"]) for row in archive}
        pairs = safe_pairs(league, derive_pairs(league, current, archive), live_keys)
        table[league] = pairs
        mapped = {pairs.get(key, key) for key in archived_keys}
        measurement.append((league, len(live_keys), len(live_keys - archived_keys), len(live_keys - mapped)))

    print("# league  live-teams  unmatched-before  unmatched-after", file=sys.stderr)
    for league, live, before, after in measurement:
        print(f"# {league:6} {live:>10} {before:>17} {after:>16}", file=sys.stderr)
    return table


def render(table: dict[str, dict[str, str]]) -> str:
    lines = []
    for league in sorted(table):
        pairs = table[league]
        if not pairs:
            continue
        for source in sorted(pairs):
            lines.append(f"{league}|{source}={pairs[source]}")
    return "\n".join(lines)


def main() -> None:
    global CURATED
    CURATED = load_curated_aliases()
    season_start_year = (
        int(sys.argv[1])
        if len(sys.argv) > 1
        else (datetime.now().year if datetime.now().month >= 7 else datetime.now().year - 1)
    )
    table = collect(season_start_year)
    print(render(table))
    print(
        json.dumps({"leagues": len(table), "pairs": sum(len(value) for value in table.values())}),
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
