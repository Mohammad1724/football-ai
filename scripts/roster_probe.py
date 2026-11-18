"""Ask TheSportsDB what it actually holds for the lanes that are still on initials.

The development workspace cannot open thesportsdb.com, so every question about that source
is answered by running this script on a machine that can — a throwaway workflow job — and
reading the report back from an issue comment.

Two questions, both of which decide whether a badge is reachable at all:

  * per name — the app asks `searchteams.php?t=<name>` with the feed's spelling. Does the
    source answer at all, and does an ASCII spelling of the same name do better? Polish and
    Czech clubs carry diacritics the feed may not match.
  * per league — `search_all_teams.php?l=<league>` returns a whole roster with badges in one
    request. If the lower divisions exist as leagues here, one request per lane replaces
    dozens of failing name searches.

Run: python3 scripts/roster_probe.py
"""
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

KEY = "123"
API = "https://www.thesportsdb.com/api/v1/json/%s/" % KEY
ISSUE = os.environ.get("ISSUE_NUMBER")

_last = [0.0]


def get(path, **query):
    """One request, spaced below the free tier's 30/minute."""
    wait = 2.1 - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    url = API + path + ("?" + urllib.parse.urlencode(query) if query else "")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return json.loads(response.read().decode("utf-8", "replace") or "{}")
        except Exception as error:  # noqa: BLE001 - the report is the point
            if attempt == 2:
                return {"_error": str(error)}
            time.sleep(1.5 * (attempt + 1))
    return {}


