package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Maps [LibPebble] connectivity signals to `connectivity` envelopes (HLDD-001 §9):
 * watch.connected / watch.disconnected (from connectionEvents) and watch.battery (diffed off
 * the watches snapshot, which re-emits when any watch property changes).
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
            libPebble.watches.collect { devices ->
                for (device in devices) {
                    if (device is CommonConnectedDevice) {
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
            }
        }
    }
}
