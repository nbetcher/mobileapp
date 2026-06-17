# Upstream-sync & release pipeline

Automated release pipeline for the **Pebble app fork** (`coredevices.coreapp`).
Implements [HLDD-004](../../../docs/hldd/HLDD-004-release-pipeline.md). All logic
lives in `lib/*.sh`; the GitHub Actions workflow
[`.github/workflows/upstream-sync.yml`](../../.github/workflows/upstream-sync.yml)
is a thin orchestrator.

## What it does

Once a day (03:00 America/Phoenix = `0 10 * * *` UTC) — or on manual dispatch —
the pipeline:

1. **detect** (`detect.sh`) — `git ls-remote` upstream `master` and compare its
   HEAD to the last-built SHA stored on the `pipeline-state` branch
   (`tools/pipeline/state`). No checkout. If unchanged (and not forced), the run
   exits quietly.
2. **rebase** (`rebase.sh`) — restore the committed `rr-cache/` into
   `.git/rr-cache`, add the `upstream` remote, and
   `git -c rerere.enabled=true rebase upstream/master` the `tasker` branch.
   A genuinely new conflict aborts and opens a `CONFLICT` issue.
3. **tap-check** (`tap-check.sh`) — grep `libpebble3/src` for the stable tap
   anchors (`// BRIDGE-TAP: <id>`) and their `AutomationNotificationHooks`
   callsites. A missing tap opens a `TAP-DRIFT` issue (catches semantic drift a
   clean compile would miss).
4. **build** — provision `google-services.json` (from the committed dummy) and
   `local.properties`, then
   `./gradlew :composeApp:assembleRelease :tasker-bridge:testDebugUnitTest`
   (the latter includes the golden-JSON contract test). Failure opens a
   `BUILD-FAIL` issue + uploads reports.
5. **sign** (`sign.sh`) — `zipalign` then `apksigner sign` with the shared
   `tasker-plugin.p12` (ADR-009) decoded from secrets, then `apksigner verify`
   + `zipalign -c`. Failure opens a `SIGN-FAIL` issue.
6. **smoke** — KVM-accelerated `google_apis` `x86_64` emulator
   (`reactivecircus/android-emulator-runner`) boots, installs the signed APK,
   and asserts the app process comes up (boot + bridge-bind self-check).
   Failure opens a `SMOKE-FAIL` issue + uploads logcat.
7. **publish** (`publish.sh`) — compute `.sha256`, tag `tasker` as
   `v<versionCode>-tasker.<N>`, create a GitHub **release** (APK + sha256 +
   upstream changelog range), force-with-lease push `tasker`, and advance the
   last-built SHA on `pipeline-state`. Failure opens a `PUBLISH-FAIL` issue.

[Obtainium](obtainium.json) tracks the fork's releases by **versionCode** (the
nanogiants commit-count metric) and prompts to install when a newer build lands.

On **any** failure the pipeline opens an owner-assigned issue labeled `pipeline`
and **stops** without advancing the last-built SHA, so the next tick retries
until a human resolves it (see the conflict runbook,
`docs/runbooks/conflict-resolution.md`). Issue creation de-dupes per failure
kind so a stuck pipeline files one issue, not one per day.

## Scripts (`lib/`)

| Script | Role | Conflict exit code |
|---|---|---|
| `detect.sh`   | upstream-change detection (`changed=true/false`) | — |
| `rebase.sh`   | rerere-replay rebase of `tasker` onto `upstream/master` | `23` |
| `tap-check.sh`| patch-tap anchor grep on `libpebble3/src` | `31` |
| `sign.sh`     | `apksigner` sign + verify + `zipalign -c` | `41` |
| `publish.sh`  | release + sha256 + changelog; advance state | `51` |
| `issue.sh`    | open/assign a labeled `pipeline` failure issue | — |

Each script is POSIX `sh`, `set -eu`, self-contained, with a usage header.

## Required GitHub Actions secrets

| Secret | Purpose |
|---|---|
| `APP_KEYSTORE_B64`      | base64 of the shared `tasker-plugin.p12` keystore (ADR-009) |
| `APP_KEYSTORE_PASSWORD` | keystore (store) password |
| `APP_KEY_ALIAS`         | key alias inside the keystore |
| `APP_KEY_PASSWORD`      | key password (defaults to the store password if unset) |
| `READ_PACKAGES_ACTOR`   | GitHub login for GitHub Packages reads (mirrors `build.yml`) |
| `READ_PACKAGES_TOKEN`   | token with `read:packages` for the above |

`GITHUB_TOKEN` (auto-provided) covers release publishing and issue filing via the
workflow's `permissions: contents: write, issues: write`.

> The keystore lives **only** in Actions secrets (+ offline backups). It is
> decoded onto the ephemeral runner at sign time and shredded on exit — never
> logged, never committed (HLDD-004 §9).

### How signing maps to the app's build config

`composeApp/build.gradle.kts` already defines a `release` signingConfig that
reads `<repo>/keystore.jks` + `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEYSTORE_ALIAS`
/ `RELEASE_KEY_PASSWORD`. The workflow decodes `APP_KEYSTORE_B64` to
`keystore.jks` and maps the `APP_*` secrets onto those env vars, so Gradle's
release build signs with **zero build patch**. `sign.sh` then performs an
explicit, independent `apksigner` pass (the HLDD §4 step L behaviour) and proves
signature + alignment.

## One-time setup

1. **Action pins.** Replace each `@<pin-sha>` placeholder in
   `.github/workflows/upstream-sync.yml` with the real commit SHA for the noted
   version tag (`android-actions/setup-android@v3`,
   `reactivecircus/android-emulator-runner@v2`). Third-party actions are pinned
   by commit SHA per HLDD-004 §9.
2. **Secrets.** Add the secrets in the table above
   (*Settings → Secrets and variables → Actions*).
3. **State branch.** Optional — created automatically on the first successful
   publish. To seed it manually so the first run doesn't rebuild from scratch:
   create a `pipeline-state` branch containing `tools/pipeline/state` with the
   current upstream `master` SHA.
4. **Phone.** Install the GitHub Android app (issue/Actions notifications on) and
   add the fork to Obtainium using [`obtainium.json`](obtainium.json) — replace
   `OWNER/REPO` first.

## Running it manually

Trigger an on-demand build via **workflow_dispatch** with `force=true` (rebuilds
even when upstream `master` is unchanged — used after resolving a conflict, per
the runbook step 5):

```sh
# GitHub CLI:
gh workflow run upstream-sync.yml -f force=true

# Or: Actions tab -> "upstream-sync" -> Run workflow -> force = true.
```

A normal scheduled tick (or `force=false`) only builds when upstream moved.
