package coredevices.coreapp.automation.service

import android.content.Context
import coredevices.coreapp.automation.events.WatchRef
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble

/** Read-only snapshot source for getState (HLDD-002 §4.6). */
interface StateProvider {
    fun watchRefs(): List<WatchRef>
    fun appVersion(): String
}

class LibPebbleStateProvider(
    private val libPebble: LibPebble,
    private val context: Context,
) : StateProvider {

    override fun watchRefs(): List<WatchRef> =
        libPebble.watches.value.filterIsInstance<CommonConnectedDevice>().map { device ->
            WatchRef(
                serial = device.serial,
                name = device.name,
                nickname = device.nickname,
                model = device.watchInfo.board,
                fw = device.runningFwVersion,
                battery = device.batteryLevel,
                address = device.identifier.asString,
            )
        }

    override fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
