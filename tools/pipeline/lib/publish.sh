#!/bin/sh
# =============================================================================
# publish.sh — publish the signed APK as a GitHub release and advance state.
#
# Realizes HLDD-004 §4 steps O & P:
#   * compute the APK .sha256 sidecar (§9: "Staged APKs carry SHA-256")
#   * assemble the upstream changelog range (last-built..new upstream SHA)
#   * create a GitHub release tagged  v<versionCode>-tasker.<N>  with the APK +
#     sha256 + changelog body, and tag the `tasker` branch so the nanogiants
#     versioning plugin resolves a real versionName (instead of "unknown")
#   * advance the last-built SHA on the `pipeline-state` branch
#
# Tag scheme (HLDD-004 §4 "Publish"): v<versionCode>-tasker.<N>
#   versionCode = upstream commit count (the nanogiants metric, §3)
#   N           = monotonic fork-build counter for this versionCode (so two
#                 builds of the same upstream commit, e.g. a re-run, stay unique)
#
# Uses the `gh` CLI (preinstalled on GitHub-hosted runners) with GITHUB_TOKEN.
# The branch pushes use force-with-lease so a concurrent change is never
# clobbered silently.
#
# Usage:
#   publish.sh <signed-apk> <version-code> <upstream-sha> <last-built-sha>
#
# Environment:
#   GITHUB_TOKEN        token with contents:write (release + tag pushes)
#   GH_REPO / GITHUB_REPOSITORY  owner/repo (gh reads either)
#   TASKER_BRANCH       our patch branch        (default: tasker)
#   STATE_BRANCH        state branch            (default: pipeline-state)
#   STATE_FILE          state path within repo  (default: tools/pipeline/state)
#   UPSTREAM_BRANCH     upstream branch name    (default: master)
#   PUSH_TASKER         "true" to force-with-lease push tasker (default: true)
#
# Exit codes:
#   0   release published + state advanced
#   51  publish failure (caller -> PUBLISH FAIL issue)
#   2   bad arguments
# =============================================================================
set -eu

APK="${1:-}"
VERSION_CODE="${2:-}"
UPSTREAM_SHA="${3:-}"
LAST_BUILT_SHA="${4:-}"
PUBLISH_EXIT=51

if [ -z "$APK" ] || [ -z "$VERSION_CODE" ] || [ -z "$UPSTREAM_SHA" ]; then
  echo "usage: publish.sh <signed-apk> <version-code> <upstream-sha> <last-built-sha>" >&2
  exit 2
fi
if [ ! -f "$APK" ]; then
  echo "publish.sh: APK not found: $APK" >&2
  exit 2
fi
: "${GITHUB_TOKEN:?publish.sh: GITHUB_TOKEN is required}"

TASKER_BRANCH="${TASKER_BRANCH:-tasker}"
STATE_BRANCH="${STATE_BRANCH:-pipeline-state}"
STATE_FILE="${STATE_FILE:-tools/pipeline/state}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
PUSH_TASKER="${PUSH_TASKER:-true}"

# gh reads GH_REPO; mirror GITHUB_REPOSITORY into it if needed.
if [ -z "${GH_REPO:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  GH_REPO="$GITHUB_REPOSITORY"
  export GH_REPO
fi

# --- 1. Compute the .sha256 sidecar. -----------------------------------------
SHA_FILE="${APK}.sha256"
echo "publish.sh: computing sha256 -> $SHA_FILE" >&2
# Print "<hash>  <basename>" (sha256sum's standard format) so it verifies with
# `sha256sum -c` from the download directory.
apk_dir="$(dirname "$APK")"
apk_base="$(basename "$APK")"
( cd "$apk_dir" && sha256sum "$apk_base" ) > "$SHA_FILE"

# --- 2. Determine the next fork-build counter N for this versionCode. ---------
# Look at existing release tags of the form v<versionCode>-tasker.<N> and pick
# max(N)+1; default to 1. Requires the tasker tags to be fetched (the workflow
# checks out full history). `git tag` listing is local; no network needed.
TAG_PREFIX="v${VERSION_CODE}-tasker."
next_n=1
existing="$(git tag --list "${TAG_PREFIX}*" 2>/dev/null || true)"
if [ -n "$existing" ]; then
  max_n="$(printf '%s\n' "$existing" \
    | sed "s/^${TAG_PREFIX}//" \
    | grep -E '^[0-9]+$' \
    | sort -n | tail -n1)"
  if [ -n "$max_n" ]; then
    next_n=$((max_n + 1))
  fi
fi
TAG="${TAG_PREFIX}${next_n}"
echo "publish.sh: release tag = $TAG" >&2

