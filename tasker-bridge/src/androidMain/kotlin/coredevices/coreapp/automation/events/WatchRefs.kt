package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.CommonConnectedDevice

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
