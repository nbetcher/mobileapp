#!/bin/sh
# =============================================================================
# compile-gate.sh — compile every module the fork patches, without packaging.
#
# Used by fork-sync.yml right after the merge, so an upstream API change that
# merged cleanly but breaks the fork fails the sync instead of the build.
#
# Usage:
#   compile-gate.sh <log-file>
#
# Environment:
#   COMPILE_GATE_TASKS  override the Gradle tasks to run
#
# Exit status is Gradle's. The full log is written to <log-file>.
# =============================================================================
set -eu

LOG="${1:?usage: compile-gate.sh <log-file>}"
TASKS="${COMPILE_GATE_TASKS:-:tasker-bridge:compileReleaseKotlin :tasker-bridge:compileDebugUnitTestKotlin :composeApp:compileAndroidMain}"

rc=0
# shellcheck disable=SC2086
./gradlew --no-daemon --continue $TASKS > "$LOG" 2>&1 || rc=$?
grep -E '^e: |FAILED$|BUILD (SUCCESSFUL|FAILED)' "$LOG" | sort -u || true
exit "$rc"
