package coredevices.coreapp.automation.service

import android.content.Context
import coredevices.coreapp.automation.StateData
import coredevices.coreapp.automation.events.SUPPORTED_STATE_CAPABILITIES
import coredevices.coreapp.automation.events.toWatchRef
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import coredevices.coreapp.automation.events.WatchRef
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble

/** Read-only snapshot source for getState (HLDD-002 §4.6). */
interface StateProvider {
    fun watchRefs(): List<WatchRef>
    fun appVersion(): String
    fun supportedCapabilities(): List<String> = emptyList()
    fun state(): StateData = StateData(watchRefs(), capabilities = supportedCapabilities())
}

class LibPebbleStateProvider(
    private val libPebble: LibPebble,
    private val context: Context,
) : StateProvider {

    override fun watchRefs(): List<WatchRef> =
        libPebble.watches.value.filterIsInstance<CommonConnectedDevice>().map { it.toWatchRef() }

    override fun supportedCapabilities(): List<String> = SUPPORTED_STATE_CAPABILITIES

    override fun state(): StateData = StateData(
        watches = watchRefs(),
        bluetoothEnabled = libPebble.bluetoothEnabled.value == BluetoothState.Enabled,
        capabilities = supportedCapabilities(),
    )

    override fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
