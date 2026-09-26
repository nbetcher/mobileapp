# Automation bridge compatibility notes

These notes describe the September 2026 implementation changes. The protocol remains v1; new fields and capability names are additive. Update the Pebble host and Tasker plugin together for the corrected behavior.

## Command policy versus session failure

`COMMAND_NOT_AUTHORIZED` rejects one command because its tier exceeds the grant or the dangerous-command toggle is off. It does not revoke a healthy event listener. `NOT_AUTHORIZED` still denotes invalid session/access and requires fresh readiness. The plugin maps both to its existing Tasker authorization error code, while preserving the wire code to distinguish their recovery behavior.

Only the persisted `normal`, `sensitive`, `dangerous` and `extremely_dangerous` grants authorize commands; unknown values fail closed. The service revalidates the caller/session when command execution starts, and the plugin revalidates queued IPC before calling the host. A command already handed to the backend can still complete after a later revocation or timeout.

A server `TIMEOUT` means command completion is unknown; it does not by itself invalidate an otherwise healthy session. Do not automatically retry non-idempotent actions. Server commands have a cooperative 6-second deadline. The plugin bounds IPC at 8 seconds, readiness at 12 seconds, and the combined action at 25 seconds. Condition readiness and state IPC each use at most 4 seconds and share the remaining eight-second query budget, including queue waiting.

## Complete AppMessage subscriptions

The `appmessages.replace_subscriptions` capability adds an atomic full-owner replacement to `appmessage.subscribe`:

```json
{
  "v": 1,
  "kind": "command",
  "type": "appmessage.subscribe",
  "args": {
    "mode": "replace",
    "subscriptions_json": "[{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"watch\":\"\",\"ownership\":\"observe\"}]"
  }
}
```

The nested JSON array has at most 256 entries. UUIDs and ownership (`observe` or `tasker`) are validated before publication. Unknown watch selectors are deferred, with their selectors returned as the JSON string `data.deferred_watches`; available subscriptions are still installed. The top-level watch selector is not allowed for replacement. Invalid UUID/ownership requests leave the previous owner set intact. An empty array removes all subscriptions for this verified package; other owners are unchanged. Legacy per-key subscribe/unsubscribe requests remain available.

The plugin saves desired configurations and replaces the complete set on a Ready session or host initialization query. Failed replacements are not cached as successful. Diagnostics offers explicit subscription cleanup after obsolete Tasker profiles have been deleted. Tasker-only ownership remains conditional on authorization, a live listener, and the watch application's eligibility; it never overrides a declared native/PKJS companion.

Copied profile IDs coalesce only identical configurations. The configuration editor asks whether to keep or replace a previous configuration when app/watch/ownership changes. Deferred watches keep the desired snapshot eligible for retry, including on watch connection. Deletion has no automatic Tasker callback; cleanup remains explicit.

## Event compatibility and privacy

- Call filters accept the older `RingingCall`, `DialingCall`, `ActiveCall`, `HoldingCall`, and `Ended` values as well as the current lowercase values.
- `notif.sent.data.title_shared` explicitly describes title availability. Body-only redaction preserves titles, strips body/text, and retains `redacted=true`. All-content-off strips both. Consumers of older events fall back to `!redacted` when `title_shared` is absent.
- App transitions already observed by the collector are queued in order; optional locker metadata lookup is limited to 250 ms. This does not make the upstream `StateFlow` lossless.
- Plugin-local `plugin.access` diagnostics carry a persisted `diagnostic_id`, independent of the remote event delivery epoch. Only the current decision is eligible; clearing the decision expires its diagnostic. Local diagnostic queries do not initiate a bridge handshake.

## Replay and authorization continuity

`BridgeHello.authorityId` combines persisted master, per-client authorization, and privacy-policy revisions. Handshake reads the grant and revisions under the policy locks. Updating an unrelated client's approval does not invalidate this client's pending events. The plugin retains delivery epochs across transient transport recovery only after matching both boot and authority. Denial, changed authority, or missing revision proof invalidates pending payloads.

The `events.paged` capability limits serialized events to 40 Ki UTF-16 code units and batches to 96 Ki units including envelope headroom (approximately 192 KiB for the Parcel string). Oversized single events are rejected before journal acceptance/ACK. Replay cursors advance only through scanned events, and listeners immediately drain `more=true` pages. `events.registration_ack_only` permits a proof query at `Long.MAX_VALUE` to return registration identity and the current cursor without replaying the backlog. These are conservative per-message bounds, not a guarantee against exhaustion of Android's shared Binder buffer by unrelated concurrent calls.

