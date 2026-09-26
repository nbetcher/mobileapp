package io.rebble.libpebblecommon.automation

import com.russhwolf.settings.MapSettings
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse.BlobStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchPrefSupportTrackerTest {
    private val settings = MapSettings()
    private fun tracker(address: String = "AA:BB", fw: String = "v4.38.2") =
        WatchPrefSupportTracker(address.asPebbleBleIdentifier(), settings).apply { init(fw) }

    @Test fun watchWritesAndSuccessfulInsertsProveSupport() {
        val tracker = tracker()
        tracker.recordWatchWrite("clock24h")
        tracker.recordInsertResult("dndManuallyEnabled", BlobStatus.Success)
        assertEquals(setOf("clock24h", "dndManuallyEnabled"), tracker.acceptedWatchPrefs.value)
        assertTrue(tracker.rejectedWatchPrefs.value.isEmpty())
    }

    @Test fun dataStaleRejectsOnlyKeysTheWatchHasNeverShown() {
        val tracker = tracker()
        tracker.recordWatchWrite("clock24h")
        tracker.recordInsertResult("clock24h", BlobStatus.DataStale)
        tracker.recordInsertResult("lightColor", BlobStatus.DataStale)
        assertEquals(setOf("clock24h"), tracker.acceptedWatchPrefs.value)
        assertEquals(setOf("lightColor"), tracker.rejectedWatchPrefs.value)

        // A stale write to a supported key is followed by the watch writing its newer value back.
        tracker.recordWatchWrite("lightColor")
        assertEquals(setOf("clock24h", "lightColor"), tracker.acceptedWatchPrefs.value)
        assertTrue(tracker.rejectedWatchPrefs.value.isEmpty())
    }

    @Test fun transientFailuresSayNothing() {
        val tracker = tracker()
        for (status in listOf(null, BlobStatus.GeneralFailure, BlobStatus.TryLater, BlobStatus.WatchDisconnected)) {
            tracker.recordInsertResult("clock24h", status)
        }
        assertTrue(tracker.acceptedWatchPrefs.value.isEmpty())
        assertTrue(tracker.rejectedWatchPrefs.value.isEmpty())
    }

    @Test fun outcomesAreEmittedPerInsert() = runTest {
        val tracker = tracker()
        val outcome = async { tracker.watchPrefSyncOutcomes.first() }
        runCurrent()
        tracker.recordInsertResult("lightColor", BlobStatus.DataStale)
        assertEquals(WatchPrefSyncOutcome("lightColor", accepted = false), outcome.await())
    }

    @Test fun knowledgePersistsPerWatchAndRefusalsResetOnFirmwareChange() {
        tracker().apply {
            recordWatchWrite("clock24h")
            recordInsertResult("lightColor", BlobStatus.DataStale)
        }
        tracker().apply {
            assertEquals(setOf("clock24h"), acceptedWatchPrefs.value)
            assertEquals(setOf("lightColor"), rejectedWatchPrefs.value)
        }
        assertTrue(tracker(address = "CC:DD").acceptedWatchPrefs.value.isEmpty())
        tracker(fw = "v4.39.0").apply {
            assertEquals(setOf("clock24h"), acceptedWatchPrefs.value)
            assertTrue(rejectedWatchPrefs.value.isEmpty())
        }
    }
}
