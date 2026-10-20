#!/bin/sh
# plugin-readiness.sh: read-only go-signal probe for de-forking the Tasker bridge.
# No checkout, no writes (except $GITHUB_OUTPUT when set). Requires: git, curl; gh optional.
# Usage: REPO=<fork clone with `upstream` fetched> UPSTREAM_REF=upstream/master sh plugin-readiness.sh
set -eu
# k5 hashes `sort -u` output, whose order is locale-dependent.
export LC_ALL=C
REPO="${REPO:-.}"
REF="${UPSTREAM_REF:-upstream/master}"
LP=libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon
LPA=libpebble3/src/androidMain/kotlin/io/rebble/libpebblecommon
PK2_RAW=https://raw.githubusercontent.com/pebble-dev/PebbleKitAndroid2
show() { git -C "$REPO" show "$REF:$1" 2>/dev/null || true; }
out()  { printf '%s=%s\n' "$1" "$2"; if [ -n "${GITHUB_OUTPUT:-}" ]; then printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"; fi; }
yn()   { if eval "$2" >/dev/null 2>&1; then out "$1" true; else out "$1" false; fi; }
cnt()  { grep -cE "$1" || true; }

# ---- Plugin-API route (baselines measured 2026-10-20 on 531b6c9c) --------------------------------
yn  p1_default_on          "show $LP/LibPebbleConfig.kt | grep -q 'enablePlugins: Boolean = true'"           # false
yn  p2_toggle_in_debug     "show pebble/src/commonMain/kotlin/coredevices/pebble/ui/WatchSettingsScreen.kt | grep -A15 'Use experimental plugins' | grep -q 'isDebugSetting = true'"  # true
yn  p3_dont_ship           "show test-apps/plugins.md | grep -q \"Don't ship anything\""                     # true
yn  p3_testapps_present    "git -C '$REPO' cat-file -e '$REF:test-apps/README.md'"                           # true
out p4_caller_kinds        "$(show $LP/plugin/PluginClient.kt | sed -n '/sealed interface PluginCaller/,/^}/p' | cnt ': PluginCaller')"  # 2
out p4_aidl_files          "$(git -C "$REPO" ls-tree -r --name-only "$REF" | cnt '\.aidl$')"                # 0
yn  p4_future_bullet       "show test-apps/plugins.md | grep -q 'PebbleKit-Android plugin support'"         # true (advisory: Future list is stale)
out p5_builtin_plugins     "$(git -C "$REPO" grep -lE '\) *: *NativePlugin' "$REF" -- 'libpebble3/src/*Main/*' 'pebble/src/*Main/*' 2>/dev/null | grep -vc Test || true)"  # 7
yn  p5_watchstate_no_src   "show $LP/plugin/WatchStatePlugin.kt | grep -q 'sources: List<SourceDeclaration> = emptyList()'"  # true
out p9_request_timeout     "$(show $LP/plugin/JsPlugin.kt | grep -oE 'REQUEST_TIMEOUT *= *[0-9]+\.[a-z]+' | head -1 | tr -d ' ')"  # REQUEST_TIMEOUT=20.seconds

# ---- PebbleKit 2 route, host side ----------------------------------------------------------------
out k1_pk2_pin             "$(show gradle/libs.versions.toml | grep -E '^pebblekit *= *"' | tr -d ' "')"  # pebblekit=1.3.0
out k2_host_overrides      "$(show $LPA/pebblekit/two/PebbleSenderReceiver.kt | cnt 'override suspend fun')"  # 5
out k2_host_pk2_files      "$(git -C "$REPO" ls-tree --name-only "$REF" $LPA/pebblekit/two/ | cnt '\.kt$')"  # 5
yn  k3_pin_actions_mapped  "show $LPA/pebblekit/two/PebbleSenderReceiver.kt | sed -n '/fun TimelinePin.toPinJson/,/^}/p' | grep -q actions"  # false
yn  k4_classic_start_timeout "show $LPA/pebblekit/classic/io/rebble/libpebblecommon/pebblekit/classic/PebbleKitClassicStartListeners.kt | grep -q withTimeout"  # false
out k5_exported_sha        "$(git -C "$REPO" ls-tree -r --name-only "$REF" | grep -E '/src/[^/]+/AndroidManifest\.xml$' | sort | while read -r m; do show "$m"; done \
  | tr '\n' ' ' | grep -oE '<[a-z-]+ [^>]*android:exported="true"[^>]*>' | grep -oE 'android:name="[^"]*"' | sort -u | sha256sum | cut -c1-16)"  # ae19e754a70b92d0

# ---- PebbleKit 2 route, library side (latest tag of pebble-dev/PebbleKitAndroid2) ----------------
TAG="$(git ls-remote --tags https://github.com/pebble-dev/PebbleKitAndroid2.git | sed 's@.*refs/tags/@@' | grep -v '\^{}' | sort -V | tail -1)"
out l0_pk2_latest_tag "$TAG"                                                                                 # 1.3.2
pk2() { curl -fsSL "$PK2_RAW/$TAG/$1" 2>/dev/null || true; }
KEYS="$(pk2 common/src/main/kotlin/io/rebble/pebblekit2/PebbleKitBundleKeys.kt)"
CONN="$(pk2 server-api/src/main/kotlin/io/rebble/pebblekit2/server/PebbleListenerConnector.kt)"
PROV="$(pk2 common/src/main/kotlin/io/rebble/pebblekit2/PebbleKitProviderContract.kt)"
out l1_bundle_actions      "$(printf '%s\n' "$KEYS" | cnt 'const val ACTION_')"                             # 10 (5 requests + 5 events)
out l2_listener_events     "$(printf '%s\n' "$CONN" | cnt 'suspend fun send')"                              # 5
out l2_events_keyed_to_app "$(printf '%s\n' "$CONN" | cnt 'watchappUUID: UUID')"                            # 5 (equal => every event is companion-scoped)
out l3_provider_consts     "$(printf '%s\n' "$PROV" | cnt 'const val [A-Z_]+: (String|Int) =')"              # 16
yn  l3_battery_column      "printf '%s\n' \"\$PROV\" | grep -qiE 'const val [A-Z_]*BATTERY'"               # false
yn  l3_firmware_status_col "printf '%s\n' \"\$PROV\" | grep -qiE 'const val [A-Z_]*(UPDATE|FIRMWARE_STATUS)'"  # false

# ---- Upstream engagement (needs gh + token) ------------------------------------------------------
if command -v gh >/dev/null 2>&1; then
  for n in 255 293; do out "g11_issue_$n" "$(gh issue view $n -R coredevices/mobileapp --json state,comments --jq '.state + "/" + (.comments|length|tostring)' 2>/dev/null || echo NA)"; done  # OPEN/0 both
  for n in 171 172 395; do out "g11_pr_$n" "$(gh pr view $n -R coredevices/mobileapp --json state --jq .state 2>/dev/null || echo NA)"; done  # OPEN all three
fi
