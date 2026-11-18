"""TEMPORARY: post the failing build output of this run to an issue.

The development workspace cannot read Actions logs, so a red run explains itself here.
"""
import glob
import json
import os
import urllib.request
import xml.etree.ElementTree as ET


def main():
    lines = []
    for path in glob.glob("app/build/test-results/**/*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                lines.append("`%s.%s`\n```\n%s\n```" % (
                    case.get("classname"), case.get("name"),
                    "\n".join((bad.text or "").strip().splitlines()[:5])))
    body = "### failing unit tests\n\n" + ("\n".join(lines) if lines else "none in the XML reports")
    errors = []
    if os.path.exists("build-output.txt"):
        with open("build-output.txt", encoding="utf-8", errors="replace") as handle:
            text = handle.read().splitlines()
        errors = [line for line in text if "e: file" in line or "error:" in line or "FAILURE:" in line][:15]
    if errors:
        body += "\n\n### build output\n```\n" + "\n".join(errors)[:20000] + "\n```"
    print(body[:4000])
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
