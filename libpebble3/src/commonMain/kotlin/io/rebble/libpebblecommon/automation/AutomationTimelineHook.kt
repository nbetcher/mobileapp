package io.rebble.libpebblecommon.automation

/**
 * Neutral, optional automation hook (ADR-008) for observing user-invoked timeline *pin* actions —
 * e.g. a calendar RSVP or another pin action taken on the watch (HOOKS.md §2.4). An external
 * integration (e.g. the tasker-bridge) assigns [onAction] once at startup; the single pin-action
 * dispatch point in TimelineActionManager invokes it (guarded by runCatching) for every pin action,
 * so no per-pin subscription is required. Notification actions are reported via a separate channel
 * (notif.action), so this hook covers only the pin/timeline-item actions that channel does not.
 * Parameters are neutral types only.
 */
object AutomationTimelineHook {
    /** A timeline pin action was invoked on the watch: (pinUuid, actionId). */
    var onAction: ((String, Int) -> Unit)? = null
}
