# Bridge regression verification

Run current production tests with the normal Gradle JUnit worker. This project references source
files in `../../tasker-bridge/src` directly; it neither copies nor rewrites production Kotlin.

From the Pebble workspace in PowerShell:

```powershell
& './Tasker Plugin/tools/verify.ps1' -Suite BridgeCore
```

The script uses Android Studio's JBR and `%LOCALAPPDATA%/Android/Sdk` by default. Override with
`-Jdk` and `-AndroidSdk`. Compile SDK 36 is required. `-Tests '*ConsentLifecycleTest'` selects a
suite (omit the filter for the full core run). No release signing or deployment task is run.

The standalone toolchain is Gradle 8.14.4 (the existing Tasker Plugin wrapper), AGP 8.13.2,
Kotlin 2.3.10, coroutines 1.9.0, serialization 1.7.3, and Robolectric 4.14.1 at API 34.
This deliberately differs from the Watch App root's newer toolchain: success here is a bounded
core check, not proof that the entire Watch App builds or that UI/device integration works.

## Included production boundaries

- Shared envelopes, client records, command catalog/arguments, AppMessage value parsing, event access policy.
- Caller/signer verification, persisted trust/pending/denied decisions, privacy settings and authority revisions, consent notification controller.
- Event dispatcher, listener replay/live delivery, and client session lifecycle.
- Command policy/executor, rate limiter and the production command-handler interface.

Tests come from the real `tasker-bridge/src/androidUnitTest/kotlin` source set. Five suites are
explicitly excluded because they require the actual LibPebble dependency graph:
`LibPebbleCommandHandlerTest`, `CommandActuationTest`, `CollectorRegressionTest`, and
`BridgeServiceContractTest` (the latter needs the real service, Koin and StateProvider), and
`ClientTetherTest` (requires LibPebble).
Adding a new backend-dependent suite requires explicitly extending this list and keeping this
boundary accurate. Missing production dependencies should fail compilation, not be replaced by
fake classes. The test-only manifest does not instantiate the host service or application.

Normal results are under `build/test-results/testDebugUnitTest/` (JUnit XML) and
`build/reports/tests/testDebugUnitTest/index.html` (HTML). These files are local build outputs.
Failures return a nonzero PowerShell/Gradle result. Never interpret old reports as a new pass
after a compilation failure; check the command's exit code and report modification time.

## Full Tasker Plugin

The empty sibling `../../NeonGrid/android/theme` is not a usable dependency. Use the real
[theme upstream](https://github.com/nbetcher/neon_grid_theme). The verified checkout for this
work is commit `eb78a5521adf4e7a698d97bfe816d6ac1fa26dab`.

```powershell
git clone https://github.com/nbetcher/neon_grid_theme.git C:/src/pebble-neon-grid
git -C C:/src/pebble-neon-grid checkout --detach eb78a5521adf4e7a698d97bfe816d6ac1fa26dab
& './Tasker Plugin/tools/verify.ps1' -Suite Plugin -NeonGridDir C:/src/pebble-neon-grid/android
```

Existing setup on this workstation:

```powershell
& './Tasker Plugin/tools/verify.ps1' -Suite Both -NeonGridDir 'C:/Users/nbetc/AppData/Local/Temp/pebble-neongrid-source-20260920/android'
```

The plugin build supports `-PneonGridDir=<checkout>/android` or `NEON_GRID_ANDROID_DIR` directly.
Its unit-test command compiles the whole production plugin, its actual resources/AIDL/manifest,
and its tests. There are no trimmed runners or warning/resource stubs. Robolectric tests still
cannot qualify installed Tasker, cross-process Binder, notification settings, or watch hardware.

## Full Watch App bridge

```powershell
& './Tasker Plugin/tools/verify.ps1' -Suite WatchBridge -Java17Toolchain 'C:/Users/nbetc/AppData/Local/Temp/pebble-jdk17-20260920/jdk-17.0.20.1+1'
# Backend JVM tests, including AppMessage ownership and state-before-event ordering:
& './Tasker Plugin/tools/verify.ps1' -Suite WatchJvm -Java17Toolchain 'C:/Users/nbetc/AppData/Local/Temp/pebble-jdk17-20260920/jdk-17.0.20.1+1'
# Android UI Kotlin compilation (consent screen, MainActivity, pending banner):
& './Tasker Plugin/tools/verify.ps1' -Suite WatchUi -Java17Toolchain 'C:/Users/nbetc/AppData/Local/Temp/pebble-jdk17-20260920/jdk-17.0.20.1+1'
```

The full root currently requires SDK 37, NDK 28.2.13676358 and a JDK 17 compilation toolchain.
The JBR remains the Gradle launcher; `-Java17Toolchain` only sets Gradle's toolchain search path
(or set `PEBBLE_JAVA17_HOME`). These prerequisites are independent of the core fallback.
The script supplies Git for Windows' `usr/bin` when installed because root configuration invokes
`which`. It does not edit sibling repositories or application source. The full command configures
the root's other projects and can download dependencies or encounter platform/backend blockers.

## Windows worker failure

The initial worker crash was `Could not find or load main class Files\NVIDIA`.
AGP inherited embedded quotes from a CUDA PATH entry into `java.library.path`; the worker
command split at those quotes. The script removes quotes only from its process PATH, uses
`--no-daemon` to obtain a fresh single-use daemon, and restores environment variables in `finally`.
No machine/user PATH is changed. The normal Gradle worker then executes tests and writes JUnit XML.

## Regression and installed-device plan

Use [the binding review](../../../docs/reviews/2026-09-20-tasker-pebble/BINDING-REVIEW.md)
and `findings.json` as the behavioral evidence. The archived `*Reproductions` tests asserted the
old defects; their green result is not acceptance for the fixes. Current regressions must assert
the repaired behavior, and production tests must stay in the owning module's test directories.

- Ordering (F01-F06, F16-F18): disconnect/connect, delayed replay, concurrent emit, overflow/gaps,
  boot changes and immutable per-query payloads; preserve state freshness and watch identity.
- Consent/session lifecycle (F14-F15, F21-F24, F35-F37, F39-F40, F43-F48, F50-F54): master off
  still creates a reviewable request; pending stays quiet across retries/restarts; denial survives
  recreation; approval/revoke/signer replacement invalidate the right session and notification;
  queued old-generation handshakes/callbacks never become current.
- Tasker entry points and restore (F07, F19-F22, F38, F41-F42, F49): action error codes/messages,
  pending versus denied, condition diagnostics, setup absent/present, and event initialization
  without a pass-through message ID must not fabricate an event.
- Contract/actuation (F08-F13, F25-F34): exact producer/consumer fields, watch selection, unsupported
  capabilities, timeout/cancellation, AppMessage types and acknowledgement ownership, and notification
  action identity. Run the real LibPebble-backed tests via the full root; the core suite covers only
  policy/contract parsing.
- Installed-device qualification: restore action-only, state-only and event-only Tasker backups
  with each app cold/running and setup absent/present. Repeat with pending, denied and approved
  consent; permission allowed/blocked; master off/on; no watch/connected watch; explicit reset;
  process death, package replacement and rapid disconnect/reconnect. Capture actual host callbacks,
  task results and visible diagnostics. Never infer this matrix from Robolectric success.
