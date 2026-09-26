package io.rebble.libpebblecommon.web

import io.rebble.libpebblecommon.connection.FakeConnectedDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckState
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FirmwareUpdateManagerTest {
    private class Web(var result: FirmwareUpdateCheckResult) : WebServices {
        override suspend fun fetchLocker() = null
        override suspend fun removeFromLocker(id: Uuid) = false
        override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean) = result
        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}
        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {}
    }

    private val watchInfo = FakeConnectedDevice(
        "addr".asPebbleBleIdentifier(), FirmwareUpdateCheckState(false, null),
        FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle(), "Pebble", null, connectionFailureInfo = null,
    ).watchInfo

    @Test fun checkedAtAdvancesOnAnsweredChecksAndSurvivesFailures() = runTest {
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val clock = object : Clock { override fun now() = now }
        val web = Web(FirmwareUpdateCheckResult.FoundNoUpdate)
        val manager = RealFirmwareUpdateManager(web, ConnectionCoroutineScope(backgroundScope.coroutineContext), clock)
        manager.init(watchInfo)
        advanceTimeBy(2.seconds); runCurrent()
        val first = manager.availableUpdates.first()
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, first.result)
        assertEquals(now, first.checkedAt)

        val answeredAt = now
        now += 10.minutes
        web.result = FirmwareUpdateCheckResult.UpdateCheckFailed("offline")
        manager.checkForUpdates(force = true)
        advanceTimeBy(2.seconds); runCurrent()
        val failed = manager.availableUpdates.first()
        assertIs<FirmwareUpdateCheckResult.UpdateCheckFailed>(failed.result)
        assertEquals(answeredAt, failed.checkedAt)

        web.result = FirmwareUpdateCheckResult.FoundNoUpdate
        manager.checkForUpdates(force = true)
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(now, manager.availableUpdates.first().checkedAt)
    }
}
