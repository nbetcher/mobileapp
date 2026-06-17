#!/bin/sh
# =============================================================================
# sign.sh — sign + verify the release APK with the shared tasker-plugin key.
#
# Realizes HLDD-004 §4 step "Sign (L)" and §9 "Security & supply chain".
#
# The shared keystore is `tasker-plugin.p12` (ADR-009), delivered to the runner
# only as the encrypted Actions secret APP_KEYSTORE_B64 (base64 of the .p12).
# We decode it to a tmpfile, `zipalign` the APK (alignment must precede v2/v3
# signing), `apksigner sign` it, then prove the result with `apksigner verify`
# and `zipalign -c`. The decoded keystore is shredded on exit (trap), never
# logged, never committed (§3 of the HLDD: "decoded onto the ephemeral runner at
# sign time, never logged or committed").
#
# NOTE on the build's own signingConfig: the app's release signingConfig already
# reads `<repo>/keystore.jks` + RELEASE_KEYSTORE_* env (composeApp/build.gradle
# .kts), so the zero-patch alternative is to let Gradle sign during
# assembleRelease. This script performs an explicit, independent apksigner pass
# as the design doc specifies (step L: "sign (apksigner) ... verify signature +
# alignment"), which also works when Gradle emits an unsigned release APK. It is
# idempotent: signing an already-signed APK simply re-signs it.
#
# Usage:
#   sign.sh <in-apk> <out-apk>
#
# Environment (all required except *_PASSWORD aliases):
#   APP_KEYSTORE_B64        base64 of tasker-plugin.p12
#   APP_KEYSTORE_PASSWORD   keystore (store) password
#   APP_KEY_ALIAS           key alias inside the keystore
#   APP_KEY_PASSWORD        key password (defaults to APP_KEYSTORE_PASSWORD)
#   ANDROID_BUILD_TOOLS     dir containing apksigner/zipalign. If unset we try
#                           $ANDROID_HOME/build-tools/<latest> then PATH.
#   MIN_SDK                 minSdk for apksigner --min-sdk-version (default 26)
#
# Exit codes:
#   0   signed + verified + aligned
#   41  sign / verify / align failure (caller -> SIGN FAIL issue)
#   2   bad arguments / missing secrets
# =============================================================================
set -eu

IN_APK="${1:-}"
OUT_APK="${2:-}"
SIGN_EXIT=41

if [ -z "$IN_APK" ] || [ -z "$OUT_APK" ]; then
  echo "usage: sign.sh <in-apk> <out-apk>" >&2
  exit 2
fi
if [ ! -f "$IN_APK" ]; then
  echo "sign.sh: input APK not found: $IN_APK" >&2
  exit 2
fi

: "${APP_KEYSTORE_B64:?sign.sh: APP_KEYSTORE_B64 is required}"
: "${APP_KEYSTORE_PASSWORD:?sign.sh: APP_KEYSTORE_PASSWORD is required}"
: "${APP_KEY_ALIAS:?sign.sh: APP_KEY_ALIAS is required}"
APP_KEY_PASSWORD="${APP_KEY_PASSWORD:-$APP_KEYSTORE_PASSWORD}"
MIN_SDK="${MIN_SDK:-26}"

# --- Locate build-tools (apksigner, zipalign). -------------------------------
find_build_tools() {
  if [ -n "${ANDROID_BUILD_TOOLS:-}" ] && [ -x "$ANDROID_BUILD_TOOLS/apksigner" ]; then
    echo "$ANDROID_BUILD_TOOLS"; return 0
  fi
  for home in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$home" ] || continue
    if [ -d "$home/build-tools" ]; then
      # Pick the highest-versioned build-tools dir.
      latest="$(ls -1 "$home/build-tools" 2>/dev/null | sort -V | tail -n1)"
      if [ -n "$latest" ] && [ -x "$home/build-tools/$latest/apksigner" ]; then
        echo "$home/build-tools/$latest"; return 0
      fi
    fi
  done
  return 1
}

