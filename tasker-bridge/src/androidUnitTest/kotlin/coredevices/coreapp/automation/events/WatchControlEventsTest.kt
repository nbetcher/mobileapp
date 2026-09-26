package coredevices.coreapp.automation.events

import io.mockk.every
import io.mockk.mockk
import io.rebble.libpebblecommon.connection.*
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchControlEventsTest {
    @Test fun prefChangesAreEmittedButTheInitialSnapshotIsNot() = runTest {
        val prefs = MutableStateFlow<List<WatchPreference<*>>>(listOf(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, null)))
        val lp = mockk<LibPebble> { every { watchPrefs } returns prefs }
        val dispatcher = EventDispatcher("boot")
        WatchPrefCollector(lp, dispatcher).start(backgroundScope)
        runCurrent()
        assertTrue(dispatcher.snapshot(0).events.isEmpty())

        prefs.value = listOf(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, true))
        runCurrent()
        prefs.value = listOf(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, true))
        runCurrent()
        val event = dispatcher.snapshot(0).events.single()
        assertEquals("system", event.category)
        assertEquals("watch.pref", event.type)
        assertNull(event.watch)
        assertEquals(mapOf("pref_key" to "dndManuallyEnabled", "label" to "Quiet Time - Manual", "value" to "1", "previous" to "0"), event.data)
    }

    @Test fun firmwareOfferIsAnnouncedOncePerVersion() = runTest {
        fun found(version: String) = FirmwareUpdateCheckResult.FoundUpdate(
            mockk<FirmwareVersion> { every { stringVersion } returns version }, "https://example.invalid", "notes")
        fun watch(result: FirmwareUpdateCheckResult?): CommonConnectedDevice = mockk(relaxed = true) {
            every { identifier.asString } returns "AA:BB"
            every { serial } returns "SERIAL"
            every { name } returns "Pebble"
            every { runningFwVersion } returns "v4.38.0"
            every { firmwareUpdateState } returns FirmwareUpdateStatus.NotInProgress.Idle()
            every { devConnectionActive } returns MutableStateFlow(false)
            every { firmwareUpdateAvailable } returns FirmwareUpdateCheckState(false, result)
        }
        val devices = MutableStateFlow<List<PebbleDevice>>(emptyList())
        val lp = mockk<LibPebble> { every { watches } returns devices }
        val dispatcher = EventDispatcher("boot")
        FirmwareOfferCollector(lp, dispatcher).start(backgroundScope)
        for (state in listOf(found("v4.38.2"), found("v4.38.2"), null, found("v4.38.2"), FirmwareUpdateCheckResult.FoundNoUpdate, found("v4.38.2"), found("v4.39.0"))) {
            devices.value = listOf(watch(state))
            runCurrent()
            devices.value = emptyList()
            runCurrent()
        }
        val offers = dispatcher.snapshot(0).events.filter { it.type == "fw.available" }
        assertEquals(listOf("v4.38.2", "v4.38.2", "v4.39.0"), offers.map { it.data["version"] })
        assertEquals("v4.38.0", offers.first().data["current"])
        assertEquals("SERIAL", offers.first().watch?.serial)
    }
}