def fold(value):
    value = (value or "").replace("ł", "l").replace("Ł", "L").replace("ø", "o").replace("Ø", "O")
    value = unicodedata.normalize("NFD", value)
    value = "".join(c for c in value if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9']+", " ", value.lower()).strip()


LEGAL = {
    "fc", "cf", "afc", "sc", "ac", "as", "ss", "ssc", "cd", "ud", "sd", "rc", "sv", "tsv",
    "vfl", "vfb", "fsv", "ks", "mks", "gks", "bsp", "club", "football", "futbol", "fussball",
    "sporting", "sport", "sports", "calcio", "atletico", "athletic", "athletik", "cs", "ca",
    "us", "ssd", "asd", "pol", "ts", "kts", "rks", "mkp", "csk", "fk", "sk", "mfk", "sp",
}
MARKERS = {
    "ii", "iii", "b", "jong", "young", "u17", "u18", "u19", "u20", "u21", "u23", "women",
    "ladies", "reserves", "reserve", "youth", "academy",
}


def core(value):
    words = [w for w in fold(value).split() if w not in LEGAL and not w.isdigit()]
    return tuple(w for w in words if w)


def compatible(published, wanted):
    """The app's rule, in miniature: word by word, never a substring."""
    p, w = core(published), core(wanted)
    if not p or not w:
        return False
    if set(w) - set(p):
        return False
    pm = {x for x in fold(published).split() if x in MARKERS}
    wm = {x for x in fold(wanted).split() if x in MARKERS}
    if not wm.issuperset(pm):
        return False
    if len(w) == 1:
        return fold(published).split()[0] == w[0] or len(p) == 1
    return True


# Clubs the app is still drawing as initials, as the feed spells them.
MISSING = {
    "D3": ["1. FC Saarbrücken", "Alemannia Aachen", "FC Ingolstadt 04", "SV Waldhof Mannheim",
           "Rot-Weiss Essen", "VfL Osnabrück", "MSV Duisburg", "TSV 1860 München"],
    "EC": ["AFC Hornchurch", "Boston United", "Eastleigh", "Boreham Wood", "Torquay United"],
    "ENG6S": ["Braintree Town", "Dag & Red", "Dorking Wanderers", "St Albans City", "Hemel Hempstead"],
    "POL2": ["Grodzisk M.", "Lechia Gdansk", "Odra Opole", "Znicz Pruszkow", "Chrobry Glogow"],
    "POL3": ["Bielsko-Biała", "Hutnik Krakow", "Kleczew", "Stal Stalowa Wola", "Rekord Bielsko"],
    "CZE1": ["Brno", "FK Pardubice", "Hradec Kralove", "Bohemians 1905", "Slovacko"],
}

# Countries to ask for a league list.
COUNTRIES = ["Germany", "England", "Poland", "Czech Republic", "Czechia"]

def report_name():
    lines = ["== per-name searches =="]
    for lane, names in MISSING.items():
        for name in names:
            raw = get("searchteams.php", t=name).get("teams") or []
            raw_soccer = [t for t in raw if (t.get("strSport") or "").lower() == "soccer"]
            folded_name = " ".join(w for w in fold(name).split())
            ascii_row = None
            if fold(name) != name.strip().lower():
                alt = get("searchteams.php", t=folded_name).get("teams") or []
                ascii_row = [t for t in alt if (t.get("strSport") or "").lower() == "soccer"]
            def summarise(rows):
                if not rows:
                    return "none"
                with_badge = [t for t in rows if (t.get("strBadge") or "").startswith("https://")]
                ok = [t for t in with_badge if compatible(t.get("strTeam") or "", name)]
                return "%d/%d badge, match=%s" % (
                    len(with_badge), len(rows),
                    (ok[0].get("strTeam") + " [" + ok[0].get("strBadge", "") + "]") if len(ok) == 1 else ("AMBIGUOUS" if ok else "no"),
                )
            tail = ""
            if ascii_row is not None:
                tail = " · ascii '%s': %s" % (folded_name, summarise(ascii_row))
            lines.append("%-6s %-26s feed: %s%s" % (lane, name, summarise(raw_soccer), tail))
    return lines


def report_leagues():
    """Can a whole league be read in one request, and how many rows come back?"""
    lines = ["", "== league documents =="]
    leagues = {}
    for country in COUNTRIES:
        for endpoint in ("search_all_leagues.php", "lookup_all_leagues.php"):
            payload = get(endpoint, c=country, s="Soccer")
            found = payload.get("countries") or payload.get("leagues") or []
            for league in found:
                name = league.get("strLeague") or "?"
                leagues[fold(name)] = (name, league.get("idLeague"), country, endpoint)
    for key, (name, league_id, country, endpoint) in sorted(leagues.items()):
        lines.append("%-14s %-55s id %-6s (%s)" % (country, name, league_id, endpoint))
    wanted_all = [(lane, name) for lane, names in MISSING.items() for name in names]
    candidates = [
        ("POL2", "Polish I liga", "4661"), ("POL3", "Polish II liga", "5709"),
        ("---", "Polish Ekstraklasa", "4422"), ("ENG6S", "English Isthmian League Premier Division", "4647"),
        ("---", "English Isthmian League North Division", "5225"),
        ("---", "Czech First League", "4351"), ("CZE1", "Czech National Football League", "4662"),
        ("---", "Czech Moravian-Silesian Football League", "5877"),
    ]
    for lane, name, league_id in candidates:
        for endpoint, params in (
            ("lookup_all_teams.php", {"id": league_id}),
            ("search_all_teams.php", {"l": name}),
        ):
            payload = get(endpoint, **params)
            rows = payload.get("teams") or []
            badges = [t for t in rows if (t.get("strBadge") or "").startswith("https://")]
            hits = []
            for wanted_lane, wanted in wanted_all:
                ok = [t for t in badges if compatible(t.get("strTeam") or "", wanted)]
                if len(ok) == 1:
                    hits.append("%s[%s]->%s" % (wanted, wanted_lane, ok[0].get("strTeam")))
                elif len(ok) > 1:
                    hits.append("%s[%s]->AMBIGUOUS" % (wanted, wanted_lane))
            lines.append("%-38s %-28s id %-5s: %2d rows, %2d badges | %s"
                         % (name, endpoint.split("_")[0], league_id, len(rows), len(badges),
                            "; ".join(hits) or "-"))
    return lines


def main():
    lines = ["### TheSportsDB probe", ""]
    for path in (report_name, report_leagues):  # each takes no argument
        try:
            lines += path()
        except Exception as error:  # noqa: BLE001
            lines.append("%s failed: %s" % (path.__name__, error))
    body = "\n".join(lines)
    print(body)
    if ISSUE:
        token = os.environ.get("GH_TOKEN")
        repo = os.environ.get("REPO")
        if token and repo:
            data = json.dumps({"body": body[:65000]}).encode()
            request = urllib.request.Request(
                "https://api.github.com/repos/%s/issues/%s/comments" % (repo, ISSUE),
                data=data,
                headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"},
            )
            urllib.request.urlopen(request, timeout=30).read()
    return 0


if __name__ == "__main__":
    sys.exit(main())
