"""Temporary helper: post failing unit-test detail to an issue.

The development workspace cannot read GitHub Actions logs (the log download fails), so a
failing run posts the same information as an issue comment, which the workspace can read.
Remove this file and its workflow step once it is no longer needed.
"""
import glob
import json
import os
import re
import urllib.request

MAX = 55_000


def failure_lines() -> list[str]:
    lines = []
    for path in glob.glob("app/build/test-results/**/*.xml", recursive=True) + \
            glob.glob("engine/build/test-results/**/*.xml", recursive=True):
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for case, body in re.findall(r"<testcase([^>]*)>(.*?)</testcase>", text, re.S):
            if "<failure" not in body and "<error" not in body:
                continue
            name = re.search(r'name="([^"]*)"', case)
            klass = re.search(r'classname="([^"]*)"', case)
            message = re.search(r"<(?:failure|error)[^>]*message=\"([^\"]*)\"", body)
            head = re.sub(r"\s+", " ", (message.group(1) if message else ""))[:600]
            # The stack trace names the failing line in the test source.
            trace = [line.strip() for line in re.findall(r"at com\.footballai[^\n]*", body)][:6]
            lines.append(
                f"{klass.group(1) if klass else '?'}.{name.group(1) if name else '?'}\n  {head}\n  "
                + "\n  ".join(trace)
            )
    return lines


def tail(path: str, count: int = 120) -> str:
    try:
        return "\n".join(open(path, encoding="utf-8", errors="replace").read().splitlines()[-count:])
    except OSError:
        return f"({path} not found)"


def main() -> int:
    body = ["### failing unit tests", ""]
    failures = failure_lines()
    body += failures if failures else ["(no test-result XML found)"]
    body += ["", "### build output (tail)", "```", tail("build-output.txt"), "```"]
    text = "\n".join(body)[:MAX]
    issue = os.environ["ISSUE_NUMBER"]
    token = os.environ["GH_TOKEN"]
    repo = os.environ["REPO"]
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/issues/{issue}/comments",
        data=json.dumps({"body": text}).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "football-ai ci report",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        print("posted", response.status)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
