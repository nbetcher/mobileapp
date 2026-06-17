package io.rebble.libpebblecommon.automation

/**
 * Neutral, optional automation hooks (ADR-008) that let an external integration (e.g. the
 * tasker-bridge module) observe notification activity without any libpebble3 callsite depending on
 * that integration. The integration assigns these callbacks once at startup; the callsites invoke
 * them only if set, and only ever inside a runCatching guard so a hook can never affect delivery.
 *
 * Parameters are primitives so this stays platform- and integration-neutral.
 */
object AutomationNotificationHooks {
    /** A notification was forwarded to the watch: (packageName, title, body). */
    var onSent: ((String, String?, String?) -> Unit)? = null

    /** A notification action was performed on the watch: (actionType, packageName). */
    var onAction: ((String, String) -> Unit)? = null
}
