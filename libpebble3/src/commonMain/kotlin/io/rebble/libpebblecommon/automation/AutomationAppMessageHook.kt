package io.rebble.libpebblecommon.automation

/**
 * Neutral, optional automation hook (ADR-008) for observing inbound AppMessages from watchapps — the
 * ★ AutoPebble "watch button -> phone" channel (HOOKS.md §2.3). An external integration (e.g. the
 * tasker-bridge) assigns [onReceived] once at startup; the single inbound choke point in
 * AppMessageService invokes it (guarded by runCatching) for every received message, so no per-UUID
 * subscription is required. Parameters are neutral types only.
 */
object AutomationAppMessageHook {
    /** An AppMessage arrived from a watchapp: (appUuid, dictionary of key -> value). */
    var onReceived: ((String, Map<Int, Any>) -> Unit)? = null
}