The plugin serializes both Tasker query entry paths through result handoff using the pinned SDK 0.4.10 evaluator. This fixes plugin-boundary overtaking; it cannot guarantee the installed host's task scheduling or acknowledged/exactly-once task execution. Passing unit tests are not a device qualification.

## Watch control, preferences and diagnostics

New commands are additive and advertised as `command.<type>` only when this build can run them (`watch.pressButton` and `watch.swipe` stay unadvertised, and fail with `UNSUPPORTED_COMMAND`, until libpebble3 gains remote input). All accept the usual `watch` selector.

| Command | Tier | Args | Result |
|---|---|---|---|
| `watch.listPrefs` | normal | — | `prefs`: JSON array of `{key,label,description?,type,value,default,support,options?,min?,max?,unit?}`; `count` |
| `watch.getPref` | normal | `pref_key` | flat `pref_key,label,description?,type,value,default,support,options?,min?,max?,unit?` |
| `watch.stopApp` | normal | `uuid` (default: running app) | `uuid` |
| `watch.syncTime` | normal | — | `verified`, `skew_s?` |
| `watch.checkFirmware` | normal | `force` (without it, the last known result is returned at once) | `status` = `available`\|`none`\|`failed`\|`pending`, `version?` |
| `watch.screenshot` | sensitive | — | `job_id` |
| `watch.reboot` | dangerous | — | `rebooting` |
| `watch.pressButton` | dangerous | `button` (back/up/select/down), `presses` 1-255 (1), `hold_ms` 0-65535 (50), `gap_ms` 0-65535 (100) | `accepted` |
| `watch.swipe` | dangerous | `direction` (up/down/left/right), `duration_ms` 1-300 (150) | `accepted` |
| `watch.installFirmware` | dangerous | `version` (optional guard) | `version`, `started` |
| `watch.gatherLogs` | extremely dangerous | — | `job_id` |
| `watch.factoryReset` | extremely dangerous | `confirm_serial` (must equal the target serial) | `factory_reset=started` |

- **Preferences.** Labels are the app's own English setting names. `support` is `supported`, `unsupported` (the watch rejected the key, or cannot sync settings at all) or `unknown` (not yet written to this watch). `watch.getPref` returns `PREF_UNSUPPORTED` for an unsupported key. `watch.setPref` stays global; with exactly one connected watch it refuses a key that watch does not support and waits up to 3 s for the watch's answer, returned as `watch_status`. Values and options use the `watch.setPref` wire form, so a listed option can be passed back unchanged. Debug settings are not listed.
- **Cooldowns** apply per watch across all clients and return `RATE_LIMITED` with the remaining seconds: reboot 60 s; time sync 30 min after a verified sync, otherwise 30 s. Until libpebble3 can read the watch clock back, every sync is `verified=false`.
- **Remote input** is acknowledged when the watch admits the sequence, not when it finishes. `WATCH_BUSY` means another injected sequence is still running. A timed long press (`hold_ms`) is released by the watch itself; holding a button indefinitely is deliberately not offered.
- **Firmware install** refuses with `FIRMWARE_UPDATE_UNAVAILABLE` when a check within the last 24 h found nothing, and `FIRMWARE_CHECK_STALE` when no successful check is known within 24 h. Until libpebble3 records check times, every refusal is `FIRMWARE_CHECK_STALE`. `WATCH_BUSY` means an update is already running.
- **Jobs.** Screenshots and log dumps outlast the IPC deadline. The command returns a `job_id`; the result arrives as a `system`/`job.done` event with `job_id`, `command`, `status` (`ok`/`failed`), `error?`, and on success `uri`, `mime` and (screenshots) `width`/`height`. The event carries `owner` and reaches only that package (`events.owner_targeted`), so the client needs the `system` category; without it, or with `system` disabled in the app, the command fails with `CATEGORY_DISABLED`. The `content://` URI is readable only by the requesting package and is deleted after one hour.
- **Events.** `system`/`watch.pref` (`pref_key`, `label`, `value`, `previous?`) fires on any preference change, including changes made on the watch; preferences are phone-global, so it has no watch. `system`/`fw.available` (`version`, `current`, `can_downgrade`, `notes` ≤ 2000 chars) fires once per watch and offered version.
- **Extremely dangerous tier** (`extremely_dangerous`, `commands.extremely_dangerous`). Granting it shows a one-time, per-client warning whose Accept button unlocks after 10 s of the dialog being in focus. Acceptance is kept for that package and signing certificate until the client is revoked. The app-wide dangerous-commands toggle must also be on. A grant without a recorded acceptance acts as `dangerous`. `BridgeHello.grants.tier` can now read `extremely_dangerous`; plugins that do not know it should treat it as `dangerous`.
