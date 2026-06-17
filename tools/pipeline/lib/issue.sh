#!/bin/sh
# =============================================================================
# issue.sh — open (or update) the owner-assigned failure issue for the pipeline.
#
# Realizes HLDD-004 §4 failure nodes (F, F2, F3, F4) and §7 "Failure handling".
# Every failure path in the workflow calls this helper; it rides the GitHub
# Android app's participating-notifications channel (assigned issue => phone
# push), so no extra notification infra is needed.
#
# Behaviour:
#   * De-duplicates: if an OPEN issue with the same `pipeline` label and the
#     same failure KIND already exists, it adds a comment to that issue instead
#     of opening a noisy duplicate (the pipeline idles and retries each tick;
#     we don't want one issue per tick).
#   * Always labels `pipeline` (+ a kind label) and assigns the owner so the
#     phone push fires.
#   * Attaches the upstream commit range and any captured context files written
#     by the earlier steps (conflict file list, tap drift list, logs).
#
# This script NEVER changes exit status of the run on its own — it returns 0 if
# the issue was filed (so the workflow's own `exit 1` is what fails the run).
# If issue filing itself fails, it returns non-zero so the operator notices.
#
# Usage:
#   issue.sh <kind> <title> [body-file]
#     kind      one of: CONFLICT | TAP-DRIFT | BUILD-FAIL | SMOKE-FAIL |
#               SIGN-FAIL | PUBLISH-FAIL  (free-form also accepted)
#     title     short human title
#     body-file optional path to a file whose contents become the issue body;
#               if omitted, a body is synthesized from environment context.
#
# Environment:
#   GITHUB_TOKEN          token with issues:write
#   GH_REPO / GITHUB_REPOSITORY   owner/repo
#   PIPELINE_ASSIGNEE     GitHub login to assign (default: @me -> token owner)
#   PIPELINE_LABEL        base label (default: pipeline)
#   UPSTREAM_SHA, LAST_BUILT_SHA, RUN_URL  woven into the synthesized body
#
# Exit codes:
#   0   issue filed or commented
#   1   issue filing failed
#   2   bad arguments
# =============================================================================
set -eu

KIND="${1:-}"
TITLE="${2:-}"
BODY_FILE="${3:-}"

if [ -z "$KIND" ] || [ -z "$TITLE" ]; then
  echo "usage: issue.sh <kind> <title> [body-file]" >&2
  exit 2
fi
: "${GITHUB_TOKEN:?issue.sh: GITHUB_TOKEN is required}"

if [ -z "${GH_REPO:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  GH_REPO="$GITHUB_REPOSITORY"; export GH_REPO
fi
PIPELINE_LABEL="${PIPELINE_LABEL:-pipeline}"
KIND_LABEL="pipeline:$(printf '%s' "$KIND" | tr '[:upper:] ' '[:lower:]-')"
ASSIGNEE="${PIPELINE_ASSIGNEE:-@me}"
RUN_URL="${RUN_URL:-${GITHUB_SERVER_URL:-https://github.com}/${GH_REPO:-}/actions/runs/${GITHUB_RUN_ID:-}}"

# --- Ensure labels exist (id-empotent; ignore "already exists"). -------------
ensure_label() {
  # $1=name $2=color $3=description
  gh label create "$1" --color "$2" --description "$3" >/dev/null 2>&1 || true
}
ensure_label "$PIPELINE_LABEL" "B60205" "Upstream-sync release pipeline"
ensure_label "$KIND_LABEL"    "D93F0B" "Pipeline failure: $KIND"

# --- Compose the body. -------------------------------------------------------
BODY_TMP="$(mktemp)"
if [ -n "$BODY_FILE" ] && [ -f "$BODY_FILE" ]; then
  cat "$BODY_FILE" > "$BODY_TMP"
else
  {
    echo "**Pipeline failure:** \`$KIND\`"
    echo
    [ -n "${UPSTREAM_SHA:-}" ]   && echo "- Upstream HEAD: \`$UPSTREAM_SHA\`"
    [ -n "${LAST_BUILT_SHA:-}" ] && echo "- Last built: \`$LAST_BUILT_SHA\`"
    echo "- Run: $RUN_URL"
    echo
  } > "$BODY_TMP"
fi

# Append any captured context the earlier steps left behind (best-effort).
append_ctx() {
  # $1=label $2=file
  if [ -f "$2" ] && [ -s "$2" ]; then
    {
      echo
      echo "### $1"
      echo '```'
      cat "$2"
      echo '```'
    } >> "$BODY_TMP"
  fi
}
append_ctx "Conflicting files"        /tmp/pipeline-conflict-files.txt
append_ctx "Upstream commit range"    /tmp/pipeline-upstream-range.txt
append_ctx "Missing taps"             /tmp/pipeline-tap-missing.txt

# Always end with the runbook pointer (§8).
{
  echo
  echo "_Resolution runbook: \`docs/runbooks/conflict-resolution.md\`._"
  echo "_The pipeline is idle (last-built SHA not advanced) and will retry each tick until this is resolved; re-run with \`workflow_dispatch force=true\` after fixing._"
} >> "$BODY_TMP"

# --- De-dupe: look for an existing OPEN issue of the same kind. ---------------
# Search by the kind label; if one is open, comment instead of re-filing.
existing="$(gh issue list \
  --state open \
  --label "$PIPELINE_LABEL" \
  --label "$KIND_LABEL" \
  --json number \
  --jq '.[0].number' 2>/dev/null || true)"

if [ -n "$existing" ] && [ "$existing" != "null" ]; then
  echo "issue.sh: existing open #$existing ($KIND); adding a comment." >&2
  gh issue comment "$existing" --body-file "$BODY_TMP" \
    || { echo "issue.sh: failed to comment on #$existing" >&2; rm -f "$BODY_TMP"; exit 1; }
  rm -f "$BODY_TMP"
  exit 0
fi

# --- Otherwise open a fresh issue, labeled + assigned (=> phone push). --------
echo "issue.sh: opening new $KIND issue, assigned to $ASSIGNEE" >&2
gh issue create \
  --title "[pipeline] $TITLE" \
  --body-file "$BODY_TMP" \
  --label "$PIPELINE_LABEL" \
  --label "$KIND_LABEL" \
  --assignee "$ASSIGNEE" \
  || { echo "issue.sh: gh issue create failed" >&2; rm -f "$BODY_TMP"; exit 1; }

rm -f "$BODY_TMP"
echo "issue.sh: issue filed." >&2
