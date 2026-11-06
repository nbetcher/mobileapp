#!/usr/bin/env python3
"""Add missing branches to fork-owned `when` -> wire-string maps after an upstream sync.

Reads Kotlin compiler output for "'when' expression must be exhaustive" errors and, for each
site in an allowed file whose `when` body is nothing but `is <Type> -> "<snake_case>"` lines,
inserts the missing subtypes with a snake_case wire value derived from the class name. Any
other shape is reported as unfixable and left untouched.

Usage:
  exhaustive_when_fix.py --log COMPILE_LOG --allow ALLOWLIST [--root DIR] [--report FILE]

ALLOWLIST is a file of repo-relative paths that may be edited, one per line.

Exit codes: 0 at least one branch added, 3 nothing fixable, 1 usage/IO error.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field

ERROR_RE = re.compile(
    r"^e: (?:file://)?(?P<path>/[^:]+):(?P<line>\d+):(?P<col>\d+) "
    r"'when' expression must be exhaustive\. Add the (?P<branches>.+?) branch(?:es)? "
    r"or an 'else' branch\."
)
MISSING_RE = re.compile(r"'([^']+)'")
BRANCH_RE = re.compile(r'^(?P<indent>\s*)is (?P<type>[A-Za-z_][A-Za-z0-9_.]*) -> "(?P<value>[a-z0-9_]+)",?\s*$')
IGNORABLE_RE = re.compile(r"^\s*(//.*)?$")


@dataclass(frozen=True)
class Site:
    path: str
    line: int
    col: int
    missing: tuple[str, ...]


@dataclass
class Result:
    added: list[str] = field(default_factory=list)
    unfixable: list[str] = field(default_factory=list)


def snake_case(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_", name).lower()


def parse_sites(log: str, root: str) -> list[Site]:
    sites: dict[tuple[str, int, int], Site] = {}
    for raw in log.splitlines():
        m = ERROR_RE.match(raw.strip())
        if not m:
            continue
        path = os.path.relpath(os.path.realpath(m["path"]), os.path.realpath(root))
        key = (path, int(m["line"]), int(m["col"]))
        sites[key] = Site(path, key[1], key[2], tuple(MISSING_RE.findall(m["branches"])))
    return list(sites.values())


def _skip_string(text: str, i: int) -> int:
    quote = text[i]
    if text.startswith('"""', i):
        end = text.find('"""', i + 3)
        return len(text) if end < 0 else end + 3
    i += 1
    while i < len(text) and text[i] != quote:
        i += 2 if text[i] == "\\" else 1
    return i + 1


def _matching_brace(text: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    while i < len(text):
        c = text[i]
        if c in "\"'":
            i = _skip_string(text, i)
            continue
        if text.startswith("//", i):
            nl = text.find("\n", i)
            i = len(text) if nl < 0 else nl
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = len(text) if end < 0 else end + 2
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def fix_site(lines: list[str], site: Site) -> tuple[list[str], list[str]] | str:
    """Return (new_lines, added descriptions) or an unfixable reason."""
    if any(not m.startswith("is ") for m in site.missing):
        return "missing branch is not a type check: " + ", ".join(site.missing)
    text = "".join(lines)
    offset = sum(len(l) for l in lines[: site.line - 1]) + site.col - 1
    if not text.startswith("when", offset):
        return "compiler position does not point at a 'when'"
    open_idx = text.find("{", offset)
    close_idx = _matching_brace(text, open_idx) if open_idx >= 0 else -1
    if close_idx < 0:
        return "could not find the 'when' body"
    first = text.count("\n", 0, open_idx) + 1
    last = text.count("\n", 0, close_idx)
    if text[open_idx + 1 :].split("\n", 1)[0].strip() or lines[last][: close_idx - text.rfind("\n", 0, close_idx) - 1].strip():
        return "'when' braces do not sit on their own lines"
    body = lines[first:last]
    branches = [BRANCH_RE.match(l) for l in body]
    if not any(branches) or any(b is None and not IGNORABLE_RE.match(l) for b, l in zip(branches, body)):
        return "'when' body is not purely `is Type -> \"wire_value\"` lines"
    matched = [b for b in branches if b]
    prefixes = {b["type"].rpartition(".")[0] for b in matched}
    if len(prefixes) != 1:
        return "existing branches do not share one type qualifier"
    prefix = prefixes.pop()
    used = {b["value"] for b in matched}
    last_branch = max(i for i, b in enumerate(branches) if b)
    indent = branches[last_branch]["indent"]
    trailing_comma = body[last_branch].rstrip().endswith(",")
    new_lines, added = [], []
    for missing in site.missing:
        name = missing[3:]
        simple = name.rpartition(".")[2]
        qualified = name if "." in name or not prefix else f"{prefix}.{name}"
        value = snake_case(simple)
        if value in used:
            return f"derived wire value '{value}' for {simple} is already used"
        used.add(value)
        new_lines.append(f'{indent}is {qualified} -> "{value}"{"," if trailing_comma else ""}\n')
        added.append(f"{site.path}:{site.line}: is {qualified} -> \"{value}\"")
    insert_at = first + last_branch + 1
    return lines[:insert_at] + new_lines + lines[insert_at:], added


def apply(sites: list[Site], allowed: set[str], root: str) -> Result:
    result = Result()
    by_file: dict[str, list[Site]] = {}
    for s in sites:
        if s.path not in allowed:
            result.unfixable.append(f"{s.path}:{s.line}: not a fork-owned file")
            continue
        by_file.setdefault(s.path, []).append(s)
    for path, file_sites in by_file.items():
        full = os.path.join(root, path)
        with open(full, encoding="utf-8") as f:
            lines = f.readlines()
        changed = False
        for s in sorted(file_sites, key=lambda s: (s.line, s.col), reverse=True):
            outcome = fix_site(lines, s)
            if isinstance(outcome, str):
                result.unfixable.append(f"{s.path}:{s.line}: {outcome}")
                continue
            lines, added = outcome
            result.added.extend(added)
            changed = True
        if changed:
            with open(full, "w", encoding="utf-8") as f:
                f.writelines(lines)
    return result


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True)
    ap.add_argument("--allow", required=True)
    ap.add_argument("--root", default=".")
    ap.add_argument("--report")
    a = ap.parse_args(argv)
    try:
        with open(a.log, encoding="utf-8", errors="replace") as f:
            log = f.read()
        with open(a.allow, encoding="utf-8") as f:
            allowed = {l.strip() for l in f if l.strip()}
    except OSError as e:
        print(e, file=sys.stderr)
        return 1
    result = apply(parse_sites(log, a.root), allowed, a.root)
    report = [f"added: {x}" for x in result.added] + [f"unfixable: {x}" for x in result.unfixable]
    print("\n".join(report) or "no exhaustive-when errors found")
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            f.write("\n".join(report) + "\n")
    return 0 if result.added else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
