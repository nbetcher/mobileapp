#!/bin/sh
# =============================================================================
# tap-check.sh — semantic tap-drift guard for the fork's patch taps.
#
# Realizes HLDD-004 §4 step "Tap-impact (G)" and §5 "Semantic tap drift" layer.
#
# Each ⚠ patch tap (HLDD-001 §5 / HOOKS.md §2) lives at a libpebble3 choke point
# next to a STABLE ANCHOR COMMENT (`// BRIDGE-TAP: <id>`) and an invocation of
# the neutral `AutomationNotificationHooks` facade. A rebase can compile cleanly
# yet still relocate or drop the choke point (e.g. upstream refactors the
# notification path); the compiler won't catch that, but this grep will.
#
# We assert, per tap, that BOTH markers are still present somewhere in the
# libpebble3 source tree:
#   * the stable anchor comment   `// BRIDGE-TAP: <id>`
#   * the corresponding facade callsite `AutomationNotificationHooks.<member>`
# Either one missing => semantic drift => exit 31 (caller -> TAP DRIFT issue).
#
# The anchor comment is the human-stable handle the conflict runbook (§8 step 2)
# tells the maintainer to re-attach when a tap moves; checking it (not just the
# callsite) is what flags a silently-relocated tap.
#
# Usage:
#   tap-check.sh
#
# Environment:
#   LIBPEBBLE_SRC  root to grep (default: libpebble3/src)
#
# Exit codes:
#   0   all taps present
#   31  one or more taps missing (caller -> TAP DRIFT issue)
# =============================================================================
set -eu

LIBPEBBLE_SRC="${LIBPEBBLE_SRC:-libpebble3/src}"
DRIFT_EXIT=31

if [ ! -d "$LIBPEBBLE_SRC" ]; then
  echo "tap-check.sh: source root '$LIBPEBBLE_SRC' not found" >&2
  exit 1
fi

# The taps to verify, as space-separated  "<id>|<anchor-substr>|<callsite-substr>"
# triples. Keep this list in sync with HOOKS.md §2 when taps are added/removed.
#   notif-send   : a forwarded notification is mirrored to the integration.
#   notif-action : a watch-initiated notification action is reported.
TAPS="
notif-send|// BRIDGE-TAP: notif-send|AutomationNotificationHooks.onSent
notif-action|// BRIDGE-TAP: notif-action|AutomationNotificationHooks.onAction
"

# Marker file the subshell appends drift detail to (the `while` loop below runs
# in a subshell behind the pipe, so it can't set parent-shell variables). The
# caller's issue.sh attaches this file to the TAP-DRIFT issue body, so we leave
# it in place on the drift path. Start fresh so a stale file can't false-positive.
MISSING_FILE="${MISSING_FILE:-/tmp/pipeline-tap-missing.txt}"
rm -f "$MISSING_FILE"

# grep helper: fixed-string, recursive, quiet. Returns 0 if found.
has() {
  # $1 = literal needle
  grep -R -F -q -- "$1" "$LIBPEBBLE_SRC"
}

# Iterate the tap table. IFS handling keeps the pipe-delimited fields intact.
echo "tap-check.sh: verifying patch taps under $LIBPEBBLE_SRC" >&2
echo "$TAPS" | while IFS='|' read -r id anchor callsite; do
  # Skip blank lines from the table.
  [ -n "$id" ] || continue
  ok=1
  if ! has "$anchor"; then
    echo "tap-check.sh: MISSING anchor for '$id': $anchor" >&2
    printf '%s: missing anchor "%s"\n' "$id" "$anchor" >> "$MISSING_FILE"
    ok=0
  fi
  if ! has "$callsite"; then
    echo "tap-check.sh: MISSING callsite for '$id': $callsite" >&2
    printf '%s: missing callsite "%s"\n' "$id" "$callsite" >> "$MISSING_FILE"
    ok=0
  fi
  if [ "$ok" -eq 1 ]; then
    echo "tap-check.sh: ok '$id'" >&2
  fi
done

# A non-empty marker file means at least one tap drifted.
if [ -s "$MISSING_FILE" ]; then
  missing="$(cut -d: -f1 "$MISSING_FILE" | sort -u | tr '\n' ' ' | sed 's/ *$//')"
  echo "tap-check.sh: TAP DRIFT — missing tap(s): $missing" >&2
  # Surface for the workflow summary (issue.sh attaches $MISSING_FILE itself).
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'missing_taps=%s\n' "$missing" >> "$GITHUB_OUTPUT"
  fi
  exit "$DRIFT_EXIT"
fi

echo "tap-check.sh: all taps present." >&2
