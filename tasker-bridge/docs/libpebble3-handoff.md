# Hand-off: libpebble3 additions for the Tasker Automation bridge

## Where this work happens

- Repo: `nbetcher/mobileapp`, a fork of `coredevices/mobileapp`. Branch off `tasker` (the fork's default and patch branch).
- libpebble3 is **not an external dependency**. It is the in-tree Gradle subproject `:libpebble3` (`include(":libpebble3")` in `settings.gradle.kts`) and is compiled from source. Make every change under `libpebble3/` in this repo.
- Upstream owns libpebble3. `.github/workflows/fork-sync.yml` merges upstream releases into `tasker`, so every line you change here will have to survive future merges. Keep the upstream-owned diff as small as possible:
  - Prefer **new** files, and new members appended to existing interfaces, over reshaping upstream code.
  - Put fork-only glue under `io.rebble.libpebblecommon.automation` (the package that already holds `AutomationAppMessageHook`).
  - Mark each call site you add inside an upstream function with `// BRIDGE-TAP: <id>`, the fork's existing convention (see `services/appmessage/AppMessageService.kt:60`). The sync workflow is told to preserve those anchors.
- Scope is **libpebble3 only**, plus switching the bridge over to the new APIs (section 6) and writing the follow-up hand-off (section 8). The Tasker bridge commands, events, tiers, rate limits and consent UI are already in `tasker-bridge/` and `composeApp/.../automation/`.
- **iOS: no work.** Automation is Android-only. Protocol code still goes in `commonMain`, because that is where libpebble3's protocol and services live and moving it would diverge from upstream. Add no iOS wiring, UI, or testing. `FakeLibPebble` and other test doubles need stubs so everything compiles.
- Follow `CLAUDE.md`:
  - minimal comments;
  - no `init {}` blocks;
  - per-watch state goes in per-watch services;
  - unit tests for new logic (`libpebble3/src/jvmTest` already exists; run `./gradlew :libpebble3:jvmTest`).
- Make one commit per numbered section below (sections 1–7).
- **Commit identity:** author and commit as `Nick Betcher <nick@nickbetcher.com>`. Set it in the repo's own config (`git config user.name` / `user.email`, not `--global`, which the environment may reset) and check `git log --format='%an <%ae> | %cn <%ce>'` before every push.
- **No AI attribution anywhere:** no `Co-Authored-By` or session-link trailers in commit messages, no generated-by footers, no model names in code, comments, docs or PR text.

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

## 6. Switch the bridge adapter to the new APIs

The bridge side is already built against a seam: `WatchControl` in
`tasker-bridge/src/androidMain/kotlin/coredevices/coreapp/automation/command/WatchControl.kt`. Its
`LibPebbleWatchControl` adapter reports the pieces above as unavailable until they exist. Once sections
1–5 land, update only that adapter:

| Adapter member | Today | After this work |
|---|---|---|
| `reboot` | `sendPPMessage(ResetMessage.Reset)` | `device.reset()` |
| `remoteInputAvailable` | `false` | `true` |
| `pressButton` / `swipe` | `UNSUPPORTED` | call the section 2 service and map `RemoteInputResult` to `RemoteInputOutcome` one to one |
| `syncTime` | `updateTime()` then `Unverified` | `updateTimeVerified()` mapped to `Verified` / `Mismatch` / `Timeout` |
| `firmwareCheckedAt` | `null` | `device.firmwareUpdateAvailable.checkedAt` |
| `prefSupport` | `UNSUPPORTED` without BlobDB v2, else `UNKNOWN` | also `UNSUPPORTED` if the key is in `rejectedWatchPrefs`, `SUPPORTED` if the watch has accepted it |
| `writePrefAndAwait` | write, then `prefSupport` | subscribe to `watchPrefSyncOutcomes`, write, wait for this key's outcome; on timeout (e.g. an unchanged value that is never re-sent) fall back to `prefSupport` |

Two bridge changes outside the adapter, both in `WatchControlCommands.kt`:

- **`checkFirmware` completion.** It currently decides a triggered check has finished by watching
  `checkingForUpdates` go `true` then `false` on `libPebble.watches`. That flow is built with `combine`
  and can drop the brief `true` state, so a check that fails or hits a cache within milliseconds
  returns `status=pending` after 4.5 s. Record `checkedAt` before triggering and wait instead for
  `firmwareUpdateAvailable` to carry a result with a later `checkedAt`, or an `UpdateCheckFailed`
  (which leaves `checkedAt` unchanged, so also compare the result object).
- **`lastSuccessfulCheck`.** The bridge now remembers per watch when `checkFirmware` saw a fresh
  successful check, as a stopgap for the missing `checkedAt`. Once `firmwareCheckedAt` is real, remove
  that map and use `control.firmwareCheckedAt(device)` alone in `installFirmware`.

Update `WatchControlCommandsTest` / `CommandActuationTest` to cover the new mappings. The bridge's
contract (`tasker-bridge/README.md`) does not change; if you find it has to, stop and record why in the
follow-up hand-off (section 8) instead of changing the contract silently.

## 7. Log dumps must not share a temp file

`services/LogDumpService.kt`, `gatherLogs()`, writes every dump for a watch to the fixed path
`getTempFilePath(appContext, "logs-${identifier.asString}")`. Two callers can run at once: the app's
bug report (`PebbleAppDelegate.getWatchLogs()`, used by `BugReportProcessor`) and the bridge's
`watch.gatherLogs` job, which copies the file out and then deletes it. Concurrent dumps interleave
lines in the same file, and the bridge's delete can remove the file the bug report is about to attach.
LogDump replies are not matched by cookie, so two dumps on one watch cannot safely overlap even with
separate files.

- Serialize `gatherLogs()` per watch with a `Mutex` held by the per-watch service.
- Give each dump a unique file name (for example append a random UUID). Keep the file in the same temp
  directory so existing cleanup still applies.
- Mark the change with `// BRIDGE-TAP: logdump-serialized`.
- Tests: two concurrent `gatherLogs()` calls produce two distinct paths and do not interleave.

The bridge keeps its `dump.delete()`; with unique names it only deletes its own copy.

## 8. After you push: write the follow-up hand-off

Do this as the **last step, right after the libpebble3 work is committed and successfully pushed to
GitHub**. Confirm the push landed (`git ls-remote origin <branch>` matches `HEAD`). If the push fails,
stop, report the failure, and do not write the follow-up yet.

Write `tasker-bridge/docs/followup-after-libpebble3.md`, commit it with the same identity rules, push
it, and print its full contents in your final reply. Write it as a self-contained prompt for a fresh
AI session. It takes exactly one of two forms:

**A. Changes needed in this repository.** Use this if anything you know of still needs doing or
deciding in `nbetcher/mobileapp` before the Tasker plugin can build on it. Examples: a mapping in
section 6 you could not finish; a libpebble3 API that ended up different from this hand-off; a
bridge contract change; a failing or flaky test; a firmware behaviour you observed that contradicts
section 2; anything you deferred. For each item give the file paths, what is wrong or missing, why, and
what "done" looks like. List any user decisions needed separately, as questions. End with: "When this
is done, write the Tasker plugin hand-off described in form B."

**B. Proceed to the Tasker plugin.** Use this only if nothing is left in this repository. The file is
then the prompt for implementing the features in the **Tasker plugin app**, which is a separate
repository that will not contain this code. So:

- Start with one line saying the host-side work is complete and verified, with the commit hash.
- Inline, verbatim, the "Watch control, preferences and diagnostics" section of `tasker-bridge/README.md`
  plus any README sections it depends on (command policy, jobs, events). Do not only link to them.
- List the new capability strings (`command.watch.*`, `commands.extremely_dangerous`,
  `events.owner_targeted`), the new error codes (`PREF_UNSUPPORTED`, `FIRMWARE_UPDATE_UNAVAILABLE`,
  `FIRMWARE_CHECK_STALE`, `WATCH_BUSY`, plus `CATEGORY_DISABLED` for jobs) and the new events
  (`watch.pref`, `fw.available`, `job.done`).
- Spell out what the plugin must build: a Tasker action per command; the preference picker fed by
  `watch.listPrefs` (labels, types, options, support status), used for both a "Get Preference" action and
  a "Preference Changed" event filter; a "Firmware Update Available" event; handling `job_id` →
  `job.done` for screenshots and logs, including reading the `content://` URI before it expires after
  one hour; and treating an unknown tier string such as `extremely_dangerous` as at least `dangerous`.
- Say that commands missing from the host's advertised `command.<type>` capabilities must be hidden or
  shown as unavailable, not attempted.
- Carry over the commit identity and no-attribution rules above.

## Already done on the bridge / app side

- Commands: `watch.reboot`, `watch.pressButton`, `watch.swipe`, `watch.stopApp`, `watch.screenshot`, `watch.syncTime`, `watch.checkFirmware`, `watch.installFirmware`, `watch.getPref`, `watch.listPrefs`, `watch.gatherLogs`, `watch.factoryReset`.
- Events: preference changed, firmware update available.
- Rate limits: reboot 60 s; time sync 30 min after success, 30 s otherwise.
- The new EXTREMELY_DANGEROUS tier and its countdown disclaimer dialog.
- Log gathering (`ConnectedPebble.Logs.gatherLogs()`), factory reset (`Debug.factoryReset()`), screenshot (`Screenshot.takeScreenshot()`) and stop app (`AppRunState.stopApp()`) already exist in libpebble3 and need no changes here.

## Done when

- All seven sections are committed and pushed.
- `./gradlew :libpebble3:jvmTest` passes.
- `./gradlew :androidApp:assembleDebug` builds.
- `./gradlew :tasker-bridge:testDebugUnitTest` passes.
- The follow-up hand-off from section 8 is written, committed and pushed.
- The upstream-owned diff is limited to: new members on existing interfaces, the endpoint enum entry, the packet registrations, the `FirmwareUpdateCheckState` field, and the `BRIDGE-TAP` call site in `BlobDB.kt`.
