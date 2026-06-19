package coredevices.coreapp.automation.events

import coredevices.coreapp.automation.AutomationSettings
import io.rebble.libpebblecommon.automation.AutomationNotificationHooks
import kotlinx.coroutines.CoroutineScope

/**
 * Bridges the libpebble3 notification hooks (the ⚠ patch taps, HOOKS.md §2.2) into bridge events:
 *   - onSent   -> notif.sent
 *   - onAction -> notif.action
 *
 * Consent + redaction (PLAN §5.4): notification content is gated by [AutomationSettings] — title and
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
                put("package", pkg)
                if (settings.notificationContentEnabled.value) {
                    title?.let { put("title", it) }
                    if (!settings.redactNotificationContent.value) body?.let { put("body", it) }
                }
            }
            dispatcher.emit(category = "notifications", type = "notif.sent", data = data)
        }
        AutomationNotificationHooks.onAction = { type, pkg ->
            dispatcher.emit(
                category = "notifications",
                type = "notif.action",
                data = mapOf("type" to type, "package" to pkg),
            )
        }
    }
}
