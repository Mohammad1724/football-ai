"""Ask ESPN what it actually publishes, from a machine that can reach it.

This workspace cannot open espn.com, so the club-name tables the app relies on are
maintained from the output of this script: it runs on a GitHub runner, prints a compact
report, and (when `ISSUE_NUMBER` is set) posts the same report as an issue comment.

Two questions it answers, both of which decide whether a badge can be found at all:
  * for a league slug, which club names does ESPN publish, and does each one carry a logo?
  * for a name the feed publishes, what does ESPN's search answer, and under what name?

Run: python3 scripts/espn_probe.py
"""
import json
import os
import sys
import urllib.parse
import urllib.request

BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
SEARCH = "https://site.api.espn.com/apis/common/v3/search"

# Leagues the app already maps, plus slugs worth verifying before they are mapped.
SLUGS = [
    "eng.1", "eng.2", "eng.3", "eng.4", "ger.1", "ger.2", "esp.1", "esp.2", "ita.1",
    "ita.2", "fra.1", "fra.2", "ned.1", "ned.2", "por.1", "bel.1", "tur.1", "gre.1",
    "sui.1", "aut.1", "den.1", "swe.1", "nor.1", "sco.1", "sco.2", "rus.1", "ukr.1",
    "pol.1", "cze.1", "cro.1", "rou.1", "bra.1", "arg.1", "mex.1", "jpn.1", "chn.1",
    "aus.1", "usa.1",
    # Candidates: a 404 here is the answer, and it keeps a guessed slug out of the app.
    "sco.3", "sco.4", "fin.1", "wal.1", "wal.2", "pol.2", "pol.3", "svk.1", "svn.1",
    "cyp.1", "kaz.1", "irl.1", "irl.2", "eng.5", "eng.6", "ger.3", "esp.3", "ita.3",
    "fra.3", "bel.2", "por.2", "gre.2", "aut.2", "sui.2", "den.2", "swe.2", "nor.2",
    "jpn.2", "kor.1", "bra.2", "arg.2", "mex.2",
]

# Names as the open feed publishes them: the clubs that were still drawn as initials.
SEARCHES = [
    "Ajax", "PSV", "Feyenoord", "Excelsior Rotterdam", "FC Groningen", "sc Heerenveen",
    "N.E.C. Nijmegen", "SC Cambuur", "Telstar", "PEC Zwolle", "Slavia Prague", "Plzen",
    "Sigma Olomouc", "Mlada Boleslav", "Zlin", "Teplice", "Jablonec", "Ostrava",
    "Malmo FF", "Vasteras SK", "Mjallby", "Varberg", "Ostersund", "Samsunspor",
    "Galatasaray", "Fenerbahce", "Kocaelispor", "Amedsport", "Gaziantep",
    "Livyi Bereg", "Krylya Sovetov", "Bukovyna", "Ch. Odesa", "Dyn. Kyiv",
    "1. FC Saarbrücken", "Alemannia Aachen", "FC Ingolstadt 04", "Elversberg",
    "Billericay", "Braintree", "Chesham", "AFC Hornchurch", "Boston United", "Eastleigh",
    "Bielsko-Biala", "Chojniczanka", "Hutnik Kraków", "Ammanford", "Barry",
    "Haverfordwest", "Artis Brno", "Bohemians", "Grazer AK", "Hartberg", "LASK",
    "A. Lustenau", "Tirol",
]


def fetch(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": "football-ai crest probe"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def describe(team: dict) -> str:
    logos = team.get("logos") or []
    has_default = any(
        "default" in [str(rel).lower() for rel in (logo.get("rel") or [])] for logo in logos
    )
    mark = "logo" if has_default else ("logos-only" if logos else "no-logo")
    return f"{team.get('name')}|{team.get('id')}|{mark}"


def roster(slug: str) -> str:
    try:
        document = fetch(f"{BASE}/{slug}/teams?limit=100")
    except Exception as error:  # noqa: BLE001 - the HTTP status is the finding
        return f"{slug}: unavailable ({error})"
    names = []
    for sport in document.get("sports") or []:
        for league in sport.get("leagues") or []:
            if league.get("slug") != slug:
                continue
            for row in league.get("teams") or []:
                team = row.get("team") or {}
                if team.get("name"):
                    names.append(describe(team))
    if not names:
        return f"{slug}: empty document"
    return f"{slug} ({len(names)}): " + ", ".join(names)


def search(query: str) -> str:
    url = f"{SEARCH}?query={urllib.parse.quote(query)}&limit=6&type=team"
    try:
        document = fetch(url)
    except Exception as error:  # noqa: BLE001
        return f"{query}: unavailable ({error})"
    answers = []
    for item in (document.get("items") or [])[:6]:
        logos = item.get("logos") or []
        answers.append(
            f"{item.get('name')}({item.get('id')},{item.get('sport')},"
            f"{'logo' if logos else 'no-logo'})"
        )
    return f"{query} -> " + (", ".join(answers) if answers else "nothing")


def main() -> int:
    lines = ["### ESPN rosters", ""]
    lines += roster(slug) for slug in SLUGS
    lines += ["", "### ESPN search", ""]
    lines += search(name) for name in SEARCHES
    report = "\n".join(lines)
    print(report)

    issue = os.environ.get("ISSUE_NUMBER")
    token = os.environ.get("GH_TOKEN")
    repo = os.environ.get("REPO")
    if not (issue and token and repo):
        return 0
    # GitHub truncates long comments; send it in bounded chunks.
    chunks, current = [], ""
    for line in report.splitlines():
        if len(current) + len(line) + 1 > 55000:
            chunks.append(current)
            current = ""
        current += line + "\n"
    chunks.append(current)
    for index, chunk in enumerate(chunks, start=1):
        body = json.dumps({"body": f"probe part {index}/{len(chunks)}\n```\n{chunk}\n```"})
        request = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/issues/{issue}/comments",
            data=body.encode(),
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "User-Agent": "football-ai crest probe",
            },
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            print(f"posted part {index}: {response.status}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
