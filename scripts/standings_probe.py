"""Ask ESPN which columns its league table actually carries (workspace cannot reach it).

Run: python3 scripts/standings_probe.py   (posts to ISSUE_NUMBER when set)
"""
import json
import os
import urllib.request

BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
ISSUE = os.environ.get("ISSUE_NUMBER")
LEAGUES = ["eng.1", "esp.1", "ita.1", "irn.1"]


def fetch(slug):
    try:
        with urllib.request.urlopen("%s/%s/standings" % (BASE, slug), timeout=30) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except Exception as error:  # noqa: BLE001
        return {"_error": str(error)}


def main():
    lines = ["### standings probe"]
    for slug in LEAGUES:
        doc = fetch(slug)
        if "_error" in doc:
            lines.append("%s: %s" % (slug, doc["_error"]))
            continue
        groups = doc.get("children") or []
        entries = []
        for group in groups:
            entries += ((group.get("standings") or {}).get("entries") or [])
        if not entries:
            lines.append("%s: no entries (keys=%s)" % (slug, list(doc)))
            continue
        entry = entries[0]
        stats = entry.get("stats") or []
        lines.append("%s: %d entries · stat names: %s" % (
            slug, len(entries), ", ".join(sorted({s.get("name", "?") for s in stats}))))
        sample = {s.get("name"): s.get("value") for s in stats}
        lines.append("    first row: %s" % json.dumps(sample, ensure_ascii=False)[:700])
        team = entry.get("team") or {}
        lines.append("    team keys: %s" % ", ".join(sorted(team)))
    body = "\n".join(lines)
    print(body)
    token, repo, issue = (os.environ.get("GH_TOKEN"), os.environ.get("REPO"),
                          os.environ.get("ISSUE_NUMBER"))
    if token and repo and issue:
        request = urllib.request.Request(
            "https://api.github.com/repos/%s/issues/%s/comments" % (repo, issue),
            data=json.dumps({"body": body[:60000]}).encode(),
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"},
        )
        urllib.request.urlopen(request, timeout=30).read()


if __name__ == "__main__":
    main()