# --- 3. Build the changelog range (upstream commits since last build). --------
CHANGELOG_FILE="$(mktemp)"
{
  echo "Automated fork build: upstream \`$UPSTREAM_BRANCH\` + \`$TASKER_BRANCH\` patches."
  echo
  echo "- Upstream HEAD: \`$UPSTREAM_SHA\`"
  echo "- versionCode: \`$VERSION_CODE\`"
  echo
  echo "### Upstream changes since last build"
  echo
  if [ -n "$LAST_BUILT_SHA" ] && git cat-file -e "${LAST_BUILT_SHA}^{commit}" 2>/dev/null; then
    # One line per upstream commit in the range; fall back gracefully.
    if ! git log --oneline --no-merges "${LAST_BUILT_SHA}..${UPSTREAM_SHA}" 2>/dev/null; then
      echo "_(range ${LAST_BUILT_SHA}..${UPSTREAM_SHA} unavailable)_"
    fi
  else
    echo "_First tracked build (no previous SHA); full range unavailable._"
  fi
  echo
  echo "---"
  echo "SHA-256:"
  echo '```'
  cat "$SHA_FILE"
  echo '```'
} > "$CHANGELOG_FILE"

# --- 4. Tag the tasker branch so versionName resolves, and push it. ----------
# The tag must point at the rebased tasker HEAD (current checkout).
echo "publish.sh: tagging $TASKER_BRANCH as $TAG" >&2
git tag -f "$TAG"

if [ "$PUSH_TASKER" = "true" ]; then
  echo "publish.sh: pushing $TASKER_BRANCH (force-with-lease) + tag $TAG" >&2
  # Force-with-lease: refuses if the remote moved unexpectedly (§4 step O).
  git push --force-with-lease origin "HEAD:refs/heads/${TASKER_BRANCH}" \
    || { echo "publish.sh: failed to push $TASKER_BRANCH" >&2; exit "$PUBLISH_EXIT"; }
fi
git push -f origin "refs/tags/${TAG}" \
  || { echo "publish.sh: failed to push tag $TAG" >&2; exit "$PUBLISH_EXIT"; }

# --- 5. Create the GitHub release with the APK + sha256 sidecar. --------------
echo "publish.sh: creating GitHub release $TAG" >&2
gh release create "$TAG" \
  "$APK" \
  "$SHA_FILE" \
  --title "$TAG" \
  --notes-file "$CHANGELOG_FILE" \
  --target "$TASKER_BRANCH" \
  || { echo "publish.sh: gh release create failed" >&2; exit "$PUBLISH_EXIT"; }

rm -f "$CHANGELOG_FILE"

# --- 6. Advance the last-built SHA on the pipeline-state branch. --------------
# Done LAST: if anything above failed we did NOT advance state, so the next
# cron tick retries (HLDD-004 §7 "Idle = the last-built SHA is not advanced").
echo "publish.sh: advancing $STATE_FILE on $STATE_BRANCH -> $UPSTREAM_SHA" >&2
STATE_WT="$(mktemp -d)"
# Work in a throwaway worktree so we never disturb the rebased tasker checkout.
# Ask the remote directly whether the branch exists (the build job's checkout
# may not have a populated origin/<state> remote-tracking ref).
if git ls-remote --exit-code --heads origin "$STATE_BRANCH" >/dev/null 2>&1; then
  git fetch --no-tags origin "$STATE_BRANCH"
  git worktree add --force "$STATE_WT" "FETCH_HEAD" >/dev/null 2>&1 \
    || git worktree add --force --detach "$STATE_WT" "origin/${STATE_BRANCH}"
else
  # Branch doesn't exist yet — create an orphan in the worktree.
  git worktree add --force --detach "$STATE_WT" >/dev/null 2>&1
  ( cd "$STATE_WT" && git checkout --orphan "$STATE_BRANCH" && git rm -rf . >/dev/null 2>&1 || true )
fi

(
  cd "$STATE_WT"
  mkdir -p "$(dirname "$STATE_FILE")"
  printf '%s\n' "$UPSTREAM_SHA" > "$STATE_FILE"
  git add "$STATE_FILE"
  git -c user.name="pipeline-bot" \
      -c user.email="pipeline-bot@users.noreply.github.com" \
      commit -m "pipeline: built upstream $UPSTREAM_SHA as $TAG" \
    || echo "publish.sh: state unchanged (nothing to commit)" >&2
  git push origin "HEAD:refs/heads/${STATE_BRANCH}"
) || { echo "publish.sh: failed to advance state branch" >&2; \
       git worktree remove --force "$STATE_WT" 2>/dev/null || true; exit "$PUBLISH_EXIT"; }

git worktree remove --force "$STATE_WT" 2>/dev/null || true

echo "publish.sh: published $TAG and advanced state to $UPSTREAM_SHA" >&2
# Surface the tag for the workflow summary.
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'tag=%s\n' "$TAG" >> "$GITHUB_OUTPUT"
fi