BT="$(find_build_tools || true)"
if [ -n "$BT" ]; then
  APKSIGNER="$BT/apksigner"
  ZIPALIGN="$BT/zipalign"
else
  # Fall back to PATH (android-actions/setup-android puts them there).
  APKSIGNER="$(command -v apksigner || true)"
  ZIPALIGN="$(command -v zipalign || true)"
fi
if [ -z "${APKSIGNER:-}" ] || [ ! -x "$APKSIGNER" ]; then
  echo "sign.sh: apksigner not found (set ANDROID_BUILD_TOOLS or ANDROID_HOME)" >&2
  exit "$SIGN_EXIT"
fi
if [ -z "${ZIPALIGN:-}" ] || [ ! -x "$ZIPALIGN" ]; then
  echo "sign.sh: zipalign not found (set ANDROID_BUILD_TOOLS or ANDROID_HOME)" >&2
  exit "$SIGN_EXIT"
fi

# --- Decode keystore to a private tmpfile; shred on exit. --------------------
KS_FILE="$(mktemp)"
ALIGNED_APK="$(mktemp -u).apk"
cleanup() {
  # Best-effort secure delete of the decoded keystore.
  if [ -f "$KS_FILE" ]; then
    if command -v shred >/dev/null 2>&1; then
      shred -u "$KS_FILE" 2>/dev/null || rm -f "$KS_FILE"
    else
      rm -f "$KS_FILE"
    fi
  fi
  rm -f "$ALIGNED_APK"
}
trap cleanup EXIT INT TERM

# Decode. `base64 -d` is the GNU/coreutils form present on ubuntu-latest.
printf '%s' "$APP_KEYSTORE_B64" | base64 -d > "$KS_FILE" 2>/dev/null \
  || { echo "sign.sh: failed to base64-decode APP_KEYSTORE_B64" >&2; exit "$SIGN_EXIT"; }
chmod 600 "$KS_FILE"

# --- 1. Align BEFORE signing (apksigner requires pre-aligned input for v2+). -
echo "sign.sh: zipalign -p 4 $IN_APK" >&2
"$ZIPALIGN" -p -f 4 "$IN_APK" "$ALIGNED_APK" \
  || { echo "sign.sh: zipalign failed" >&2; exit "$SIGN_EXIT"; }

# --- 2. Sign (.p12 keystore type is PKCS12). ---------------------------------
# Passwords are passed via env: pass:env:VAR so they never appear in argv/ps.
echo "sign.sh: apksigner sign -> $OUT_APK" >&2
KS_PASS_ENV="APP_KEYSTORE_PASSWORD"
KEY_PASS_ENV="APP_KEY_PASSWORD"
export APP_KEYSTORE_PASSWORD APP_KEY_PASSWORD
"$APKSIGNER" sign \
  --ks "$KS_FILE" \
  --ks-type PKCS12 \
  --ks-key-alias "$APP_KEY_ALIAS" \
  --ks-pass "env:$KS_PASS_ENV" \
  --key-pass "env:$KEY_PASS_ENV" \
  --min-sdk-version "$MIN_SDK" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT_APK" \
  "$ALIGNED_APK" \
  || { echo "sign.sh: apksigner sign failed" >&2; exit "$SIGN_EXIT"; }

# --- 3. Verify signature. ----------------------------------------------------
echo "sign.sh: apksigner verify" >&2
"$APKSIGNER" verify --min-sdk-version "$MIN_SDK" --verbose "$OUT_APK" \
  || { echo "sign.sh: apksigner verify failed" >&2; exit "$SIGN_EXIT"; }

# --- 4. Confirm alignment of the signed APK. ---------------------------------
echo "sign.sh: zipalign -c 4 (verify alignment)" >&2
"$ZIPALIGN" -c 4 "$OUT_APK" \
  || { echo "sign.sh: zipalign -c (alignment check) failed" >&2; exit "$SIGN_EXIT"; }

echo "sign.sh: signed + verified + aligned -> $OUT_APK" >&2
