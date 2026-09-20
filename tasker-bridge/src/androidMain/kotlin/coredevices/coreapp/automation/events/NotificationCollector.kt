package coredevices.coreapp.automation.events

import coredevices.coreapp.automation.AutomationSettings
import io.rebble.libpebblecommon.automation.AutomationNotificationHooks
import kotlinx.coroutines.CoroutineScope

/**
 * Bridges the libpebble3 notification hooks (the âš  patch taps, HOOKS.md Â§2.2) into bridge events:
 *   - onSent   -> notif.sent
 *   - onAction -> notif.action
 *
 * Consent + redaction (PLAN Â§5.4): notification content is gated by [AutomationSettings] â€” title and
 * body are emitted only when content sharing is enabled, and the body is dropped when redaction is
 * on. The flags are read from thread-safe StateFlows at emit time, so a consent change in the
 * settings UI takes effect immediately. (Whether the notifications category fires at all is enforced
 * centrally by EventDispatcher's consent gate.)
 */
class NotificationCollector(
    private val dispatcher: EventDispatcher,
    private val settings: AutomationSettings,
) {
    @Suppress("UNUSED_PARAMETER")
    fun start(scope: CoroutineScope) {
        AutomationNotificationHooks.onSent = { pkg, title, body ->
            val data = buildMap {
                put("pkg", pkg)
                val redacted = !settings.notificationContentEnabled.value || settings.redactNotificationContent.value
                put("redacted", redacted.toString())
                put("title_shared", settings.notificationContentEnabled.value.toString())
                if (settings.notificationContentEnabled.value) {
                    title?.let { put("title", it) }
                    if (!settings.redactNotificationContent.value) body?.let { put("text", it) }
                }
            }
            dispatcher.emit(category = "notifications", type = "notif.sent", data = data)
        }
        AutomationNotificationHooks.onAction = { type, pkg, actionId ->
            dispatcher.emit(
                category = "notifications",
                type = "notif.action",
                data = mapOf(
                    "action" to when (type) {
                        "Generic" -> "generic"
                        "OpenOnPhone" -> "open_on_phone"
                        "Dismiss" -> "dismiss"
                        "Reply" -> "reply"
                        "MuteApp" -> "mute_app"
                        "MuteChannel" -> "mute_channel"
                        else -> "unknown"
                    },
                    "pkg" to pkg,
                    "action_id" to actionId.toString(),
                ),
            )
        }
    }
}
