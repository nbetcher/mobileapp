package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.AutomationFiles
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import io.mockk.*
import io.rebble.libpebblecommon.connection.*
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WatchControlCommandsTest {
    private val control = mockk<WatchControl>(relaxed = true)
    private val jobs = mockk<AutomationJobs>(relaxed = true)
    private val files = mockk<AutomationFiles>(relaxed = true)
    private var nowMs = 0L
    private val instant = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock { override fun now() = instant }

    private fun watch(
        id: String = "A",
        available: FirmwareUpdateCheckResult? = null,
        status: FirmwareUpdateStatus = FirmwareUpdateStatus.NotInProgress.Idle(),
        running: Uuid? = null,
    ): ConnectedPebbleDevice = mockk(relaxed = true) {
        every { serial } returns id
        every { identifier.asString } returns "address-$id"
        every { runningApp } returns MutableStateFlow(running)
        every { devConnectionActive } returns MutableStateFlow(false)
        every { firmwareUpdateAvailable } returns FirmwareUpdateCheckState(false, available)
        every { firmwareUpdateState } returns status
    }

    private var receivesJobs = true

    private fun commands(vararg watches: PebbleDevice, prefs: List<WatchPreference<*>> = emptyList()): WatchControlCommands {
        val lp = mockk<LibPebble>(relaxed = true) {
            every { this@mockk.watches } returns MutableStateFlow(watches.toList())
            every { watchPrefs } returns flowOf(prefs)
        }
        return WatchControlCommands(lp, control, jobs, files, Cooldowns { nowMs }, clock) { receivesJobs }
    }

    private suspend fun WatchControlCommands.run(type: String, vararg args: Pair<String, String>, identity: String = "pkg") =
        handle(CommandEnvelope(type = type, args = args.toMap()), identity)

    private fun ok(result: CommandResult) = assertIs<CommandResult.Ok>(result).data
    private fun failure(result: CommandResult) = assertIs<CommandResult.Failure>(result).code

    private fun update(version: String) = FirmwareUpdateCheckResult.FoundUpdate(
        version = mockk<FirmwareVersion> { every { stringVersion } returns version },
        url = "https://example.invalid/fw.pbz",
        notes = "notes",
    )

    @Test fun rebootCoolsDownForSixtySecondsPerWatch() = runTest {
        val a = watch("A"); val b = watch("B")
        val commands = commands(a, b)
        ok(commands.handle(CommandEnvelope(type = CommandCatalog.WATCH_REBOOT, watch = "A"), "pkg"))
        assertEquals(ErrorCode.RATE_LIMITED, failure(commands.handle(CommandEnvelope(type = CommandCatalog.WATCH_REBOOT, watch = "A"), "pkg")))
        ok(commands.handle(CommandEnvelope(type = CommandCatalog.WATCH_REBOOT, watch = "B"), "pkg"))
        nowMs = 60_000
        ok(commands.handle(CommandEnvelope(type = CommandCatalog.WATCH_REBOOT, watch = "A"), "pkg"))
        coVerify(exactly = 2) { control.reboot(a) }
        coVerify(exactly = 1) { control.reboot(b) }
    }

    @Test fun aFailedRebootDoesNotStartTheCooldown() = runTest {
        val a = watch("A")
        val commands = commands(a)
        coEvery { control.reboot(a) } throws IllegalStateException("closed")
        assertFailsWith<IllegalStateException> { commands.run(CommandCatalog.WATCH_REBOOT) }
        coEvery { control.reboot(a) } just Runs
        ok(commands.run(CommandCatalog.WATCH_REBOOT))
    }

    @Test fun unforcedFirmwareCheckReportsTheKnownResultAtOnce() = runTest {
        val a = watch("A", update("v4.38.2"))
        val commands = commands(a)
        val result = ok(commands.run(CommandCatalog.WATCH_CHECK_FIRMWARE, "force" to ""))
        assertEquals("available", result["status"])
        assertEquals("v4.38.2", result["version"])
        verify { a.checkforFirmwareUpdate(false) }
    }

    @Test fun unforcedFirmwareCheckDoesNotReplayAStaleFailure() = runTest {
        val a = watch("A", FirmwareUpdateCheckResult.UpdateCheckFailed("offline"))
        assertEquals("pending", ok(commands(a).run(CommandCatalog.WATCH_CHECK_FIRMWARE))["status"])
    }

    @Test fun forcedCheckFinishesEvenWhenTheCheckingStateIsNeverSeen() = runTest {
        val a = watch("A")
        val earlier = instant - 1.hours
        var state = FirmwareUpdateCheckState(false, FirmwareUpdateCheckResult.FoundNoUpdate, earlier)
        every { a.firmwareUpdateAvailable } answers { state }
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(a))
        val lp = mockk<LibPebble>(relaxed = true) { every { watches } returns devices }
        val commands = WatchControlCommands(lp, control, jobs, files, Cooldowns { nowMs }, clock)
        for ((next, status) in listOf(
            FirmwareUpdateCheckState(false, FirmwareUpdateCheckResult.FoundNoUpdate, instant) to "none",
            FirmwareUpdateCheckState(false, FirmwareUpdateCheckResult.UpdateCheckFailed("offline"), instant) to "failed",
        )) {
            backgroundScope.launch {
                delay(100)
                state = next
                devices.value = emptyList()
                yield()
                devices.value = listOf(a)
            }
            assertEquals(status, ok(commands.run(CommandCatalog.WATCH_CHECK_FIRMWARE, "force" to "true"))["status"])
        }
    }

    @Test fun pressButtonValidatesBeforeSendingAndMapsOutcomes() = runTest {
        val a = watch()
        val commands = commands(a)
        for (bad in listOf(mapOf("button" to "left"), mapOf("button" to "up", "presses" to "0"),
            mapOf("button" to "up", "hold_ms" to "70000"), mapOf("button" to "up", "gap_ms" to "-1"))) {
            assertEquals(ErrorCode.INVALID_ARGS, failure(commands.handle(CommandEnvelope(type = CommandCatalog.WATCH_PRESS_BUTTON, args = bad), "pkg")))
        }
        coVerify(exactly = 0) { control.pressButton(any(), any(), any(), any(), any()) }

        val expected = mapOf(
            RemoteInputOutcome.OK to null,
            RemoteInputOutcome.BUSY to ErrorCode.WATCH_BUSY,
            RemoteInputOutcome.INVALID to ErrorCode.INVALID_ARGS,
            RemoteInputOutcome.UNSUPPORTED to ErrorCode.UNSUPPORTED_COMMAND,
            RemoteInputOutcome.TIMEOUT to ErrorCode.TIMEOUT,
        )
        for ((outcome, code) in expected) {
            coEvery { control.pressButton(a, 2, 1, 50, 100) } returns outcome
            val result = commands.run(CommandCatalog.WATCH_PRESS_BUTTON, "button" to "select")
            if (code == null) assertEquals("true", ok(result)["accepted"]) else assertEquals(code, failure(result))
        }
        coEvery { control.pressButton(a, 0, 3, 1_000, 250) } returns RemoteInputOutcome.OK
        ok(commands.run(CommandCatalog.WATCH_PRESS_BUTTON, "button" to "back", "presses" to "3", "hold_ms" to "1000", "gap_ms" to "250"))
    }

    @Test fun swipeRejectsDurationsTheRecognizerCannotSee() = runTest {
        val a = watch()
        val commands = commands(a)
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands.run(CommandCatalog.WATCH_SWIPE, "direction" to "left", "duration_ms" to "301")))
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands.run(CommandCatalog.WATCH_SWIPE, "direction" to "sideways")))
        coEvery { control.swipe(a, 2, 150) } returns RemoteInputOutcome.OK
        ok(commands.run(CommandCatalog.WATCH_SWIPE, "direction" to "left"))
    }

    @Test fun syncTimeCooldownDependsOnVerifiedOutcome() = runTest {
        val a = watch()
        val commands = commands(a)
        coEvery { control.syncTime(a) } returns TimeSyncOutcome.Verified(0)
        assertEquals("true", ok(commands.run(CommandCatalog.WATCH_SYNC_TIME))["verified"])
        nowMs = 30 * 60_000L - 1
        assertEquals(ErrorCode.RATE_LIMITED, failure(commands.run(CommandCatalog.WATCH_SYNC_TIME)))
        nowMs = 30 * 60_000L

        coEvery { control.syncTime(a) } returns TimeSyncOutcome.Timeout
        assertEquals(ErrorCode.TIMEOUT, failure(commands.run(CommandCatalog.WATCH_SYNC_TIME)))
        nowMs += 29_999
        assertEquals(ErrorCode.RATE_LIMITED, failure(commands.run(CommandCatalog.WATCH_SYNC_TIME)))
        nowMs += 1
        coEvery { control.syncTime(a) } returns TimeSyncOutcome.Mismatch(9)
        assertEquals(ErrorCode.INTERNAL, failure(commands.run(CommandCatalog.WATCH_SYNC_TIME)))
    }

    @Test fun installFirmwareDistinguishesUnavailableFromStaleCheck() = runTest {
        val none = watch()
        val commands = commands(none)
        every { control.firmwareCheckedAt(none) } returns null
        assertEquals(ErrorCode.FIRMWARE_CHECK_STALE, failure(commands.run(CommandCatalog.WATCH_INSTALL_FIRMWARE)))
        every { control.firmwareCheckedAt(none) } returns instant - 25.hours
        assertEquals(ErrorCode.FIRMWARE_CHECK_STALE, failure(commands.run(CommandCatalog.WATCH_INSTALL_FIRMWARE)))
        every { control.firmwareCheckedAt(none) } returns instant - 1.hours
        assertEquals(ErrorCode.FIRMWARE_UPDATE_UNAVAILABLE, failure(commands.run(CommandCatalog.WATCH_INSTALL_FIRMWARE)))
        verify(exactly = 0) { none.updateFirmware(any()) }
    }

    @Test fun installFirmwareStartsOnlyTheExpectedIdleUpdate() = runTest {
        val found = update("v4.38.2")
        val busy = watch("B", found, FirmwareUpdateStatus.WaitingToStart(found))
        assertEquals(ErrorCode.WATCH_BUSY, failure(commands(busy).run(CommandCatalog.WATCH_INSTALL_FIRMWARE)))

        val a = watch("A", found)
        val commands = commands(a)
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands.run(CommandCatalog.WATCH_INSTALL_FIRMWARE, "version" to "v4.38.1")))
        verify(exactly = 0) { a.updateFirmware(any()) }
        assertEquals("v4.38.2", ok(commands.run(CommandCatalog.WATCH_INSTALL_FIRMWARE, "version" to "v4.38.2"))["version"])
        verify(exactly = 1) { a.updateFirmware(found) }
    }

    @Test fun prefsListWithLabelsValuesAndSupport() = runTest {
        val a = watch()
        val commands = commands(a, prefs = listOf(WatchPreference(BoolWatchPref.QuietTimeManuallyEnabled, true)))
        every { control.prefSupport(a, any()) } returns PrefSupport.UNKNOWN
        every { control.prefSupport(a, BoolWatchPref.Backlight.id) } returns PrefSupport.UNSUPPORTED

        val listed = Json.decodeFromString<List<PrefEntry>>(ok(commands.run(CommandCatalog.WATCH_LIST_PREFS))["prefs"]!!)
        val quiet = listed.single { it.key == "dndManuallyEnabled" }
        assertEquals("Quiet Time - Manual", quiet.label)
        assertEquals("boolean", quiet.type)
        assertEquals("1", quiet.value)
        assertEquals("0", quiet.default)
        assertEquals("unknown", quiet.support)
        assertEquals(listOf("1", "0"), quiet.options?.map { it.value })
        assertEquals("unsupported", listed.single { it.key == BoolWatchPref.Backlight.id }.support)

        val single = ok(commands.run(CommandCatalog.WATCH_GET_PREF, "pref_key" to "dndManuallyEnabled"))
        assertEquals("1", single["value"])
        assertEquals("Quiet Time - Manual", single["label"])
        assertEquals(ErrorCode.PREF_UNSUPPORTED, failure(commands.run(CommandCatalog.WATCH_GET_PREF, "pref_key" to BoolWatchPref.Backlight.id)))
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands.run(CommandCatalog.WATCH_GET_PREF, "pref_key" to "nope")))
    }

    @Test fun artifactCommandsNeedIdentityAndStartOneOwnedJob() = runTest {
        val a = watch()
        val commands = commands(a)
        for (type in listOf(CommandCatalog.WATCH_SCREENSHOT, CommandCatalog.WATCH_GATHER_LOGS)) {
            assertEquals(ErrorCode.NOT_AUTHORIZED, failure(commands.run(type, identity = "")))
            every { jobs.start(type, "address-A", "pkg", any(), any(), any()) } returns "job-1"
            assertEquals("job-1", ok(commands.run(type))["job_id"])
            every { jobs.start(type, "address-A", "pkg", any(), any(), any()) } returns null
            assertEquals(ErrorCode.WATCH_BUSY, failure(commands.run(type)))
        }
    }

    @Test fun artifactCommandsRefuseClientsThatCannotReceiveTheResult() = runTest {
        val a = watch()
        val commands = commands(a)
        receivesJobs = false
        for (type in listOf(CommandCatalog.WATCH_SCREENSHOT, CommandCatalog.WATCH_GATHER_LOGS)) {
            assertEquals(ErrorCode.CATEGORY_DISABLED, failure(commands.run(type)))
        }
        verify(exactly = 0) { jobs.start(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun stopAppDefaultsToTheRunningApp() = runTest {
        val idle = watch("I")
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands(idle).run(CommandCatalog.WATCH_STOP_APP)))
        val app = Uuid.random()
        val a = watch("A", running = app)
        assertEquals(app.toString(), ok(commands(a).run(CommandCatalog.WATCH_STOP_APP))["uuid"])
        coVerify(exactly = 1) { a.stopApp(app) }
    }

    @Test fun factoryResetRequiresTheTargetSerial() = runTest {
        val a = watch("SERIAL1")
        val commands = commands(a)
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands.run(CommandCatalog.WATCH_FACTORY_RESET)))
        assertEquals(ErrorCode.INVALID_ARGS, failure(commands.run(CommandCatalog.WATCH_FACTORY_RESET, "confirm_serial" to "OTHER")))
        verify(exactly = 0) { a.factoryReset() }
        ok(commands.run(CommandCatalog.WATCH_FACTORY_RESET, "confirm_serial" to "SERIAL1"))
        verify(exactly = 1) { a.factoryReset() }
    }
}
