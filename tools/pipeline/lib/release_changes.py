#!/usr/bin/env python3
"""Upstream changes for a fork release, and splicing them into its release notes.

  release_changes.py facts --tag TAG --releases RELEASES.json --out-dir DIR
      Writes what changed upstream since the previous published release into DIR:
      changelog.md (upstream's own changelog entries for every version in between),
      commits.md (the upstream commits), facts.md (both, the summary's input) and meta.json.

  release_changes.py section --dir DIR [--summary SUMMARY.md]
      Prints the release-notes section: the summary if it is usable, then the facts.

  release_changes.py splice --body BODY.md --section SECTION.md
      Prints BODY.md with SECTION.md in place of an earlier section, else before
      "### Download", else at the end.

RELEASES.json is `gh api repos/{owner}/{repo}/releases --paginate` output (tag_name, body, draft).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

START = "<!-- upstream-changes:start -->"
END = "<!-- upstream-changes:end -->"
TAG_RE = re.compile(r"^v(\d+(?:\.\d+)+)-tasker\.(\d+)$")
CUTOFF_RE = re.compile(r"Upstream cutoff \| `([0-9a-f]{7,40})`")
UPSTREAM_COMMIT_URL = "https://github.com/coredevices/mobileapp/commit/"
MAX_COMMITS = 300


def version_key(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in v.split("."))


def parse_release(rel: dict) -> tuple[tuple[int, ...], int, str] | None:
    m = TAG_RE.match(rel.get("tag_name", ""))
    if not m or rel.get("draft"):
        return None
    return version_key(m[1]), int(m[2]), m[1]


def previous_release(releases: list[dict], tag: str) -> dict | None:
    """The newest published release of an older upstream version."""
    this = next((parse_release(r) for r in releases if r.get("tag_name") == tag), None)
    if this is None:
        return None
    older = [(p, r) for r in releases if (p := parse_release(r)) and p[0] < this[0]]
    return max(older, key=lambda pr: pr[0][:2])[1] if older else None


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def resolve(rev: str) -> str | None:
    proc = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}"],
                          capture_output=True, text=True)
    return proc.stdout.strip() or None


def cutoff_of(release: dict) -> str | None:
    m = CUTOFF_RE.search(release.get("body") or "")
    if m:
        return resolve(m[1])
    for line in git("log", "--first-parent", "--format=%H %s", release["tag_name"]).splitlines():
        sha, _, subject = line.partition(" ")
        if subject.startswith("sync: merge upstream release"):
            return resolve(f"{sha}^2")
    return None


def upstream_commits(prev_cut: str | None, cut: str) -> list[tuple[str, str, str]] | None:
    if not prev_cut or subprocess.run(["git", "merge-base", "--is-ancestor", prev_cut, cut]).returncode:
        return None
    out = git("log", "--no-merges", "--format=%H%x1f%an%x1f%s", f"{prev_cut}..{cut}")
    return [tuple(l.split("\x1f", 2)) for l in out.splitlines() if l]


def changelog_entries(prev_version: str | None, version: str, entries: list[dict]) -> list[dict]:
    lo = version_key(prev_version) if prev_version else None
    hi = version_key(version)
    picked = [e for e in entries if version_key(e["version"]) <= hi and (lo is None or version_key(e["version"]) > lo)]
    if lo is None:
        picked = [e for e in picked if version_key(e["version"]) == hi]
    return sorted(picked, key=lambda e: version_key(e["version"]), reverse=True)


def fetch_changelog() -> list[dict]:
    try:
        from pebble_release_cutoff import changelog
        return [{"version": e["version"], "date": e["date"].strftime("%Y-%m-%d"), "notes": e["notes"]}
                for e in changelog()]
    except (Exception, SystemExit) as e:  # the notes still get the commit list
        print(f"changelog unavailable: {e}", file=sys.stderr)
        return []


def format_notes(notes: str) -> list[str]:
    """Upstream's notes cell as markdown: wrapped bullet lines joined, "Heading:" lines bolded."""
    out: list[str] = []
    for raw in notes.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith(("-", "*", "•")):
            out.append("- " + line.lstrip("-*• ").strip())
        elif line.endswith(":") and len(line) < 40:
            out.append(f"*{line[:-1]}*")
        elif out and out[-1].startswith("- "):
            out[-1] += " " + line
        else:
            out.append(line)
    return out


def render_changelog(entries: list[dict]) -> str:
    out = []
    for e in entries:
        out.append(f"**{e['version']}** ({e['date']})\n")
        out.extend(format_notes(e["notes"]))
        out.append("")
    return "\n".join(out)


