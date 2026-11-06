#!/usr/bin/env python3
"""Add new upstream sealed subtypes to fork-owned `when` -> wire-string maps.

Indexes every `sealed class`/`sealed interface` in the tree with the subtypes nested directly
inside it, then scans the allowed (fork-owned) files for `when` bodies made of nothing but
`is Sealed.Sub -> "snake_case"` lines. Subtypes such a `when` lacks are inserted with a
snake_case wire value derived from the class name. Anything it cannot read with certainty
is left for the compiler to report.

Usage:
  exhaustive_when_fix.py --allow ALLOWLIST [--root DIR] [--report FILE]

ALLOWLIST is a file of repo-relative paths that may be edited, one per line.

Exit codes: 0 at least one branch added, 3 nothing to add, 1 usage/IO error.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field

BRANCH_RE = re.compile(r'^(?P<indent>\s*)is (?P<type>[A-Za-z_][A-Za-z0-9_.]*) -> "(?P<value>[a-z0-9_]+)",?\s*$')
IGNORABLE_RE = re.compile(r"^\s*(//.*)?$")
SEALED_RE = re.compile(r"\bsealed\s+(?:class|interface)\s+([A-Za-z_]\w*)")
DECL_RE = re.compile(r"\b(?:class|object|interface)\s+([A-Za-z_]\w*)")
WHEN_RE = re.compile(r"\bwhen\b")
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea"}


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


@dataclass(frozen=True)
class WhenMap:
    first: int
    last_branch: int
    branches: tuple[re.Match, ...]


def snake_case(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_", name).lower()


def mask(text: str) -> str:
    """Blank out comments and string/char literals, preserving offsets and newlines."""
    out = list(text)
    i = 0

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, len(out))):
            if out[k] != "\n":
                out[k] = " "

    while i < len(text):
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = len(text) if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = len(text) if end < 0 else end + 2
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = len(text) if end < 0 else end + 3
        elif text[i] in "\"'":
            end = i + 1
            while end < len(text) and text[end] != text[i] and text[end] != "\n":
                end += 2 if text[end] == "\\" else 1
            end += 1
        else:
            i += 1
            continue
        blank(i, end)
        i = end
    return "".join(out)


def _matching(masked: str, open_idx: int, pair: str = "{}") -> int:
    depth = 0
    for i in range(open_idx, len(masked)):
        if masked[i] == pair[0]:
            depth += 1
        elif masked[i] == pair[1]:
            depth -= 1
            if depth == 0:
                return i
    return -1


def _supertypes(masked: str, i: int) -> str:
    """Supertype list of a declaration whose name ends at i, or '' if it has none."""
    n = len(masked)
    while i < n and masked[i] in " \t":
        i += 1
    if i < n and masked[i] == "<":
        i = _matching(masked, i, "<>") + 1
        if i <= 0:
            return ""
    rest = re.match(r"\s*(?:(?:private|internal|public|protected)\s+)?(?:constructor\s*)?", masked[i:])
    i += rest.end() if rest else 0
    if i < n and masked[i] == "(":
        i = _matching(masked, i, "()") + 1
        if i <= 0:
            return ""
    while i < n and masked[i] in " \t":
        i += 1
    if i >= n or masked[i] != ":":
        return ""
    end = i + 1
    while end < n and masked[end] not in "{}\n":
        end += 1
    return masked[i + 1 : end]


def sealed_subtypes(text: str) -> dict[str, list[str]]:
    """Map each sealed type declared in `text` to the subtypes nested directly inside it."""
    masked = mask(text)
    result: dict[str, list[str]] = {}
    for m in SEALED_RE.finditer(masked):
        name = m[1]
        header_end = re.search(r"[{\n]", masked[m.end() :])
        if not header_end or masked[m.end() + header_end.start()] != "{":
            # Brace on a later line (multi-line constructor): find the body after the header.
            open_idx = masked.find("{", m.end())
        else:
            open_idx = m.end() + header_end.start()
        close_idx = _matching(masked, open_idx) if open_idx >= 0 else -1
        if close_idx < 0:
            continue
        body_start = open_idx + 1
        depth, depths, subs = 0, [], []
        for c in masked[body_start:close_idx]:
            depths.append(depth)
            if c in "{(":
                depth += 1
            elif c in "})":
                depth -= 1
        for d in DECL_RE.finditer(masked, body_start, close_idx):
            if depths[d.start() - body_start] != 0:
                continue
            if re.search(rf"(?<![\w.]){re.escape(name)}\b", _supertypes(masked, d.end())):
                subs.append(d[1])
        result[name] = subs
    return result


def index_sealed(root: str) -> dict[str, list[list[str]]]:
    index: dict[str, list[list[str]]] = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            try:
                with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            if "sealed" not in text:
                continue
            for name, subs in sealed_subtypes(text).items():
                if subs:
                    index.setdefault(name, []).append(subs)
    return index


def read_when_map(lines: list[str], line: int, col: int) -> WhenMap | str:
    """Parse the `when` at (line, col) as a pure `is Type -> "value"` map, or say why not."""
    text = "".join(lines)
    masked = mask(text)
    offset = sum(len(l) for l in lines[: line - 1]) + col - 1
    if not masked.startswith("when", offset):
        return "position does not point at a 'when'"
    open_idx = masked.find("{", offset)
    close_idx = _matching(masked, open_idx) if open_idx >= 0 else -1
    if close_idx < 0:
        return "could not find the 'when' body"
    first = text.count("\n", 0, open_idx) + 1
    last = text.count("\n", 0, close_idx)
    if masked[open_idx + 1 :].split("\n", 1)[0].strip() or masked[text.rfind("\n", 0, close_idx) + 1 : close_idx].strip():
        return "'when' braces do not sit on their own lines"
    body = lines[first:last]
    branches = [BRANCH_RE.match(l) for l in body]
    if not any(branches) or any(b is None and not IGNORABLE_RE.match(l) for b, l in zip(branches, body)):
        return "'when' body is not purely `is Type -> \"wire_value\"` lines"
    last_branch = max(i for i, b in enumerate(branches) if b)
    return WhenMap(first, last_branch, tuple(branches))


def fix_site(lines: list[str], site: Site) -> tuple[list[str], list[str]] | str:
    """Return (new_lines, added descriptions) or an unfixable reason."""
    when = read_when_map(lines, site.line, site.col)
    if isinstance(when, str):
        return when
    matched = [b for b in when.branches if b]
    prefixes = {b["type"].rpartition(".")[0] for b in matched}
    if len(prefixes) != 1:
        return "existing branches do not share one type qualifier"
    prefix = prefixes.pop()
    used = {b["value"] for b in matched}
    last = when.branches[when.last_branch]
    trailing_comma = last.string.rstrip().endswith(",")
    new_lines, added = [], []
    for missing in site.missing:
        qualified = f"{prefix}.{missing}" if prefix else missing
        value = snake_case(missing)
        if value in used:
            return f"derived wire value '{value}' for {missing} is already used"
        used.add(value)
        new_lines.append(f'{last["indent"]}is {qualified} -> "{value}"{"," if trailing_comma else ""}\n')
        added.append(f'{site.path}:{site.line}: is {qualified} -> "{value}"')
    insert_at = when.first + when.last_branch + 1
    return lines[:insert_at] + new_lines + lines[insert_at:], added


def find_sites(path: str, text: str, index: dict[str, list[list[str]]]) -> list[Site]:
    lines = text.splitlines(keepends=True)
    line_starts = [0]
    for l in lines:
        line_starts.append(line_starts[-1] + len(l))
    sites = []
    for m in WHEN_RE.finditer(mask(text)):
        line = next(i for i in range(len(lines)) if line_starts[i + 1] > m.start()) + 1
        col = m.start() - line_starts[line - 1] + 1
        when = read_when_map(lines, line, col)
        if isinstance(when, str):
            continue
        types = [b["type"] for b in when.branches if b]
        prefixes = {t.rpartition(".")[0] for t in types}
        if len(prefixes) != 1 or not next(iter(prefixes)):
            continue
        sealed = next(iter(prefixes)).rpartition(".")[2]
        present = {t.rpartition(".")[2] for t in types}
        candidates = [subs for subs in index.get(sealed, []) if present <= set(subs)]
        if len(candidates) != 1:
            continue
        missing = tuple(s for s in candidates[0] if s not in present)
        if missing:
            sites.append(Site(path, line, col, missing))
    return sites


def run(root: str, allowed: set[str]) -> Result:
    index = index_sealed(root)
    result = Result()
    for path in sorted(allowed):
        full = os.path.join(root, path)
        if not path.endswith(".kt") or not os.path.isfile(full):
            continue
        with open(full, encoding="utf-8") as f:
            text = f.read()
        sites = find_sites(path, text, index)
        if not sites:
            continue
        lines = text.splitlines(keepends=True)
        changed = False
        for s in sorted(sites, key=lambda s: (s.line, s.col), reverse=True):
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
    ap.add_argument("--allow", required=True)
    ap.add_argument("--root", default=".")
    ap.add_argument("--report")
    a = ap.parse_args(argv)
    try:
        with open(a.allow, encoding="utf-8") as f:
            allowed = {l.strip() for l in f if l.strip()}
    except OSError as e:
        print(e, file=sys.stderr)
        return 1
    result = run(a.root, allowed)
    report = [f"added: {x}" for x in result.added] + [f"unfixable: {x}" for x in result.unfixable]
    print("\n".join(report) or "no fork `when` map is missing a sealed subtype")
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            f.write("\n".join(report) + ("\n" if report else ""))
    return 0 if result.added else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
