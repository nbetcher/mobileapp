package coredevices.coreapp.automation.command

import io.mockk.*
import io.rebble.libpebblecommon.automation.RemoteInputButton
import io.rebble.libpebblecommon.automation.RemoteInputResult
import io.rebble.libpebblecommon.automation.RemoteInputSwipeDirection
import io.rebble.libpebblecommon.automation.TimeSyncResult
import io.rebble.libpebblecommon.automation.WatchPrefSyncOutcome
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LibPebbleWatchControlTest {
    private val outcomes = MutableSharedFlow<WatchPrefSyncOutcome>(extraBufferCapacity = 8)
    private val device = mockk<ConnectedPebbleDevice>(relaxed = true) {
        every { capabilities } returns setOf(ProtocolCapsFlag.SupportsBlobDbVersion)
        every { acceptedWatchPrefs } returns MutableStateFlow(setOf("clock24h"))
        every { rejectedWatchPrefs } returns MutableStateFlow(setOf("lightColor"))
        every { watchPrefSyncOutcomes } returns outcomes
    }
    private val control = LibPebbleWatchControl()

    @Test fun remoteInputAndTimeSyncMapOneToOne() = runTest {
        coEvery { device.pressButton(RemoteInputButton.Select, 2, 50, 100) } returns RemoteInputResult.Busy
        assertEquals(RemoteInputOutcome.BUSY, control.pressButton(device, 2, 2, 50, 100))
        coEvery { device.swipe(RemoteInputSwipeDirection.Left, 150) } returns RemoteInputResult.Unsupported
        assertEquals(RemoteInputOutcome.UNSUPPORTED, control.swipe(device, 2, 150))
        assertEquals(RemoteInputOutcome.INVALID, control.pressButton(device, 9, 1, 50, 100))

        coEvery { device.updateTimeVerified() } returns TimeSyncResult.Success(1)
        assertEquals(TimeSyncOutcome.Verified(1), control.syncTime(device))
        coEvery { device.updateTimeVerified() } returns TimeSyncResult.Mismatch(90)
        assertEquals(TimeSyncOutcome.Mismatch(90), control.syncTime(device))
        coEvery { device.updateTimeVerified() } returns TimeSyncResult.Timeout
        assertEquals(TimeSyncOutcome.Timeout, control.syncTime(device))

        control.reboot(device)
        verify(exactly = 1) { device.reset() }
    }

    @Test fun prefSupportComesFromTheWatchsSyncHistory() {
        assertEquals(PrefSupport.SUPPORTED, control.prefSupport(device, "clock24h"))
        assertEquals(PrefSupport.UNSUPPORTED, control.prefSupport(device, "lightColor"))
        assertEquals(PrefSupport.UNKNOWN, control.prefSupport(device, "dndManuallyEnabled"))
        every { device.capabilities } returns emptySet()
        assertEquals(PrefSupport.UNSUPPORTED, control.prefSupport(device, "clock24h"))
    }

    @Test fun writeWaitsForThisKeysAnswerAndFallsBackOnSilence() = runTest {
        var writes = 0
        val accepted = control.writePrefAndAwait(device, "dndManuallyEnabled", 3_000) {
            writes++
            launch {
                outcomes.emit(WatchPrefSyncOutcome("clock24h", accepted = false))
                outcomes.emit(WatchPrefSyncOutcome("dndManuallyEnabled", accepted = true))
            }
        }
        assertEquals(PrefSupport.SUPPORTED, accepted)

        val refused = control.writePrefAndAwait(device, "dndManuallyEnabled", 3_000) {
            writes++
            launch { outcomes.emit(WatchPrefSyncOutcome("dndManuallyEnabled", accepted = false)) }
        }
        assertEquals(PrefSupport.UNSUPPORTED, refused)

        assertEquals(PrefSupport.SUPPORTED, control.writePrefAndAwait(device, "clock24h", 3_000) { writes++ })
        assertEquals(3, writes)
    }
}
