package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.automation.AutomationTimelineHook
import kotlinx.coroutines.CoroutineScope

/**
 * Bridges user-invoked timeline *pin* actions (the libpebble3 timeline tap, HOOKS.md §2.4) into
 * timeline.action events — e.g. a calendar RSVP or another pin action taken on the watch. Notification
 * actions are reported separately as notif.action, so this covers only the pin/timeline-item actions
 * that channel does not. Registers the single libpebble3 hook so every pin action is captured at one
 * choke point without per-pin subscription.
 *
 * Field keys (pin_uuid, action_id) match the plugin's TimelineRunner contract (HLDD-002 §6).
 */
class TimelineCollector(
    private val dispatcher: EventDispatcher,
) {
    @Suppress("UNUSED_PARAMETER")
    fun start(scope: CoroutineScope) {
        AutomationTimelineHook.onAction = { pinUuid, actionId ->
            dispatcher.emit(
                category = "timeline",
                type = "timeline.action",
                data = mapOf("pin_uuid" to pinUuid, "action_id" to actionId.toString()),
            )
        }
    }
}
