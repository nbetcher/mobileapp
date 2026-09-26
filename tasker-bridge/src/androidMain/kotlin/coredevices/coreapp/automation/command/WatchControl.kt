package coredevices.coreapp.automation.command

import io.rebble.libpebblecommon.automation.RemoteInputButton
import io.rebble.libpebblecommon.automation.RemoteInputResult
import io.rebble.libpebblecommon.automation.RemoteInputSwipeDirection
import io.rebble.libpebblecommon.automation.TimeSyncResult
import io.rebble.libpebblecommon.automation.WatchPrefSupport
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Instant

enum class RemoteInputOutcome { OK, BUSY, INVALID, UNSUPPORTED, TIMEOUT }

sealed interface TimeSyncOutcome {
    data class Verified(val skewSeconds: Long) : TimeSyncOutcome
    data class Mismatch(val skewSeconds: Long) : TimeSyncOutcome
    data object Timeout : TimeSyncOutcome
}

enum class PrefSupport(val wire: String) {
    SUPPORTED("supported"),
    UNSUPPORTED("unsupported"),
    /** Never written to this watch, so it has not said either way. */
    UNKNOWN("unknown"),
}

/** Watch operations the bridge needs beyond the plain LibPebble API, behind a seam for testing. */
interface WatchControl {
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
    override suspend fun reboot(device: ConnectedPebbleDevice) = device.reset()

    override suspend fun pressButton(device: ConnectedPebbleDevice, button: Int, presses: Int, holdMs: Int, gapMs: Int): RemoteInputOutcome {
        val target = RemoteInputButton.entries.firstOrNull { it.id.toInt() == button } ?: return RemoteInputOutcome.INVALID
        return device.pressButton(target, presses, holdMs, gapMs).toOutcome()
    }

    override suspend fun swipe(device: ConnectedPebbleDevice, direction: Int, durationMs: Int): RemoteInputOutcome {
        val target = RemoteInputSwipeDirection.entries.firstOrNull { it.id.toInt() == direction } ?: return RemoteInputOutcome.INVALID
        return device.swipe(target, durationMs).toOutcome()
    }

    override suspend fun syncTime(device: ConnectedPebbleDevice): TimeSyncOutcome = when (val result = device.updateTimeVerified()) {
        is TimeSyncResult.Success -> TimeSyncOutcome.Verified(result.skewSeconds)
        is TimeSyncResult.Mismatch -> TimeSyncOutcome.Mismatch(result.skewSeconds)
        TimeSyncResult.Timeout -> TimeSyncOutcome.Timeout
    }

    override fun firmwareCheckedAt(device: CommonConnectedDevice): Instant? = device.firmwareUpdateAvailable.checkedAt

    override fun prefSupport(device: CommonConnectedDevice, prefId: String): PrefSupport {
        if (ProtocolCapsFlag.SupportsBlobDbVersion !in device.capabilities) return PrefSupport.UNSUPPORTED
        val support = device as? WatchPrefSupport ?: return PrefSupport.UNKNOWN
        return when (prefId) {
            in support.rejectedWatchPrefs.value -> PrefSupport.UNSUPPORTED
            in support.acceptedWatchPrefs.value -> PrefSupport.SUPPORTED
            else -> PrefSupport.UNKNOWN
        }
    }

    override suspend fun writePrefAndAwait(device: CommonConnectedDevice, prefId: String, timeoutMs: Long, write: () -> Unit): PrefSupport {
        val support = device as? WatchPrefSupport
        if (support == null) {
            write()
            return prefSupport(device, prefId)
        }
        val outcome = coroutineScope {
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) { support.watchPrefSyncOutcomes.first { it.prefId == prefId } }
            }
            write()
            answer.await()
        }
        // An unchanged value is never re-sent, so no answer is not a refusal.
        return when (outcome?.accepted) {
            true -> PrefSupport.SUPPORTED
            false -> PrefSupport.UNSUPPORTED
            null -> prefSupport(device, prefId)
        }
    }

    private fun RemoteInputResult.toOutcome(): RemoteInputOutcome = when (this) {
        RemoteInputResult.Ok -> RemoteInputOutcome.OK
        RemoteInputResult.Busy -> RemoteInputOutcome.BUSY
        RemoteInputResult.Invalid -> RemoteInputOutcome.INVALID
        RemoteInputResult.Unsupported -> RemoteInputOutcome.UNSUPPORTED
        RemoteInputResult.Timeout -> RemoteInputOutcome.TIMEOUT
    }
}
