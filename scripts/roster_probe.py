"""Ask TheSportsDB two questions the phone raised, from a machine that can reach it.

  * every German third tier club is still on initials: is `strGender` simply missing on
    those records, which the app used to reject?
  * the Polish, Czech and Slovak divisions answer no name search at all — does a league
    table carry their badges, and how many rows does the free key give?

Run: python3 scripts/roster_probe.py   (posts the report when ISSUE_NUMBER is set)
"""
import json
import os
import re
import time
import unicodedata
import urllib.parse
import urllib.request

API = "https://www.thesportsdb.com/api/v1/json/123/"
ISSUE = os.environ.get("ISSUE_NUMBER")
_last = [0.0]


def get(path, **query):
    wait = 3.5 - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    url = API + path + ("?" + urllib.parse.urlencode(query) if query else "")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return json.loads(response.read().decode("utf-8", "replace") or "{}")
        except Exception as error:  # noqa: BLE001
            if attempt == 2:
                return {"_error": str(error)}
            time.sleep(2.0 * (attempt + 1))
    return {}


def fold(value):
    value = (value or "").replace("ł", "l").replace("Ł", "L")
    value = unicodedata.normalize("NFD", value)
    value = "".join(c for c in value if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9']+", " ", value.lower()).strip()


NAMES = ["1. FC Saarbrücken", "Alemannia Aachen", "FC Ingolstadt 04", "Lech Poznan",
         "Legia Warsaw", "Hradec Kralove", "Banska Bystrica", "Komarno", "Liberec",
         "Grodzisk M."]

MISSING = ["1. FC Saarbrücken", "Alemannia Aachen", "FC Ingolstadt 04", "Grodzisk M.",
           "Lechia Gdansk", "Odra Opole", "Hutnik Krakow", "Leczna", "Legia II",
           "Banska Bystrica", "Dun. Streda", "Komarno", "FK Pardubice", "Hradec Kralove",
           "Liberec", "GKS Katowice", "Lech Poznan", "Legia Warsaw"]


def report_names():
    lines = ["== what a name search returns =="]
    for name in NAMES:
        rows = (get("searchteams.php", t=name).get("teams") or [])[:3]
        if not rows:
            lines.append("%-22s -> nothing" % name)
            continue
        for row in rows:
            lines.append("%-22s -> %-28s gender=%-8s sport=%-8s badge=%s"
                         % (name, row.get("strTeam"), row.get("strGender") or "(none)",
                            row.get("strSport"), "yes" if row.get("strBadge") else "no"))
    return lines


def report_tables():
    lines = ["", "== league tables =="]
    leagues = {}
    for country in ("Poland", "Czechia", "Slovakia"):
        for league in get("search_all_leagues.php", c=country, s="Soccer").get("countries") or []:
            leagues[league.get("strLeague") or "?"] = league.get("idLeague")
            lines.append("%-12s %-48s id %s" % (country, league.get("strLeague"), league.get("idLeague")))
    for name, league_id in leagues.items():
        if league_id is None:
            continue
        rows = (get("lookuptable.php", l=league_id, s="2025-2026").get("table") or [])
        badges = [r for r in rows if (r.get("strBadge") or "").startswith("https://")]
        hits = [r.get("strTeam") for r in badges
                if any(fold(m) in fold(r.get("strTeam") or "") for m in MISSING)]
        lines.append("%-48s id %-5s: %2d rows, %2d badges | %s"
                     % (name, league_id, len(rows), len(badges),
                        ", ".join((r.get("strTeam") or "") + ("(g=" + (r.get("strGender") or "-") + ")")
                                  for r in badges) or "-"))
        if hits:
            lines.append("     ours: " + ", ".join(hits))
    return lines


def main():
    lines = ["### probe 2", ""]
    for part in (report_names, report_tables):
        try:
            lines += part()
        except Exception as error:  # noqa: BLE001
            lines.append("%s failed: %s" % (part.__name__, error))
    body = "\n".join(lines)
    print(body)
    if ISSUE and os.environ.get("GH_TOKEN"):
        data = json.dumps({"body": body[:65000]}).encode()
        request = urllib.request.Request(
            "https://api.github.com/repos/%s/issues/%s/comments" % (os.environ["REPO"], ISSUE),
            data=data,
            headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                     "Accept": "application/vnd.github+json"},
        )
        urllib.request.urlopen(request, timeout=30).read()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