def render_commits(commits: list[tuple[str, str, str]] | None) -> str:
    out = []
    if commits is None:
        out.append("_No upstream commit list: there is no earlier release of this fork with shared history._\n")
    elif commits:
        shown = commits[:MAX_COMMITS]
        out.extend(f"- [`{sha[:8]}`]({UPSTREAM_COMMIT_URL}{sha}) {subject} — {author}"
                   for sha, author, subject in shown)
        if len(commits) > len(shown):
            out.append(f"- … and {len(commits) - len(shown)} more")
        out.append("")
    return "\n".join(out)


def splice(body: str, section: str) -> str:
    block = f"{START}\n{section.strip()}\n{END}\n"
    if START in body and END in body:
        head, _, rest = body.partition(START)
        _, _, tail = rest.partition(END)
        return head + block.rstrip("\n") + tail
    i = body.find("### Download")
    if i >= 0:
        return body[:i] + block + "\n" + body[i:]
    return body.rstrip("\n") + "\n\n" + block


def usable_summary(text: str) -> bool:
    text = text.strip()
    return (40 < len(text) < 8000 and "<!--" not in text and "<script" not in text.lower()
            and any(l.lstrip().startswith("- ") for l in text.splitlines()))


def render_section(meta: dict, changelog: str, commits: str, summary: str | None) -> str:
    since = f" since {meta['previous_version']}" if meta["previous_version"] else ""
    out = [f"### What's new from upstream{since}\n"]
    if summary is not None and usable_summary(summary):
        out += [summary.strip(), "", "_Automatic summary of upstream's changelog and commits below._", ""]
        if changelog.strip():
            out += ["<details><summary>Upstream's changelog</summary>\n", changelog.strip(), "\n</details>\n"]
    elif changelog.strip():
        out += ["Upstream's changelog:\n", changelog.strip(), ""]
    if meta["commits"] > 0:
        out += [f"<details><summary>All {meta['commits']} upstream commits</summary>\n", commits.strip(), "\n</details>\n"]
    elif commits.strip():
        out += [commits.strip(), ""]
    return "\n".join(out)


def cmd_facts(a: argparse.Namespace) -> int:
    with open(a.releases, encoding="utf-8") as f:
        releases = json.load(f)
    rel = next((r for r in releases if r.get("tag_name") == a.tag), None)
    parsed = parse_release(rel) if rel else None
    if not parsed:
        print(f"{a.tag}: not a published fork release", file=sys.stderr)
        return 1
    cut = cutoff_of(rel)
    if not cut:
        print(f"{a.tag}: cannot determine the upstream cutoff", file=sys.stderr)
        return 1
    prev = previous_release(releases, a.tag)
    prev_version = parse_release(prev)[2] if prev else None
    commits = upstream_commits(cutoff_of(prev) if prev else None, cut)
    entries = changelog_entries(prev_version, parsed[2], fetch_changelog())
    meta = {"version": parsed[2], "previous_version": prev_version or "",
            "commits": -1 if commits is None else len(commits), "changelog_versions": len(entries)}
    changelog, commit_list = render_changelog(entries), render_commits(commits)
    os.makedirs(a.out_dir, exist_ok=True)
    files = {
        "changelog.md": changelog,
        "commits.md": commit_list,
        "facts.md": f"## Upstream's changelog\n\n{changelog}\n## Upstream commits\n\n{commit_list}",
        "meta.json": json.dumps(meta),
    }
    for name, text in files.items():
        with open(os.path.join(a.out_dir, name), "w", encoding="utf-8") as f:
            f.write(text)
    print(json.dumps(meta))
    return 0


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def cmd_section(a: argparse.Namespace) -> int:
    summary = _read(a.summary) if a.summary and os.path.isfile(a.summary) else None
    print(f"summary: {'used' if summary is not None and usable_summary(summary) else 'none'}", file=sys.stderr)
    sys.stdout.write(render_section(json.loads(_read(os.path.join(a.dir, "meta.json"))),
                                    _read(os.path.join(a.dir, "changelog.md")),
                                    _read(os.path.join(a.dir, "commits.md")), summary))
    return 0


def cmd_splice(a: argparse.Namespace) -> int:
    with open(a.body, encoding="utf-8") as f:
        body = f.read()
    with open(a.section, encoding="utf-8") as f:
        section = f.read()
    sys.stdout.write(splice(body, section))
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("facts")
    f.add_argument("--tag", required=True)
    f.add_argument("--releases", required=True)
    f.add_argument("--out-dir", required=True)
    c = sub.add_parser("section")
    c.add_argument("--dir", required=True)
    c.add_argument("--summary")
    s = sub.add_parser("splice")
    s.add_argument("--body", required=True)
    s.add_argument("--section", required=True)
    a = ap.parse_args(argv)
    return {"facts": cmd_facts, "section": cmd_section, "splice": cmd_splice}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
