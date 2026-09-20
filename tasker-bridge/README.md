# Automation bridge compatibility notes

These notes describe the September 2026 implementation changes. The protocol remains v1; new fields and capability names are additive. Update the Pebble host and Tasker plugin together for the corrected behavior.

## Command policy versus session failure

`COMMAND_NOT_AUTHORIZED` rejects one command because its tier exceeds the grant or the dangerous-command toggle is off. It does not revoke a healthy event listener. `NOT_AUTHORIZED` still denotes invalid session/access and requires fresh readiness. The plugin maps both to its existing Tasker authorization error code, while preserving the wire code to distinguish their recovery behavior.

Only the persisted `normal`, `sensitive`, and `dangerous` grants authorize commands; unknown values fail closed. The service revalidates the caller/session when command execution starts, and the plugin revalidates queued IPC before calling the host. A command already handed to the backend can still complete after a later revocation or timeout.

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
