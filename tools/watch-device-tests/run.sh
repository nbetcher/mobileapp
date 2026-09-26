#!/usr/bin/env bash
# Runs the on-watch automation tests (button presses, touch, app close, time sync, ...).
#
# Builds the debug Pebble app and its test APK, installs both over the existing install with
# `adb install -r` (watch pairings and app data are kept), then runs WatchControlDeviceTest inside
# the Pebble app against the connected watch. Same behaviour as run.ps1.
#
# Needs one phone on adb with the debug Pebble app installed and a paired watch nearby. The tests
# press buttons on the watch, briefly toggle Quiet Time, and launch and close an app. A factory
# reset is never run; a watch reboot runs only with --reboot.
#
# DEFERRED means the watch cannot run that test (older firmware, no touch screen) or its outcome
# could not be observed automatically; check it by hand.
#
# Usage: tools/watch-device-tests/run.sh [--device SERIAL] [--watch SERIAL] [--app-uuid UUID] [--reboot] [--skip-build]
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
test_class=coredevices.coreapp.automation.WatchControlDeviceTest
runner=coredevices.coreapp.test/androidx.test.runner.AndroidJUnitRunner
device="" watch="" app_uuid="" reboot=false skip_build=false
while [ $# -gt 0 ]; do
  case "$1" in
    --device) device="$2"; shift 2 ;;
    --watch) watch="$2"; shift 2 ;;
    --app-uuid) app_uuid="$2"; shift 2 ;;
    --reboot) reboot=true; shift ;;
    --skip-build) skip_build=true; shift ;;
    *) echo "unknown option $1" >&2; exit 64 ;;
  esac
done

command -v adb >/dev/null || { echo "adb is not on PATH (Android SDK platform-tools)." >&2; exit 2; }
adb_cmd=(adb); [ -n "$device" ] && adb_cmd+=(-s "$device")
if [ -z "$device" ] && [ "$(adb devices | tail -n +2 | grep -c $'\tdevice$')" -ne 1 ]; then
  echo "Expected exactly one phone on adb; pass --device SERIAL." >&2; exit 2
fi

if ! $skip_build; then
  "$root/gradlew" -p "$root" :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
fi

install_apk() {
  local apk
  apk="$(ls -t $1 2>/dev/null | head -1)"
  [ -n "$apk" ] || { echo "No $2 APK at $1; run without --skip-build." >&2; exit 2; }
  local out; out="$("${adb_cmd[@]}" install -r -t "$apk" 2>&1 || true)"
  if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
    echo "The installed Pebble app is signed with a different key (e.g. a store build). Replacing it means" >&2
    echo "uninstalling, which removes watch pairings; do that yourself if you accept it, then pair again and rerun." >&2
    exit 2
  fi
  grep -q Success <<<"$out" || { echo "Installing the $2 APK failed:" >&2; echo "$out" >&2; exit 2; }
  echo "Installed $2 APK: $(basename "$apk")"
}
install_apk "$root/androidApp/build/outputs/apk/debug/*.apk" app
install_apk "$root/androidApp/build/outputs/apk/androidTest/debug/*.apk" test

args=(-e class "$test_class")
[ -n "$watch" ] && args+=(-e watch "$watch")
[ -n "$app_uuid" ] && args+=(-e app_uuid "$app_uuid")
$reboot && args+=(-e reboot true)

echo "Running on the watch (the Pebble app restarts and reconnects first)..."
log="$root/build/watch-device-tests.log"
mkdir -p "$(dirname "$log")"
"${adb_cmd[@]}" shell am instrument -w -r "${args[@]}" "$runner" 2>&1 | tr -d '\r' > "$log" || true

# `am instrument -r` prints key=value status blocks, each closed by a status code:
# 1 started, 0 passed, -1 error, -2 failed, -3 ignored, -4 assumption failed (deferred).
awk '
  /^INSTRUMENTATION_STATUS: / {
    kv = substr($0, 25); key = substr(kv, 1, index(kv, "=") - 1); val = substr(kv, index(kv, "=") + 1)
    f[key] = val; last = key; next
  }
  /^INSTRUMENTATION_STATUS_CODE: / {
    code = $2 + 0
    if (code != 1 && f["test"] != "") {
      res = code == 0 ? "PASS" : ((code == -3 || code == -4) ? "DEFERRED" : "FAIL")
      detail = f["stack"]; sub(/^[A-Za-z0-9_.$]+(Exception|Error): /, "", detail)
      printf "%-8s %-60s %s\n", res, f["test"], detail
      count[res]++
    }
    delete f; last = ""; next
  }
  /^INSTRUMENTATION_/ { if ($0 ~ /shortMsg=|INSTRUMENTATION_FAILED/) crash = crash " " $0; last = ""; next }
  END {
    printf "Passed %d, failed %d, deferred %d. Full output: %s\n", count["PASS"], count["FAIL"], count["DEFERRED"], LOG
    if (crash != "") { print "Run aborted:" crash; exit 2 }
    if (count["PASS"] + count["FAIL"] + count["DEFERRED"] == 0) { print "No test results; see the log."; exit 2 }
    if (count["FAIL"] > 0) exit 1
  }
' LOG="$log" "$log"
