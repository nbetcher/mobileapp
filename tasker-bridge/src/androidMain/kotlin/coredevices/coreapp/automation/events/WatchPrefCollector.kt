package coredevices.coreapp.automation.events

import coredevices.coreapp.automation.command.PrefListing
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Emits `system`/`watch.pref` when a watch preference changes, whether from the app, an automation
 * client or the watch itself. Preferences are phone-global, so the event carries no watch.
 */
class WatchPrefCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var previous: Map<String, String>? = null
            libPebble.watchPrefs
                .map { prefs -> prefs.filterNot { it.pref.isDebugSetting }.associate { it.pref.id to PrefListing.encodedValue(it) } }
                .distinctUntilChanged()
                .collect { current ->
                    val before = previous
                    previous = current
                    if (before == null) return@collect
                    for ((key, value) in current) {
                        val old = before[key]
                        if (old == value) continue
                        val pref = PrefListing.listable().firstOrNull { it.id == key } ?: continue
                        dispatcher.emit("system", "watch.pref", data = buildMap {
                            put("pref_key", key)
                            put("label", pref.displayName)
                            put("value", value)
                            old?.let { put("previous", it) }
                        })
                    }
                }
        }
    }
}
