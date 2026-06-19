package coredevices.coreapp.automation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level event-consent settings (PLAN §5.4): which event CATEGORIES are shared with automation
 * clients, and whether notification CONTENT (title/body) is allowed to leave the app. Distinct from
 * the per-client allowlist ([coredevices.coreapp.automation.trust.ClientTrustStore], which also owns
 * the master switch). Persisted in SharedPreferences; surfaced in the in-app "Tasker integration"
 * settings section and honored by [coredevices.coreapp.automation.events.EventDispatcher] and the
 * notification collector.
 *
 * Defaults are privacy-preserving: every category ON except the sensitive HEALTH category, and
 * notification content OFF + redacted. The master switch gates everything on top of these.
 */
class AutomationSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("automation_settings", Context.MODE_PRIVATE)

    private val _categories = MutableStateFlow(loadCategories())
    /** Per-category enabled map (category name -> enabled). */
    val categories: StateFlow<Map<String, Boolean>> = _categories.asStateFlow()

    private val _notificationContentEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_NOTIF_CONTENT, false))
    /** When false, notif.sent carries the source package only — no title/body (default OFF). */
    val notificationContentEnabled: StateFlow<Boolean> = _notificationContentEnabled.asStateFlow()

    private val _redactNotificationContent =
        MutableStateFlow(prefs.getBoolean(KEY_NOTIF_REDACT, true))
    /** When true, the notification body is dropped even if content is enabled (default ON). */
    val redactNotificationContent: StateFlow<Boolean> = _redactNotificationContent.asStateFlow()

    /** True when [category] is currently enabled. Unknown categories default to enabled. */
    fun isCategoryEnabled(category: String): Boolean = _categories.value[category] ?: true

    fun setCategoryEnabled(category: String, on: Boolean) {
        prefs.edit().putBoolean(catKey(category), on).apply()
        _categories.value = _categories.value + (category to on)
    }

    fun setNotificationContentEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_CONTENT, on).apply()
        _notificationContentEnabled.value = on
    }

    fun setRedactNotificationContent(on: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIF_REDACT, on).apply()
        _redactNotificationContent.value = on
    }

    private fun loadCategories(): Map<String, Boolean> =
        CATEGORIES.associateWith { prefs.getBoolean(catKey(it), it != CATEGORY_HEALTH) }

    companion object {
        const val CATEGORY_CONNECTIVITY = "connectivity"
        const val CATEGORY_NOTIFICATIONS = "notifications"
        const val CATEGORY_APPS = "apps"
        const val CATEGORY_MEDIA = "media"
        const val CATEGORY_CALLS = "calls"
        const val CATEGORY_HEALTH = "health"
        const val CATEGORY_SYSTEM = "system"

        /** The user-toggleable event categories (mirrors EventEnvelope.category values). */
        val CATEGORIES = listOf(
            CATEGORY_CONNECTIVITY,
            CATEGORY_NOTIFICATIONS,
            CATEGORY_APPS,
            CATEGORY_MEDIA,
            CATEGORY_CALLS,
            CATEGORY_HEALTH,
            CATEGORY_SYSTEM,
        )

        private const val KEY_NOTIF_CONTENT = "notif_content_enabled"
        private const val KEY_NOTIF_REDACT = "notif_redact"
        private fun catKey(category: String) = "cat_$category"
    }
}
