package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectionFailureInfo
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Maps [LibPebble] connectivity signals to `connectivity` envelopes (HLDD-001 §9):
 * watch.connected / watch.disconnected (from connectionEvents), and watch.battery + watch.state
 * (both diffed off the watches snapshot, which re-emits when any watch property changes). watch.state
 * fires when a device's connectionFailureInfo reports a fresh connection failure (the plugin's E3).
 */
class ConnectivityCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            libPebble.connectionEvents.collect { event ->
                when (event) {
                    is PebbleConnectionEvent.PebbleConnectedEvent ->
                        dispatcher.emit("connectivity", "watch.connected", event.device.toWatchRef())

                    is PebbleConnectionEvent.PebbleDisconnectedEvent ->
                        dispatcher.emit(
                            category = "connectivity",
                            type = "watch.disconnected",
                            // Disconnect carries only an identifier; serial/name aren't known here.
                            watch = WatchRef(
                                serial = event.identifier.asString,
                                name = event.identifier.asString,
                                address = event.identifier.asString,
                            ),
                        )
                }
            }
        }

        scope.launch {
            val lastBattery = HashMap<String, Int>()
            val lastFailure = HashMap<String, ConnectionFailureInfo>()
            libPebble.watches.collect { devices ->
                val present = HashSet<String>()
                val presentIds = HashSet<String>()
                for (device in devices) {
                    // Connection-failure transitions -> watch.state (HLDD-001 §9, plugin E3). The
                    // connectionFailureInfo lingers until a successful connect, so emit only when it
                    // newly appears or the reason/attempt count changes (a fresh failure) -- never on
                    // every snapshot re-emit. A failed device isn't connected, so use the identity ref.
                    val id = device.identifier.asString
                    presentIds.add(id)
                    val failure = device.connectionFailureInfo
                    if (failure != null && lastFailure.put(id, failure) != failure) {
                        dispatcher.emit(
                            category = "connectivity",
                            type = "watch.state",
                            watch = device.toIdentityRef(),
                            data = mapOf(
                                "reason" to failure.reason.name,
                                "attempts" to failure.times.toString(),
                            ),
                        )
                    }

                    if (device is CommonConnectedDevice) {
                        present.add(device.serial)
                        val level = device.batteryLevel ?: continue
                        if (lastBattery.put(device.serial, level) != level) {
                            dispatcher.emit(
                                category = "connectivity",
                                type = "watch.battery",
                                watch = device.toWatchRef(),
                                data = mapOf("level" to level.toString()),
                            )
                        }
                    }
                }
                // Evict departed watches so the maps don't leak and a reconnect re-emits.
                lastBattery.keys.retainAll(present)
                lastFailure.keys.retainAll(presentIds)
            }
        }
    }
}
