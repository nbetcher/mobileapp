package coredevices.coreapp.automation.command

import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.ResetMessage
import kotlin.time.Instant

enum class RemoteInputOutcome { OK, BUSY, INVALID, UNSUPPORTED, TIMEOUT }

sealed interface TimeSyncOutcome {
    data class Verified(val skewSeconds: Long) : TimeSyncOutcome
    data class Mismatch(val skewSeconds: Long) : TimeSyncOutcome
    data object Timeout : TimeSyncOutcome
    /** Sent, but the watch's clock could not be read back. */
    data object Unverified : TimeSyncOutcome
}

enum class PrefSupport(val wire: String) {
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported"),
    /** Never written to this watch, so it has not said either way. */
    UNKNOWN("unknown"),
}

/**
 * Watch operations the bridge needs beyond the plain LibPebble API. Several depend on libpebble3
 * additions that have not landed yet; until they do, the adapter reports them as unavailable/unknown.
 */
interface WatchControl {
    val remoteInputAvailable: Boolean
    suspend fun reboot(device: ConnectedPebbleDevice)
    suspend fun pressButton(device: ConnectedPebbleDevice, button: Int, presses: Int, holdMs: Int, gapMs: Int): RemoteInputOutcome
    suspend fun swipe(device: ConnectedPebbleDevice, direction: Int, durationMs: Int): RemoteInputOutcome
    suspend fun syncTime(device: ConnectedPebbleDevice): TimeSyncOutcome
    /** When the last successful firmware update check for [device] finished, if known. */
    fun firmwareCheckedAt(device: CommonConnectedDevice): Instant?
    fun prefSupport(device: CommonConnectedDevice, prefId: String): PrefSupport
    /** Runs [write] and waits up to [timeoutMs] for [device] to accept or reject [prefId]. */
    suspend fun writePrefAndAwait(device: CommonConnectedDevice, prefId: String, timeoutMs: Long, write: () -> Unit): PrefSupport
}

class LibPebbleWatchControl : WatchControl {
    override val remoteInputAvailable: Boolean = false

    override suspend fun reboot(device: ConnectedPebbleDevice) = device.sendPPMessage(ResetMessage.Reset)

    override suspend fun pressButton(device: ConnectedPebbleDevice, button: Int, presses: Int, holdMs: Int, gapMs: Int) =
        RemoteInputOutcome.UNSUPPORTED

    override suspend fun swipe(device: ConnectedPebbleDevice, direction: Int, durationMs: Int) =
        RemoteInputOutcome.UNSUPPORTED

    override suspend fun syncTime(device: ConnectedPebbleDevice): TimeSyncOutcome {
        device.updateTime()
        return TimeSyncOutcome.Unverified
    }

    override fun firmwareCheckedAt(device: CommonConnectedDevice): Instant? = null

    override fun prefSupport(device: CommonConnectedDevice, prefId: String): PrefSupport =
        if (ProtocolCapsFlag.SupportsBlobDbVersion in device.capabilities) PrefSupport.UNKNOWN else PrefSupport.UNSUPPORTED

    override suspend fun writePrefAndAwait(device: CommonConnectedDevice, prefId: String, timeoutMs: Long, write: () -> Unit): PrefSupport {
        write()
        return prefSupport(device, prefId)
    }
}
