package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.automation.AutomationNotificationHooks
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.Volatile

/**
 * Bridges the libpebble3 notification hooks (the ⚠ patch taps, HOOKS.md §2.2) into bridge events:
 *   - onSent   -> notif.sent
 *   - onAction -> notif.action
 *
 * Consent + redaction (PLAN §5.4 / Phase 4): notification CONTENT defaults OFF ([contentEnabled]).
 * When content is enabled, [redactContent] still strips the body so only package + title leave the
 * app. These flags are the binding point for the in-app per-category consent UI (ConsentController);
 * until bound they stay at the privacy-preserving defaults (package only).
 */
class NotificationCollector(
    private val dispatcher: EventDispatcher,
) {
    /** When false, only the source package is emitted (no title/body). Default OFF (PLAN §5.4). */
    @Volatile
    var contentEnabled: Boolean = false

    /** When true, the body is dropped even if content is enabled (title + package only). */
    @Volatile
    var redactContent: Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun start(scope: CoroutineScope) {
        AutomationNotificationHooks.onSent = onSent@{ pkg, title, body ->
            val data = buildMap {
                put("package", pkg)
                if (contentEnabled) {
                    title?.let { put("title", it) }
                    if (!redactContent) body?.let { put("body", it) }
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
