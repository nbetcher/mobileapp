package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleDevice

/** Shared mapping from a connected device to the event [WatchRef] identity block (HLDD-002 §4.3). */
internal fun CommonConnectedDevice.toWatchRef(): WatchRef = WatchRef(
    serial = serial,
    name = name,
    nickname = nickname,
    model = watchInfo.board,
    fw = runningFwVersion,
    battery = batteryLevel,
    address = identifier.asString,
)

/**
 * Identity ref for a NON-connected device (e.g. a watch that just failed to connect), which has no
 * live model/fw/battery. Uses the known serial when the device has been paired before, else falls back
 * to the transport identifier so the client still has a stable handle to filter on (HLDD-002 §4.3).
 */
internal fun PebbleDevice.toIdentityRef(): WatchRef = WatchRef(
    serial = (this as? KnownPebbleDevice)?.serial ?: identifier.asString,
    name = name,
    nickname = nickname,
    address = identifier.asString,
)
