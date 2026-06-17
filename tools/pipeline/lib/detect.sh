#!/bin/sh
# =============================================================================
# detect.sh — upstream-change detector for the Pebble app fork pipeline.
#
# Realizes HLDD-004 §4 step "Detect (B)": resolve the upstream master HEAD via a
# cheap `git ls-remote` (no checkout) and compare it to the last-built SHA that
# the pipeline records on the `pipeline-state` branch at `tools/pipeline/state`.
#
# Decision (printed to stdout as `key=value` lines, GitHub-Actions friendly):
#   upstream_sha=<40-hex>     the current upstream master HEAD
#   last_built_sha=<40-hex|>  the previously built SHA ("" if first run)
#   changed=true|false        whether a build should proceed
#
# Exit code is always 0 on a successful probe; "no change" is communicated via
# `changed=false`, NOT via a non-zero exit, so the workflow can branch on it
# without `continue-on-error`. A genuine probe failure (network/git) exits 1.
#
# Usage:
#   detect.sh [--force]
#
# Environment / arguments:
#   UPSTREAM_URL    upstream git URL      (default: coredevices/mobileapp)
#   UPSTREAM_BRANCH upstream branch       (default: master)
#   STATE_FILE      path to the checked-out state file on the pipeline-state
#                   branch (default: tools/pipeline/state). May be absent on the
#                   very first run.
#   --force         treat as changed even if SHAs match (manual dispatch).
#
# Output sink:
#   If $GITHUB_OUTPUT is set, the key=value lines are also appended there so
#   downstream Actions steps can read them via `steps.<id>.outputs.<key>`.
# =============================================================================
set -eu

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/coredevices/mobileapp.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
STATE_FILE="${STATE_FILE:-tools/pipeline/state}"

force="false"
case "${1:-}" in
  --force) force="true" ;;
  "") : ;;
  *) echo "detect.sh: unknown argument '$1'" >&2; exit 2 ;;
esac
# A force flag may also arrive via the environment (workflow_dispatch input).
if [ "${FORCE:-false}" = "true" ]; then
  force="true"
fi

# --- Resolve the upstream HEAD SHA (no clone, just the ref advertisement). ----
# `git ls-remote <url> refs/heads/<branch>` prints "<sha>\t<ref>"; take field 1.
upstream_sha="$(git ls-remote "$UPSTREAM_URL" "refs/heads/$UPSTREAM_BRANCH" \
  | awk 'NR==1 {print $1}')"

if [ -z "$upstream_sha" ]; then
  echo "detect.sh: could not resolve $UPSTREAM_BRANCH on $UPSTREAM_URL" >&2
  exit 1
fi

# --- Read the last-built SHA from the (already checked-out) state file. -------
# The workflow checks out the `pipeline-state` branch into a path and points
# STATE_FILE at it. Absence == first run == always "changed".
last_built_sha=""
if [ -f "$STATE_FILE" ]; then
  # Tolerate trailing whitespace/newlines; take the first non-empty token.
  last_built_sha="$(awk 'NF {print $1; exit}' "$STATE_FILE")"
fi

# --- Decide. -----------------------------------------------------------------
changed="false"
if [ "$force" = "true" ]; then
  changed="true"
elif [ "$upstream_sha" != "$last_built_sha" ]; then
  changed="true"
fi

emit() {
  # $1=key $2=value — print to stdout and (if present) to $GITHUB_OUTPUT.
  printf '%s=%s\n' "$1" "$2"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
  fi
}

emit upstream_sha "$upstream_sha"
emit last_built_sha "$last_built_sha"
emit changed "$changed"

if [ "$changed" = "true" ]; then
  echo "detect.sh: upstream changed ($last_built_sha -> $upstream_sha); build will run." >&2
else
  echo "detect.sh: no upstream change ($upstream_sha); exiting quietly." >&2
fi
