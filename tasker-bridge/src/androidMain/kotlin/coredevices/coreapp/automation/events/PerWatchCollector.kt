package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Per-connected-watch event collectors (HLDD-001 §9, PLAN §6.2). Tracks the connected device set off
 * [LibPebble.watches] and, for each connected device, observes its per-connection flows:
 *   - runningApp        -> apps.run_state
 *   - musicActions      -> media.command
 *   - devConnectionActive -> dev.state
 * Firmware status is a snapshot (not a flow), so it is diffed off the watches snapshot -> fw.status,
 * the same technique [ConnectivityCollector] uses for battery. A device's subscriptions are cancelled
 * when it disconnects.
 */
class PerWatchCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        val jobs = HashMap<String, Job>()
        val lastFw = HashMap<String, String>()
        scope.launch {
            libPebble.watches.collect { devices ->
                val connected = devices.filterIsInstance<ConnectedPebbleDevice>()
                val present = connected.map { it.identifier.asString }.toSet()

                // Drop subscriptions for watches that went away.
                jobs.keys.filter { it !in present }.forEach { key ->
                    jobs.remove(key)?.cancel()
                    lastFw.remove(key)
                }

                for (device in connected) {
                    val id = device.identifier.asString
                    if (id !in jobs) jobs[id] = scope.launch { observe(device) }

                    // Firmware status: emit only on a real change. Seed on first sight (prev == null)
                    // so a freshly-seen connected watch doesn't emit a phantom 'Idle' status event.
                    val fw = device.firmwareUpdateState::class.simpleName ?: "Unknown"
                    val prevFw = lastFw.put(id, fw)
                    if (prevFw != null && prevFw != fw) {
                        dispatcher.emit("system", "fw.status", device.toWatchRef(), mapOf("status" to fw))
                    }
                }
            }
        }
    }

    private suspend fun observe(device: ConnectedPebbleDevice) = coroutineScope {
        launch {
            // drop(1): skip the StateFlow's replayed current app on (re)subscribe; emit real launches.
            device.runningApp.drop(1).collect { uuid ->
                if (uuid != null) {
                    dispatcher.emit("apps", "apps.run_state", device.toWatchRef(), mapOf("kind" to "app", "uuid" to uuid.toString()))
                }
            }
        }
        launch {
            device.musicActions.collect { action ->
                dispatcher.emit("media", "media.command", device.toWatchRef(), mapOf("action" to action.name))
            }
        }
        launch {
            // drop(1): skip the StateFlow's current value on (re)subscribe; emit only real changes.
            device.devConnectionActive.drop(1).collect { active ->
                dispatcher.emit("system", "dev.state", device.toWatchRef(), mapOf("enabled" to active.toString()))
            }
        }
    }
}
