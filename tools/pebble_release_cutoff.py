#!/usr/bin/env python3
"""
pebble_release_cutoff.py — resolve the public-repo commit cutoff for the
currently-released Pebble mobile app (coredevices/mobileapp).

Pipeline (all anonymous HTTP, no device, no APK):
  1. Store version:  iTunes Lookup API (primary) -> Play web page scrape (fallback)
  2. Changelog:      Notion internal API on ndocs.repebble.com (loadPageChunk)
                     -> per-version release date + item list
  3. Cutoff commit:  fuzzy-match changelog items against `git log` subjects
                     (author dates survive Core Devices' internal->public
                     rebase-sync; committer dates do not)
                     cutoff = highest-position matched commit
     Fallback:       newest commit authored on/before the release date

Usage:
  pebble_release_cutoff.py --repo /path/to/mobileapp [--version 1.5.0.2]
                           [--branch master] [--no-fetch] [--json] [-v]

Output (stdout): the cutoff SHA (last line), suitable for
  git -C fork update-ref refs/heads/release-track $(pebble_release_cutoff.py ...)

Exit codes: 0 ok (>=2 item matches), 3 ok but date-fallback only, 1 hard error.
"""

import argparse, json, re, subprocess, sys, unicodedata, urllib.request
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher

NDOCS = "https://ndocs.repebble.com"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) pebble-release-cutoff/1.0",
      "Content-Type": "application/json"}
ITUNES = "https://itunes.apple.com/lookup?bundleId=coredevices.coreapp"
PLAY = "https://play.google.com/store/apps/details?id=coredevices.coreapp&hl=en_US"
VERSION_RE = re.compile(r"\b(\d+\.\d+\.\d+(?:\.\d+)?)\b")


