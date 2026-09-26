# Hand-off: libpebble3 additions for the Tasker Automation bridge

## Where this work happens

- Repo: `nbetcher/mobileapp`, a fork of `coredevices/mobileapp`. Branch off `tasker` (the fork's default and patch branch).
- libpebble3 is **not an external dependency**. It is the in-tree Gradle subproject `:libpebble3` (`include(":libpebble3")` in `settings.gradle.kts`) and is compiled from source. Make every change under `libpebble3/` in this repo.
- Upstream owns libpebble3. `.github/workflows/fork-sync.yml` merges upstream releases into `tasker`, so every line you change here will have to survive future merges. Keep the upstream-owned diff as small as possible:
  - Prefer **new** files, and new members appended to existing interfaces, over reshaping upstream code.
  - Put fork-only glue under `io.rebble.libpebblecommon.automation` (the package that already holds `AutomationAppMessageHook`).
  - Mark each call site you add inside an upstream function with `// BRIDGE-TAP: <id>`, the fork's existing convention (see `services/appmessage/AppMessageService.kt:60`). The sync workflow is told to preserve those anchors.
- Scope is **libpebble3 only**. The Tasker bridge commands, events, tiers, rate limits and consent UI live in `tasker-bridge/` and are done separately. This hand-off only has to provide the APIs listed below.
- **iOS: no work.** Automation is Android-only. Protocol code still goes in `commonMain`, because that is where libpebble3's protocol and services live and moving it would diverge from upstream. Add no iOS wiring, UI, or testing. `FakeLibPebble` and other test doubles need stubs so everything compiles.
- Follow `CLAUDE.md`:
  - minimal comments;
  - no `init {}` blocks;
  - per-watch state goes in per-watch services;
  - unit tests for new logic (`libpebble3/src/jvmTest` already exists; run `./gradlew :libpebble3:jvmTest`).
- Make one commit per numbered section below.

---

## 1. Plain reboot

PebbleOS `fw_reset.c` handles endpoint 2003: `0x00` = reboot, `0x01` = core dump, `0xfe` = factory reset, `0xff` = recovery (PRF). libpebble3's `ResetMessage` already models all four correctly, but nothing sends `Reset`.

- `connection/PebbleDevice.kt`: add `fun reset()` to `ConnectedPebble.Debug`.
- `services/SystemService.kt`: implement it the same way as `resetIntoPrf()`: `scope.launch { protocolHandler.send(ResetMessage.Reset) }`.
- `FakeLibPebble.kt`: add a stub.
- Do **not** copy reset values from libpebble2 or `pebble repl`. There `FactoryReset=0x02` and `PRF=0x03`, which PebbleOS rejects as invalid.

## 2. Remote input: button presses and swipes (endpoint `0xf00d`)

This first shipped in PebbleOS **v4.35.0** (`src/fw/kernel/remote_input.c`, commit `18d1e0e6`, 2026-08-19). It is registered for both normal and recovery (PRF) firmware, and is private: only the system session, i.e. this app, can use it.

Wire format (the firmware reads the 2-byte fields with `ntohs`, so they are **big-endian**; set `Endian.Big` explicitly on every multi-byte field rather than relying on `Endian.Unspecified`):

| Message | Bytes |
|---|---|
| Button (cmd `0x00`) | `u8 cmd, u8 button_id, u8 presses, u16 hold_ms, u16 gap_ms` |
| Swipe (cmd `0x01`) | `u8 cmd, u8 direction, u16 duration_ms` |
| ButtonSet (cmd `0x02`) | **do not implement** (see below) |
| Ack (watch → phone, reply to every message) | `u8 cmd, u8 status` where 0 = Ok, 1 = Busy, 2 = Invalid |

- Button ids: 0 = back, 1 = up, 2 = select, 3 = down. Directions: 0 = up, 1 = down, 2 = left, 3 = right.
- A Button message with `presses = 0` is acknowledged as Ok and does nothing.
- On a watch without a touch screen, Swipe always acks Invalid.
- Swipe also acks Invalid if `duration_ms` is above the firmware's `SWIPE_MAX_DURATION_MS`. The firmware default is 150 ms.
- Only one injected sequence can run at a time; a second one gets Busy.

**Do not implement ButtonSet.** It holds buttons down until another ButtonSet releases them or the Bluetooth session closes, and while held it makes every other injected input return Busy. That stuck-button risk is why it was rejected. Long presses stay available through Button's `hold_ms`: the firmware times that press itself and always releases it, so it does not depend on ButtonSet.

Changes:

- `protocolhelpers/ProtocolEndpoint.kt`: add `REMOTE_INPUT(0xf00du)`.
- New file `packets/RemoteInput.kt`:
  - outbound packets `RemoteInputMessage.Button(...)` and `RemoteInputMessage.Swipe(...)`;
  - inbound `RemoteInputAck`, registered in `PacketRegistry` in the same way as the other inbound packets.
- New file: a per-watch `RemoteInputService` in `services/`.
  - `suspend fun pressButton(button, presses, holdMs, gapMs): RemoteInputResult`
  - `suspend fun swipe(direction, durationMs): RemoteInputResult`
  - Use the subscribe-before-send pattern from `SystemService.sendPing`, and match the ack on the `cmd` byte.
  - Time out after about 5 s. `RemoteInputResult` = `Ok | Busy | Invalid | Unsupported | Timeout`.
  - Return `Unsupported` **without sending** when the running firmware is older than 4.35.0. Otherwise older watches never ack and the caller only ever sees Timeout.
- Expose the service on the connected device as a new `ConnectedPebble.RemoteInput` interface, added to `ConnectedPebbleDevice`. Register it in DI the same way as the other per-watch services.
- Validate in Kotlin before sending: button 0..3, presses 1..255, `hold_ms`/`gap_ms` 0..65535, direction 0..3.
- Tests:
  - exact serialized bytes for Button and Swipe, including big-endian `hold_ms` (e.g. 1000 → `03 E8`);
  - ack parsing;
  - status mapping;
  - the firmware-version gate.

## 3. Report the outcome of a time sync

`updateTime()` sends `SetUTC` and returns `Unit`. The watch never replies to SetUTC, so today nobody can tell whether it took effect. The bridge needs this outcome for its cooldown: 30 minutes after a success, 30 s after anything else.

- `packets/System.kt`: register inbound `TimeMessage.GetTimeResponse` (endpoint `TIME`, message `0x01`). It is currently not registered.
  - The firmware replies to `GetTimeRequest` (`0x00`) with `u8 0x01, u32 time` (`htonl`, big-endian, seconds from `rtc_get_time()`, which is UTC).
  - Confirm the field endianness with a test.
- Add `suspend fun updateTimeVerified(): TimeSyncResult` to `ConnectedPebble.Time`, implemented in `SystemService`:
  1. call the existing `updateTime()`;
  2. send `GetTimeRequest` and wait for the response (subscribe before sending), with a timeout of about 5 s;
  3. return `Success(skewSeconds)` if the watch time is within ±2 s of the phone's UTC, `Mismatch(skewSeconds)` if not, and `Timeout` if there is no response.
- Leave `updateTime()`'s signature alone. Upstream calls it in several places.
- Tests: response parsing and the skew classification.

## 4. Firmware update check: record when the last check finished

The bridge will refuse "install update" in two different ways when no update is available: either "none available", or "none available and no successful check in the last 24 h". Telling those apart needs to know when the last check finished.

- `connection/LibPebble.kt`: add `val checkedAt: Instant? = null` to `FirmwareUpdateCheckState`. The default keeps existing constructors compiling.
- `web/FirmwareUpdateManager.kt`, in `doUpdateCheck`: set `checkedAt = Clock.System.now()` when the result is `FoundUpdate` or `FoundNoUpdate`. On `UpdateCheckFailed`, keep the previous `checkedAt`: a failed check does not count as having checked.
- Test: `checkedAt` is set on found/none and kept on failure.

No other firmware changes are needed. `ConnectedPebble.FirmwareUpdate.updateFirmware(FoundUpdate)`, `checkforFirmwareUpdate(force)` and `FirmwareStatus.firmwareUpdateAvailable` already exist. The bridge derives its "update available" event from `firmwareUpdateAvailable`.

## 5. Which watch preferences each watch supports

The bridge has to list every watch preference for the connected watch (key, label, type, allowed values, current value), and return an error when a preference is not supported by the target watch.

Current state:

- Preferences are phone-global (`WatchPrefs.watchPrefs`). The list of all of them is `WatchPref.enumeratePrefs()`.
- Labels come from `WatchPref.displayName` and `description`. They are English only; the app has no localized strings for them. Use these as the display labels.
- PebbleOS only accepts preference keys on its board-dependent allowlist (`s_syncable_settings` / `s_syncable_notif_prefs` in `src/fw/services/blob_db/settings_blob_db.c`, gated by `CONFIG_ORIENTATION_MANAGER`, `CONFIG_ACCEL_SENSITIVITY`, `CONFIG_DYNAMIC_BACKLIGHT`, `CONFIG_BACKLIGHT_HAS_COLOR`, `TIMELINE_PEEK_WATCHFACE_FIT_SUPPORTED`). Any other key gets `E_INVALID_OPERATION` → `BlobResponse.BlobStatus.InvalidOperation`.
- libpebble3 currently ignores that status (`connection/endpointmanager/blobdb/BlobDB.kt`, `handleInsert`, the `else -> Unit` branch), so a rejected key is silently retried on every sync.
- Settings sync only happens at all on watches with `ProtocolCapsFlag.SupportsBlobDbVersion`.

Changes:

- **Record what each watch rejects.** In `handleInsert`, when `db.databaseId() == BlobDatabase.WatchPrefs` and the status is `InvalidOperation`, record the key as unsupported for that watch. Mark the call site `// BRIDGE-TAP: watchpref-rejected`.
  - Keep the record in a per-watch holder under `automation/`, with the same lifetime as the watch's other per-watch services. Store it in memory, keyed by pref id.
  - Also publish each insert's outcome on a `SharedFlow<WatchPrefSyncOutcome(prefId, status)>`. The bridge can then write a pref and wait for this watch's acceptance or rejection instead of reporting success blindly.
- **Expose it on the connected device** as a new `ConnectedPebble.WatchPrefSupport` interface:
  - `val watchPrefsSupported: Boolean`: true when the watch has `SupportsBlobDbVersion`.
  - `val rejectedWatchPrefs: StateFlow<Set<String>>`
  - `val watchPrefSyncOutcomes: SharedFlow<WatchPrefSyncOutcome>`
  - The bridge maps these to **supported** / **unsupported** / **unknown** (never written to this watch yet).
- Do **not** add a hand-maintained per-board support table. It would copy firmware `#ifdef`s that change from release to release. The watch's own accept/reject answer is the authority.
- Tests: an InvalidOperation reply on a WatchPrefs insert records the key and emits an outcome; Success and DataStale do not record it; inserts to other databases are unaffected.

## Out of scope here (bridge / app side, done separately)

- New commands: `watch.reboot`, `watch.pressButton`, `watch.swipe`, `watch.stopApp`, `watch.screenshot`, `watch.syncTime`, `watch.checkFirmware`, `watch.installFirmware`, `watch.getPref`, `system.listPrefs`, `watch.gatherLogs`, `watch.factoryReset`.
- New events: preference changed, firmware update available.
- Rate limits: reboot 60 s; time sync 30 min after success, 30 s otherwise.
- The new EXTREMELY_DANGEROUS tier and its countdown disclaimer dialog.
- Log gathering (`ConnectedPebble.Logs.gatherLogs()`), factory reset (`Debug.factoryReset()`), screenshot (`Screenshot.takeScreenshot()`) and stop app (`AppRunState.stopApp()`) already exist in libpebble3 and need no changes here.

## Done when

- All five sections are committed.
- `./gradlew :libpebble3:jvmTest` passes.
- `./gradlew :androidApp:assembleDebug` builds.
- The upstream-owned diff is limited to: new members on existing interfaces, the endpoint enum entry, the packet registrations, the `FirmwareUpdateCheckState` field, and the `BRIDGE-TAP` call site in `BlobDB.kt`.
