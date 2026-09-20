package coredevices.coreapp.automation.events

import coredevices.coreapp.automation.AutomationSettings
import coredevices.coreapp.automation.BridgeJson
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Paths
import coredevices.coreapp.automation.service.LibPebbleStateProvider
import io.mockk.*
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.automation.AutomationNotificationHooks
import io.rebble.libpebblecommon.connection.*
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.database.dao.HealthAggregates
import kotlin.uuid.Uuid
import io.rebble.libpebblecommon.packets.AppMessageTuple
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalUnsignedTypes::class)
class CollectorRegressionTest {
    private fun retainFixtures(name: String, events: List<EventEnvelope>) {
        val directory = Paths.get("build", "collector-fixtures")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("$name.json"), BridgeJson.json.encodeToString(ListSerializer(EventEnvelope.serializer()), events))
    }

    private val address = "AA:BB".asPebbleBleIdentifier()
    private fun watch(serialId: String = "SERIAL", fw: FirmwareUpdateStatus = FirmwareUpdateStatus.NotInProgress.Idle(), dev: MutableStateFlow<Boolean> = MutableStateFlow(false)): CommonConnectedDevice = mockk(relaxed = true) {
        every { identifier } returns address
        every { serial } returns serialId
        every { name } returns "Pebble"
        every { nickname } returns null
        every { watchInfo.board } returns "snowy"
        every { runningFwVersion } returns "4.0"
        every { batteryLevel } returns 80
        every { connectionFailureInfo } returns null
        every { firmwareUpdateState } returns fw
        every { devConnectionActive } returns dev
    }

    @Test fun connectDisconnectPreservesSerialAndResetsRepeatedFailure() = runTest {
        val lp = mockk<LibPebble>()
        val connected = watch()
        val devices = MutableStateFlow<List<PebbleDevice>>(emptyList())
        val transitions = MutableSharedFlow<PebbleConnectionEvent>()
        every { lp.watches } returns devices
        every { lp.connectionEvents } returns transitions
        val d = EventDispatcher("test")
        ConnectivityCollector(lp, d).start(backgroundScope)
        runCurrent()
        devices.value = listOf(connected)
        transitions.emit(PebbleConnectionEvent.PebbleConnectedEvent(connected))
        runCurrent()
        val failure = ConnectionFailureInfo(ConnectionFailureReason.FailedToConnect, 1)
        val failed = mockk<KnownPebbleDevice>(relaxed = true) {
            every { identifier } returns address
            every { serial } returns "SERIAL"
            every { name } returns "Pebble"
            every { nickname } returns null
            every { connectionFailureInfo } returns failure
        }
        devices.value = listOf(failed)
        transitions.emit(PebbleConnectionEvent.PebbleDisconnectedEvent(address))
        runCurrent()
        devices.value = listOf(connected)
        runCurrent()
        devices.value = listOf(failed)
        runCurrent()
        val events = d.since(0)
        assertEquals(listOf("SERIAL", "SERIAL"), events.filter { it.type in listOf("watch.connected", "watch.disconnected") }.map { it.watch?.serial })
        assertEquals(2, events.count { it.type == "watch.state" })
        assertEquals(events.indices.map { it + 1L }, events.map { it.seq })
        retainFixtures("connectivity", events)
    }

    @Test fun initialProgressUpdatesAndTerminalStatesArePerWatch() = runTest {
        val lp = mockk<LibPebble>()
        val progress = MutableStateFlow(0.25f)
        val fw = FirmwareUpdateStatus.InProgress(mockk(), progress)
        val dev = MutableStateFlow(true)
        val device = watch(fw = fw, dev = dev)
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(device))
        every { lp.watches } returns devices
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        assertEquals("true", d.since(0).first { it.type == "dev.state" }.data["enabled"])
        assertEquals("25", d.since(0).first { it.type == "fw.status" }.data["progress"])
        progress.value = 0.71f
        runCurrent()
        assertEquals("71", d.since(0).last { it.type == "fw.status" }.data["progress"])
        devices.value = emptyList()
        runCurrent()
        dev.value = true
        progress.value = 1f
        runCurrent()
        assertEquals("false", d.since(0).last { it.type == "dev.state" }.data["enabled"])
        assertEquals("unavailable", d.since(0).last { it.type == "fw.status" }.data["status"])
        assertTrue(d.since(0).all { it.watch?.serial == "SERIAL" })
        retainFixtures("firmware-dev", d.since(0))
    }

    @Test fun longLivedObserverUsesCurrentSnapshotMetadata() = runTest {
        val lp = mockk<LibPebble>()
        val dev = MutableStateFlow(false)
        val original = watch(dev = dev)
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(original))
        every { lp.watches } returns devices
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        val replacement = watch(fw = FirmwareUpdateStatus.InProgress(mockk(), MutableStateFlow(0.3f)), dev = dev)
        every { replacement.batteryLevel } returns 50
        devices.value = listOf(replacement)
        runCurrent()
        dev.value = true
        runCurrent()
        val ref = d.since(0).last { it.type == "dev.state" }.watch!!
        assertEquals(50, ref.battery)
        assertEquals("in_progress", ref.fwStatus)
        assertEquals(30, ref.fwProgress)
    }

    @Test fun queuedOldConnectionUpdateCannotFollowDisconnectOrReplacement() = runTest {
        for (replacement in listOf(false, true)) {
            val lp = mockk<LibPebble>()
            val oldDev = MutableStateFlow(false)
            val old = watch(dev = oldDev)
            val devices = MutableStateFlow<List<PebbleDevice>>(listOf(old))
            every { lp.watches } returns devices
            val d = EventDispatcher("test")
            val observerScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
            try {
                PerWatchCollector(lp, d).start(observerScope)
                runCurrent()
                val before = d.latestSeq
                oldDev.value = true // queued before the device-list collector can cancel it
                devices.value = if (replacement) listOf(watch(dev = MutableStateFlow(false))) else emptyList()
                d.emit("connectivity", if (replacement) "watch.connected" else "watch.disconnected", old.toWatchRef())
                runCurrent()
                assertFalse(d.since(before).any { it.type == "dev.state" && it.data["enabled"] == "true" },
                    "obsolete connection emitted after transition (replacement=$replacement)")
            } finally { observerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }

    @Test fun queuedFirmwareProgressCannotFollowAnAlreadyPublishedIdleSnapshot() = runTest {
        val lp = mockk<LibPebble>()
        val progress = MutableStateFlow(0.2f)
        val dev = MutableStateFlow(false)
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(watch(
            fw = FirmwareUpdateStatus.InProgress(mockk(), progress), dev = dev)))
        every { lp.watches } returns devices
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        val before = d.latestSeq
        progress.value = 0.9f
        devices.value = listOf(watch(dev = dev))
        runCurrent()
        assertEquals(listOf("idle"), d.since(before).filter { it.type == "fw.status" }.map { it.data["status"] })
    }

    @Test fun replacementConnectionRestartsFirmwareObserverEvenForEqualStatus() = runTest {
        val lp = mockk<LibPebble>()
        val progress = MutableStateFlow(0.2f)
        val fw = FirmwareUpdateStatus.InProgress(mockk(), progress)
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(watch("OLD", fw = fw)))
        every { lp.watches } returns devices
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        val before = d.latestSeq
        devices.value = listOf(watch("NEW", fw = fw))
        runCurrent()
        progress.value = 0.8f
        runCurrent()
        val events = d.since(before).filter { it.type == "fw.status" }
        assertEquals(listOf("20", "80"), events.map { it.data["progress"] })
        assertTrue(events.all { it.watch?.serial == "NEW" })
    }

    @Test fun queuedMediaAndCompletedMetadataCannotEmitAfterDisconnect() = runTest {
        val lp = mockk<LibPebble>()
        val uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val metadata = kotlinx.coroutines.CompletableDeferred<LockerWrapper?>()
        val media = MutableSharedFlow<io.rebble.libpebblecommon.music.MusicAction>(extraBufferCapacity = 1)
        val device = mockk<ConnectedPebbleDevice>(relaxed = true) {
            every { identifier } returns address
            every { serial } returns "SERIAL"
            every { name } returns "Pebble"
            every { firmwareUpdateState } returns FirmwareUpdateStatus.NotInProgress.Idle()
            every { devConnectionActive } returns MutableStateFlow(false)
            every { runningApp } returns MutableStateFlow<Uuid?>(uuid)
            every { musicActions } returns media
        }
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(device))
        every { lp.watches } returns devices
        every { lp.getLockerApp(uuid) } returns flow { emit(metadata.await()) }
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        val before = d.latestSeq
        metadata.complete(null)
        assertTrue(media.tryEmit(io.rebble.libpebblecommon.music.MusicAction.Play))
        devices.value = emptyList()
        d.emit("connectivity", "watch.disconnected", device.toWatchRef())
        runCurrent()
        assertFalse(d.since(before).any { it.type in setOf("apps.run_state", "media.command") })
    }

    @Test fun removingOneWatchDoesNotResetAnotherWatch() = runTest {
        val lp = mockk<LibPebble>()
        val firstDev = MutableStateFlow(true)
        val secondDev = MutableStateFlow(true)
        val first = watch("ONE", dev = firstDev)
        val second = watch("TWO", dev = secondDev)
        every { second.identifier } returns "CC:DD".asPebbleBleIdentifier()
        val devices = MutableStateFlow<List<PebbleDevice>>(listOf(first, second))
        every { lp.watches } returns devices
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        devices.value = listOf(second)
        runCurrent()
        secondDev.value = false
        runCurrent()
        val terminal = d.since(0).filter { it.type == "fw.status" && it.data["status"] == "unavailable" }
        assertEquals(listOf("ONE"), terminal.map { it.watch?.serial })
        assertEquals("false", d.since(0).last { it.type == "dev.state" && it.watch?.serial == "TWO" }.data["enabled"])
        assertEquals("true", d.since(0).last { it.type == "dev.state" && it.watch?.serial == "TWO" }.data["available"])
    }

    @Test fun snapshotReadsCurrentDevFirmwareAndBluetoothWithoutPastEvents() {
        val lp = mockk<LibPebble>()
        val dev = MutableStateFlow(true)
        every { lp.watches } returns MutableStateFlow(listOf<PebbleDevice>(watch(dev = dev)))
        every { lp.bluetoothEnabled } returns MutableStateFlow(BluetoothState.Enabled)
        val provider = LibPebbleStateProvider(lp, mockk())
        assertEquals(true, provider.state().watches.single().devEnabled)
        dev.value = false
        assertEquals(false, provider.state().watches.single().devEnabled)
        assertEquals("idle", provider.state().watches.single().fwStatus)
        assertEquals(true, provider.state().bluetoothEnabled)
        assertFalse("state.dnd" in provider.state().capabilities)
        assertFalse("state.watchface" in provider.state().capabilities)
    }

    @Test fun notificationPayloadUsesRunnerNamesAndExplicitRedaction() = runTest {
        val enabled = MutableStateFlow(true)
        val redact = MutableStateFlow(false)
        val settings = mockk<AutomationSettings> {
            every { notificationContentEnabled } returns enabled
            every { redactNotificationContent } returns redact
        }
        val d = EventDispatcher("test")
        NotificationCollector(d, settings).start(backgroundScope)
        try {
            AutomationNotificationHooks.onSent!!("pkg", "title", "body")
            assertEquals(mapOf("pkg" to "pkg", "title" to "title", "text" to "body", "redacted" to "false", "title_shared" to "true"), d.since(0).last().data)
            redact.value = true
            AutomationNotificationHooks.onSent!!("pkg", "secret title", "secret body")
            assertEquals(mapOf("pkg" to "pkg", "redacted" to "true", "title_shared" to "true", "title" to "secret title"), d.since(0).last().data)
            AutomationNotificationHooks.onAction!!("Dismiss", "pkg", 2)
            assertEquals("dismiss", d.since(0).last().data["action"])
            assertEquals("2", d.since(0).last().data["action_id"])
            assertEquals("pkg", d.since(0).last().data["pkg"])
            retainFixtures("notifications", d.since(0))
        } finally {
            AutomationNotificationHooks.onSent = null
            AutomationNotificationHooks.onAction = null
        }
    }

    @Test fun dictionaryIsTypedUnsignedAndHasStableWatchIdentity() = runTest {
        val lp = mockk<LibPebble>()
        every { lp.watches } returns MutableStateFlow(listOf<PebbleDevice>(watch()))
        val d = EventDispatcher("test")
        AppMessageCollector(d, lp).start(backgroundScope)
        try {
            assertTrue(AutomationAppMessageHook.onReceived!!("AA:BB", "uuid", 255, mapOf(-1 to "button", 3 to byteArrayOf(0, -1), 4 to 42)))
            val event = d.since(0).single()
            assertEquals("SERIAL", event.watch?.serial)
            assertEquals("255", event.data["transaction_id"])
            assertEquals("{\"4294967295\":\"button\",\"3\":\"00ff\",\"4\":42}", event.data["dict_json"])
            retainFixtures("appmessage", d.since(0))
            d.categoryGate = { false }
            assertFalse(AutomationAppMessageHook.onReceived!!("AA:BB", "uuid", 1, emptyMap()))
        } finally { AutomationAppMessageHook.onReceived = null }
    }

    @Test fun frameworkTupleDecodedLongAndULongReachTypedJson() {
        val tuples = listOf(
            AppMessageTuple.createUInt(UInt.MAX_VALUE, UInt.MAX_VALUE),
            AppMessageTuple.createInt(1u, -123),
            AppMessageTuple.createString(2u, "123"),
            AppMessageTuple.createUByteArray(3u, ubyteArrayOf(0u, 255u)),
        )
        val dictionary = tuples.associate { it.key.get().toInt() to it.getTypedData() }
        val payload = dictionaryPayload("uuid", 255, dictionary)
        assertEquals("{\"4294967295\":4294967295,\"1\":-123,\"2\":\"123\",\"3\":\"00ff\"}", payload["dict_json"])
        assertEquals("{\"4294967295\":\"uint\",\"1\":\"int\",\"2\":\"string\",\"3\":\"bytes\"}", payload["dict_types_json"])
    }

    @Test fun appEventUsesActualPerWatchTypeNameAndPreviousIdentity() = runTest {
        val lp = mockk<LibPebble>()
        val first = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val second = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val running = MutableStateFlow<Uuid?>(first)
        val base = watch()
        val device = mockk<ConnectedPebbleDevice>(relaxed = true) {
            every { identifier } returns address
            every { serial } returns "SERIAL"
            every { name } returns "Pebble"
            every { nickname } returns null
            every { watchInfo } returns base.watchInfo
            every { runningFwVersion } returns "4.0"
            every { batteryLevel } returns 80
            every { firmwareUpdateState } returns FirmwareUpdateStatus.NotInProgress.Idle()
            every { devConnectionActive } returns MutableStateFlow(false)
            every { runningApp } returns running
            every { musicActions } returns emptyFlow()
        }
        every { lp.watches } returns MutableStateFlow(listOf<PebbleDevice>(device))
        val face = mockk<LockerWrapper> { every { properties.type } returns AppType.Watchface; every { properties.title } returns "Face" }
        val app = mockk<LockerWrapper> { every { properties.type } returns AppType.Watchapp; every { properties.title } returns "App" }
        every { lp.getLockerApp(first) } returns flowOf(face)
        every { lp.getLockerApp(second) } returns flowOf(app)
        val d = EventDispatcher("test")
        PerWatchCollector(lp, d).start(backgroundScope)
        runCurrent()
        running.value = second
        runCurrent()
        val events = d.since(0).filter { it.type == "apps.run_state" }
        assertEquals(listOf("watchface", "watchapp"), events.map { it.data["app_type"] })
        assertEquals(listOf("Face", "App"), events.map { it.data["app_name"] })
        assertTrue(events.all { it.watch?.serial == "SERIAL" })
        assertEquals(first.toString(), events.last().data["previous_uuid"])
        retainFixtures("apps", events)
    }

    @Test fun systemPayloadsUseCanonicalNamesAndHealthFailureDoesNotKillCollector() = runTest {
        val lp = mockk<LibPebble>()
        val calls = MutableStateFlow<Call?>(null)
        val errors = MutableSharedFlow<UserFacingError>()
        val health = MutableSharedFlow<Unit>()
        val bt = MutableStateFlow(BluetoothState.Disabled)
        every { lp.currentCall } returns calls
        every { lp.userFacingErrors } returns errors
        every { lp.healthDataUpdated } returns health
        every { lp.bluetoothEnabled } returns bt
        var fail = true
        val aggregates = mockk<HealthAggregates> { every { steps } returns 1234L }
        coEvery { lp.getTotalHealthData(any(), any()) } answers { if (fail) error("db unavailable") else aggregates }
        coEvery { lp.getLatestHeartRateReading() } returns LatestHeartRate(67, 123L)
        val d = EventDispatcher("test")
        SystemEventCollector(lp, d).start(backgroundScope)
        runCurrent()
        calls.value = Call.RingingCall("Caller", "123", 1u, {}, {})
        errors.emit(UserFacingError.FailedToScan("scan failed"))
        health.emit(Unit)
        runCurrent()
        fail = false
        health.emit(Unit)
        bt.value = BluetoothState.Enabled
        calls.value = null
        runCurrent()
        val events = d.since(0)
        assertEquals("Caller", events.first { it.type == "calls.state" }.data["caller_name"])
        assertEquals("ended", events.last { it.type == "calls.state" }.data["state"])
        assertEquals(mapOf("error_type" to "failed_to_scan", "message" to "scan failed"), events.single { it.type == "system.error" }.data)
        assertEquals("false", events.first { it.type == "health.updated" }.data["available"])
        assertEquals("1234", events.last { it.type == "health.updated" }.data["steps_today"])
        assertEquals("67", events.last { it.type == "health.updated" }.data["latest_hr"])
        assertEquals(listOf("false", "true"), events.filter { it.type == "bt.state" }.map { it.data["enabled"] })
        retainFixtures("system", events)
    }

    @Test fun firmwareWireVocabularyDoesNotUseClassNames() {
        val update = mockk<FirmwareUpdateCheckResult.FoundUpdate>()
        assertEquals("idle", FirmwareUpdateStatus.NotInProgress.Idle().wireStatus())
        assertEquals("failed", FirmwareUpdateStatus.NotInProgress.Idle(Exception()).wireStatus())
        assertEquals("waiting", FirmwareUpdateStatus.WaitingToStart(update).wireStatus())
        assertEquals("rebooting", FirmwareUpdateStatus.WaitingForReboot(update).wireStatus())
        assertEquals(100, FirmwareUpdateStatus.InProgress(update, MutableStateFlow(1.2f)).wireProgress())
    }
    @Test fun metadataDelayDoesNotCancelObservedAppTransitions() = runTest {
        val lp=mockk<LibPebble>()
        val a=Uuid.parse("00000000-0000-0000-0000-000000000001")
        val b=Uuid.parse("00000000-0000-0000-0000-000000000002")
        val running=MutableStateFlow<Uuid?>(a)
        val device=mockk<ConnectedPebbleDevice>(relaxed=true)
        every {device.identifier} returns address
        every {device.serial} returns "SERIAL"
        every {device.name} returns "Watch"
        every {device.devConnectionActive} returns MutableStateFlow(false)
        every {device.firmwareUpdateState} returns FirmwareUpdateStatus.NotInProgress.Idle()
        every {device.runningApp} returns running
        every {device.musicActions} returns emptyFlow()
        every {lp.watches} returns MutableStateFlow<List<PebbleDevice>>(listOf(device))
        every {lp.getLockerApp(a)} returns flow { kotlinx.coroutines.delay(1000); emit(null) }
        every {lp.getLockerApp(b)} returns flowOf(null)
        val dispatcher=EventDispatcher("boot")
        PerWatchCollector(lp,dispatcher).start(backgroundScope)
        runCurrent();running.value=b;runCurrent()
        advanceTimeBy(251);runCurrent()
        val edges=dispatcher.since(0).filter{it.type=="apps.run_state"}
        assertEquals(listOf(a.toString(),b.toString()),edges.map{it.data["uuid"]})
        assertEquals(a.toString(),edges.last().data["previous_uuid"])
        assertEquals(listOf(a.toString(),b.toString()),edges.map{it.watch?.currentAppUuid})
    }

}
