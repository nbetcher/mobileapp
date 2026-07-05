#!/bin/sh
# =============================================================================
# rebase.sh — re-apply the fork's `tasker` patch set onto upstream/master.
#
# Realizes HLDD-004 §4 step "Rebase (D)" and §5 "Known conflicts" layer:
#   1. Restore the committed git-rerere cache (tools/pipeline/rr-cache/) into
#      .git/rr-cache so previously recorded conflict resolutions replay
#      automatically.
#   2. Add/refresh the `upstream` remote and fetch the target branch.
#   3. Rebase `tasker` onto upstream/master with rerere enabled.
#   4. On a clean rebase: export any freshly recorded resolutions back out to
#      the in-repo cache (so a brand-new auto-resolution is captured) and exit 0.
#      On conflict: abort the rebase and exit 23 (the caller maps this to a
#      CONFLICT issue, §7) — the working tree is left clean.
#
# This script is deliberately read-only with respect to history pushing: it
# rebases locally only. The workflow pushes `tasker` (force-with-lease) later,
# AFTER build+sign+smoke pass (HLDD-004 §4 step O), so a failed later stage
# never leaves a rewritten branch published.
#
# Usage:
#   rebase.sh
#
# Environment:
#   UPSTREAM_URL    upstream git URL   (default: coredevices/mobileapp)
#   UPSTREAM_BRANCH upstream branch    (default: master)
#   TASKER_BRANCH   our patch branch   (default: tasker)
#   REBASE_ONTO     ref to rebase onto (default: upstream/<branch>; the workflow
#                   sets this to `release-track` = the current release cutoff so
#                   we track the shipped store version, not bleeding-edge master)
#   RR_CACHE_DIR    committed rerere cache (default: tools/pipeline/rr-cache)
#   GIT_DIR         git dir            (default: .git)
#
# Exit codes:
#   0   rebase clean (patches re-applied)
#   23  rebase hit an unresolved conflict (caller -> CONFLICT issue)
#   1   any other (setup / fetch) failure
# =============================================================================
set -eu

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/coredevices/mobileapp.git}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
TASKER_BRANCH="${TASKER_BRANCH:-tasker}"
RR_CACHE_DIR="${RR_CACHE_DIR:-tools/pipeline/rr-cache}"
GIT_DIR="${GIT_DIR:-.git}"

CONFLICT_EXIT=23

# --- 1. Restore the committed rerere cache into .git/rr-cache. ----------------
# rerere stores one directory per recorded conflict under .git/rr-cache. We keep
# a copy committed in-repo (RR_CACHE_DIR) so the ephemeral runner replays them.
echo "rebase.sh: restoring rerere cache from $RR_CACHE_DIR" >&2
mkdir -p "$GIT_DIR/rr-cache"
if [ -d "$RR_CACHE_DIR" ]; then
  # Copy contents (not the dir itself). The trailing /. copies hidden files too.
  # `.gitkeep` (if present) is harmless inside rr-cache.
  if [ -n "$(ls -A "$RR_CACHE_DIR" 2>/dev/null || true)" ]; then
    cp -R "$RR_CACHE_DIR/." "$GIT_DIR/rr-cache/"
  fi
fi

# Make sure rerere is on for this repo regardless of global config.
git config rerere.enabled true
git config rerere.autoupdate true

# --- 2. Wire up the upstream remote and fetch. --------------------------------
if git remote get-url upstream >/dev/null 2>&1; then
  git remote set-url upstream "$UPSTREAM_URL"
else
  git remote add upstream "$UPSTREAM_URL"
fi
echo "rebase.sh: fetching upstream/$UPSTREAM_BRANCH" >&2
git fetch --no-tags upstream "$UPSTREAM_BRANCH"

# --- 3. Check out tasker and rebase onto upstream/master. ---------------------
git checkout "$TASKER_BRANCH"

# Rebase onto the release cutoff (release-track) when the caller set REBASE_ONTO, else upstream/master.
REBASE_ONTO="${REBASE_ONTO:-upstream/$UPSTREAM_BRANCH}"
echo "rebase.sh: rebasing $TASKER_BRANCH onto $REBASE_ONTO" >&2
# rerere.enabled is forced on via -c too, belt-and-braces with the config above.
rc=0
git -c rerere.enabled=true rebase "$REBASE_ONTO" || rc=$?

# --- helper: copy any (re)recorded resolutions back into the in-repo cache. ---
sync_cache_out() {
  if [ -d "$GIT_DIR/rr-cache" ] && \
     [ -n "$(ls -A "$GIT_DIR/rr-cache" 2>/dev/null || true)" ]; then
    mkdir -p "$RR_CACHE_DIR"
    cp -R "$GIT_DIR/rr-cache/." "$RR_CACHE_DIR/"
    echo "rebase.sh: synced rerere cache out to $RR_CACHE_DIR" >&2
  fi
}

if [ "$rc" -eq 0 ]; then
  # Clean (possibly via replayed rerere resolutions).
  sync_cache_out
  echo "rebase.sh: rebase clean." >&2
  exit 0
fi

# --- 4. Conflict path. -------------------------------------------------------
# rerere may have *recorded* (but not fully resolved) a new conflict; sync it out
# to the in-repo cache so the workflow can upload it as an artifact (the ephemeral
# runner can't commit it) for the maintainer to commit after resolving locally
# (runbook §8 step 4); then abort to leave a clean tree.
echo "rebase.sh: rebase hit conflicts; aborting." >&2
sync_cache_out

# Capture the conflicting files + the upstream commit range for the issue body.
# These are written to files the caller can attach (best-effort; never fatal).
git diff --name-only --diff-filter=U > /tmp/pipeline-conflict-files.txt 2>/dev/null || true
# Range = what upstream added since our merge-base with tasker.
if base="$(git merge-base "$TASKER_BRANCH" "$REBASE_ONTO" 2>/dev/null)"; then
  git log --oneline "$base..$REBASE_ONTO" \
    > /tmp/pipeline-upstream-range.txt 2>/dev/null || true
fi

git rebase --abort || true
exit "$CONFLICT_EXIT"
