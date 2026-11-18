"""TEMPORARY: post the failing unit tests of this run to an issue.

The development workspace cannot read Actions logs (the log download is blocked and
`gh run view --log` fails), so a red run explains itself here instead.
"""
import glob
import json
import os
import re
import urllib.request
import xml.etree.ElementTree as ET


def failures():
    lines = []
    for path in glob.glob("app/build/test-results/**/*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                text = (bad.text or "").strip().splitlines()
                head = text[:6]
                lines.append("- `%s.%s`\n```\n%s\n```" % (
                    case.get("classname"), case.get("name"), "\n".join(head)))
    return lines


def main():
    found = failures()
    body = "### failing unit tests\n\n" + ("\n".join(found) if found else "none found in the XML reports")
    print(body[:4000])
    token, repo, issue = os.environ.get("GH_TOKEN"), os.environ.get("REPO"), os.environ.get("ISSUE_NUMBER")
    if token and repo and issue:
        request = urllib.request.Request(
            "https://api.github.com/repos/%s/issues/%s/comments" % (repo, issue),
            data=json.dumps({"body": body[:60000]}).encode(),
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"},
        )
        urllib.request.urlopen(request, timeout=30).read()


if __name__ == "__main__":
    main()
