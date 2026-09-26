package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Emits `system`/`fw.available` once per watch and offered version. A reconnect does not repeat the
 * announcement; only an explicit "no update" result clears it, so a later release is announced again.
 */
class FirmwareOfferCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            val announced = HashMap<String, String>()
            libPebble.watches.collect { devices ->
                for (device in devices.filterIsInstance<CommonConnectedDevice>()) {
                    val id = device.identifier.asString
                    when (val result = device.firmwareUpdateAvailable.result) {
                        is FirmwareUpdateCheckResult.FoundUpdate -> {
                            val version = result.version.stringVersion
                            if (announced.put(id, version) == version) continue
                            dispatcher.emit("system", "fw.available", device.toWatchRef(), mapOf(
                                "version" to version,
                                "current" to device.runningFwVersion,
                                "can_downgrade" to result.canDowngrade.toString(),
                                "notes" to result.notes.take(MAX_NOTES),
                            ))
                        }
                        FirmwareUpdateCheckResult.FoundNoUpdate -> announced.remove(id)
                        is FirmwareUpdateCheckResult.UpdateCheckFailed, null -> Unit
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_NOTES = 2_000
    }
}