def http(url, data=None, timeout=45):
    req = urllib.request.Request(url, data=data, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


# ---------- 1. store version ----------

def store_version(verbose=False):
    try:
        d = json.loads(http(ITUNES))
        v = d["results"][0]["version"].strip()
        if VERSION_RE.fullmatch(v):
            if verbose: print(f"[store] iTunes lookup -> {v}", file=sys.stderr)
            return v, "itunes"
    except Exception as e:
        if verbose: print(f"[store] iTunes failed: {e}", file=sys.stderr)
    try:
        html = http(PLAY).decode("utf-8", "replace")
        m = re.search(r'\[\[\["(\d+\.\d+\.\d+(?:\.\d+)?)"\]\]', html)
        if m:
            if verbose: print(f"[store] Play scrape -> {m.group(1)}", file=sys.stderr)
            return m.group(1), "play"
    except Exception as e:
        if verbose: print(f"[store] Play failed: {e}", file=sys.stderr)
    raise SystemExit("ERROR: could not determine store version from iTunes or Play")


# ---------- 2. changelog via Notion API ----------

def _notion_chunk(page_id):
    body = json.dumps({"pageId": page_id, "limit": 100,
                       "cursor": {"stack": []}, "chunkNumber": 0,
                       "verticalColumns": False}).encode()
    return json.loads(http(f"{NDOCS}/api/v3/loadPageChunk", body))


def _plain(prop):
    return "".join(x[0] for x in (prop or []) if isinstance(x, list))


def _val(b):
    return b.get("value", {}).get("value", b.get("value", {}))


def changelog(verbose=False):
    """Return list of dicts: {version, date, items} newest-first.

    The changelog page holds releases in MULTIPLE Notion simple-tables
    (a 'What's new' table for recent releases + an archive table inside a
    toggle, plus unrelated tables). Discover every table dynamically and
    keep rows that classify as (version, parseable date, bulleted notes).
    """
    html = http(f"{NDOCS}/changelog").decode("utf-8", "replace")
    m = re.search(r'"pageId":"([0-9a-f-]{36})"', html)
    if not m:
        raise SystemExit("ERROR: changelog pageId not found in HTML shell")
    root_blocks = _notion_chunk(m.group(1)).get("recordMap", {}).get("block", {})
    table_ids = [bid for bid, b in root_blocks.items() if _val(b).get("type") == "table"]
    if verbose:
        print(f"[changelog] {len(table_ids)} tables on page", file=sys.stderr)

    out, seen = [], {}
    for tid in table_ids:
        blocks = _notion_chunk(tid).get("recordMap", {}).get("block", {})
        # preserve table order via the table block's content list when present
        order = _val(blocks.get(tid, {})).get("content") or list(blocks.keys())
        for rid in order:
            b = blocks.get(rid)
            if not b or _val(b).get("type") != "table_row":
                continue
            cells = [_plain(p).strip() for p in (_val(b).get("properties") or {}).values()]
            ver = date = notes = None
            for c in cells:
                if VERSION_RE.fullmatch(c):
                    ver = c
                elif _parse_date(c):
                    date = _parse_date(c)
                elif len(c) > 20:
                    notes = c
            if ver and date and notes:
                items = _items(notes)
                if not items:
                    continue
                # dedupe across tables; keep the entry with the newer date
                if ver in seen and seen[ver]["date"] >= date:
                    continue
                seen[ver] = {"version": ver, "date": date, "items": items}
    out = sorted(seen.values(), key=lambda r: r["date"], reverse=True)
    if not out:
        raise SystemExit("ERROR: no changelog rows parsed from any table")
    if verbose:
        print(f"[changelog] {len(out)} versions, newest {out[0]['version']} "
              f"({out[0]['date']:%Y-%m-%d})", file=sys.stderr)
    return out


def _parse_date(s):
    if not s: return None
    s = re.sub(r"(\d+)(st|nd|rd|th)", r"\1", s).replace(",", " ")
    s = re.sub(r"\s+", " ", s).strip()
    for fmt in ("%B %d %Y", "%b %d %Y", "%d %B %Y"):
        try:
            return datetime.strptime(s, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    return None


def _items(notes):
    items = []
    for line in notes.splitlines():
        line = line.strip()
        if line.startswith(("-", "*", "•")):
            items.append(line.lstrip("-*• ").strip())
    return [i for i in items if len(i) > 8]


# ---------- 3. cutoff via fuzzy commit matching ----------

_STRIP = re.compile(r"^(android|ios|index|speech|health|recording|onboarding|ui)\s*:\s*", re.I)
_PAREN = re.compile(r"\([^)]*\)")  # drop ALL parentheticals: (#123), (thanks X!), (Android + iOS)...
_NONWORD = re.compile(r"[^a-z0-9 ]+")


def _norm(s):
    s = unicodedata.normalize("NFKD", s).lower()
    s = _STRIP.sub("", s)
    s = _PAREN.sub(" ", s)
    s = _NONWORD.sub(" ", s)
    return re.sub(r"\s+", " ", s).strip()


def _score(a, b):
    na, nb = _norm(a), _norm(b)
    if not na or not nb: return 0.0
    seq = SequenceMatcher(None, na, nb).ratio()
    ta, tb = set(na.split()), set(nb.split())
    jac = len(ta & tb) / len(ta | tb) if ta | tb else 0
    contain = len(ta & tb) / min(len(ta), len(tb)) if ta and tb else 0
    return max(seq, 0.4 * jac + 0.6 * contain)


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True,
                          text=True, check=True).stdout


def cutoff(repo, branch, entry, verbose=False, threshold=0.55):
    rel = entry["date"] or datetime.now(timezone.utc)
    lo = (rel - timedelta(days=45)).strftime("%Y-%m-%d")
    hi = (rel + timedelta(days=2)).strftime("%Y-%m-%d")
    # IMPORTANT: filter by AUTHOR date in Python. git's --since/--until use
    # committer dates, and Core Devices' internal->public rebase-sync rewrites
    # committer dates (author dates survive), so released work can carry a
    # committer date days after the release.
    log = git(repo, "log", branch, "--format=%H|%aI|%s")
    commits = []
    for line in log.splitlines():
        h, ad, s = line.split("|", 2)
        if lo <= ad[:10] <= hi:
            commits.append((h, ad, s))
    if verbose:
        print(f"[cutoff] {len(commits)} candidate commits authored {lo}..{hi} "
              f"for {len(entry['items'])} changelog items", file=sys.stderr)

    matches = []
    for item in entry["items"]:
        best = max(((h, ad, s, _score(item, s)) for h, ad, s in commits),
                   key=lambda x: x[3], default=None)
        if best and best[3] >= threshold:
            matches.append({"item": item, "sha": best[0], "subject": best[2],
                            "score": round(best[3], 2), "adate": best[1]})
            if verbose:
                print(f"  MATCH {best[3]:.2f} {best[0][:9]} '{item[:48]}' -> '{best[2][:48]}'",
                      file=sys.stderr)
        elif verbose:
            sc = best[3] if best else 0
            print(f"  miss  {sc:.2f} '{item[:60]}'", file=sys.stderr)

    if len(matches) >= 2:
        pos = {m["sha"]: int(git(repo, "rev-list", "--count", m["sha"]))
               for m in matches}
        tip = max(matches, key=lambda m: pos[m["sha"]])
        return tip["sha"], "item-match", matches, pos[tip["sha"]]

    # fallback: newest commit AUTHORED on/before the release date
    log_all = git(repo, "log", branch, "--format=%H %aI")
    fb = next((h for h, d in (l.split() for l in log_all.splitlines())
               if d[:10] <= f"{rel:%Y-%m-%d}"), None)
    if fb is None:
        raise SystemExit("ERROR: no commit authored before release date")
    return fb, "date-fallback", matches, int(git(repo, "rev-list", "--count", fb))


# ---------- main ----------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="path to local clone of coredevices/mobileapp")
    ap.add_argument("--branch", default="master")
    ap.add_argument("--version", help="override store version (skip store lookup)")
    ap.add_argument("--no-fetch", action="store_true", help="don't git fetch first")
    ap.add_argument("--json", action="store_true", help="emit full JSON to stdout")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()

    if not a.no_fetch:
        subprocess.run(["git", "-C", a.repo, "fetch", "--quiet", "origin",
                        f"{a.branch}:{a.branch}"], check=False)

    if a.version:
        ver, src = a.version, "override"
    else:
        ver, src = store_version(a.verbose)

    entries = changelog(a.verbose)
    entry = next((e for e in entries if e["version"] == ver), None)
    if entry is None:
        # store may briefly show a version not yet on changelog, or vice versa;
        # fall back to newest changelog entry <= store version numerically
        def key(v): return tuple(int(x) for x in v.split("."))
        cands = [e for e in entries if key(e["version"]) <= key(ver)]
        if not cands:
            raise SystemExit(f"ERROR: version {ver} not found in changelog")
        entry = cands[0]
        print(f"WARN: store version {ver} not in changelog; using {entry['version']}",
              file=sys.stderr)

    sha, method, matches, pos = cutoff(a.repo, a.branch, entry, a.verbose)
    head_pos = int(git(a.repo, "rev-list", "--count", a.branch))

    result = {"store_version": ver, "store_source": src,
              "changelog_version": entry["version"],
              "release_date": entry["date"].strftime("%Y-%m-%d") if entry["date"] else None,
              "method": method,
              "items_total": len(entry["items"]), "items_matched": len(matches),
              "cutoff_sha": sha, "cutoff_position": pos,
              "branch_head_position": head_pos,
              "unreleased_commits_beyond_cutoff": head_pos - pos,
              "matches": matches}

    if a.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"version={ver} method={method} matched={len(matches)}/{len(entry['items'])} "
              f"cutoff_pos={pos}/{head_pos}", file=sys.stderr)
        print(sha)

    sys.exit(0 if method == "item-match" else 3)


if __name__ == "__main__":
    main()
