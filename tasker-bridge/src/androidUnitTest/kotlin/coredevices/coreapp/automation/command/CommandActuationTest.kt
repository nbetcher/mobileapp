package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.events.EventDispatcher
import io.mockk.*
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.connection.*
import io.rebble.libpebblecommon.locker.*
import io.rebble.libpebblecommon.services.appmessage.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds

/** Real command dispatch and dictionary/result production; only the watch/DB boundary is mocked. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommandActuationTest {
    private val uuid = Uuid.parse("ed429c16-f674-4220-95da-454f303f15e2")
    private fun watch(id: String): ConnectedPebbleDevice = mockk(relaxed = true) {
        every { serial } returns id
        every { identifier.asString } returns "address-$id"
        every { runningApp } returns MutableStateFlow(null)
        every { devConnectionActive } returns MutableStateFlow(false)
        every { transactionSequence } answers { listOf(7.toUByte()).iterator() }
        coEvery { sendPing(42u) } returns 42u
    }
    private fun lp(vararg watches: PebbleDevice) = mockk<LibPebble>(relaxed = true) {
        every { this@mockk.watches } returns MutableStateFlow(watches.toList())
        every { getLocker(any(), any(), any()) } returns MutableStateFlow(emptyList())
        every { getLockerApp(any()) } returns MutableStateFlow(null)
    }
    private fun handler(lp: LibPebble) = LibPebbleCommandHandler(lp, EventDispatcher("commands"))
    private fun command(type: String, watch: String? = null, vararg args: Pair<String, String>) =
        CommandEnvelope(type = type, watch = watch, args = args.toMap())
    private fun ok(result: CommandResult) = assertIs<CommandResult.Ok>(result).data
    private fun failure(result: CommandResult, code: String = ErrorCode.INVALID_ARGS) = assertEquals(code, assertIs<CommandResult.Failure>(result).code)

    @Test fun pingAndLaunchTargetOnlySelectedWatchBySerialOrAddress() = runTest {
        val a = watch("A"); val b = watch("B"); val lp = lp(a, b)
        val clock = TestTimeSource()
        coEvery { b.sendPing(42u) } answers { clock += 37.milliseconds; 42u }
        val handler = LibPebbleCommandHandler(lp, EventDispatcher("commands"), clock)
        val ping = ok(handler.handle(command(CommandCatalog.SYSTEM_PING, "B", "cookie" to "42")))
        assertEquals("B", ping["serial"])
        assertEquals("37", ping["rtt_ms"])
        ok(handler.handle(command(CommandCatalog.WATCH_LAUNCH_APP, "address-B", "uuid" to uuid.toString())))
        coVerify(exactly = 1) { b.sendPing(42u); b.launchApp(uuid) }
        coVerify(exactly = 0) { a.sendPing(any()); a.launchApp(any()); lp.sendPing(any()); lp.launchApp(any()) }
    }

    @Test fun allPerWatchCommandsRejectMissingStaleAndAmbiguousTargets() = runTest {
        for (devices in listOf(emptyArray<PebbleDevice>(), arrayOf<PebbleDevice>(watch("A"), watch("B")))) {
            val lp = lp(*devices); val handler = handler(lp)
            for (type in CommandCatalog.types - CommandCatalog.globalTypes - CommandCatalog.APPMESSAGE_SUBSCRIBE) {
                val args = mapOf("uuid" to uuid.toString(), "dict_json" to """{"1":"value"}""")
                for (selector in listOf(null, "stale")) failure(handler.handle(CommandEnvelope(type = type, watch = selector, args = args)))
            }
            coVerify(exactly = 0) { lp.launchApp(any()); lp.sendPing(any()) }
        }
    }

    @Test fun globalCommandsRejectSelectorBeforeAnySideEffect() = runTest {
        val lp = lp(watch("A")); val handler = handler(lp)
        for (type in CommandCatalog.globalTypes) failure(handler.handle(command(type, "A")))
        verify { lp wasNot Called }
    }

    @Test fun connectAndDisconnectChooseOneWatch() = runTest {
        val a = watch("A"); val b = watch("B"); val handler = handler(lp(a,b))
        assertEquals("B", ok(handler.handle(command(CommandCatalog.WATCH_CONNECT, "B")))["serial"])
        assertEquals("B", ok(handler.handle(command(CommandCatalog.WATCH_DISCONNECT, "B")))["serial"])
        verify(exactly=1) { b.connect(); b.disconnect() }
        verify(exactly=0) { a.connect(); a.disconnect() }
    }

    @Test fun watchfaceValidationAndGetInfoUseSelectedRunningApp() = runTest {
        val a = watch("A"); val b = watch("B"); val lp = lp(a,b)
        val face = mockk<LockerWrapper.NormalApp> {
            every { properties.id } returns uuid
            every { properties.title } returns "Selected face"
            every { properties.type } returns AppType.Watchface
        }
        every { b.runningApp } returns MutableStateFlow(uuid)
        every { lp.getLocker(AppType.Watchface, null, 1000) } returns MutableStateFlow(listOf(face))
        every { lp.getLockerApp(uuid) } returns MutableStateFlow(face)
        val handler = handler(lp)
        val info = ok(handler.handle(command(CommandCatalog.WATCH_GET_INFO, "B")))
        assertEquals("Selected face", info["watchface"])
        assertEquals(uuid.toString(), info["watchface_uuid"])
        assertNull(ok(handler.handle(command(CommandCatalog.WATCH_GET_INFO, "A")))["watchface"])
        ok(handler.handle(command(CommandCatalog.WATCH_SET_WATCHFACE, "B", "uuid" to uuid.toString())))
        failure(handler.handle(command(CommandCatalog.WATCH_SET_WATCHFACE, "B", "uuid" to Uuid.random().toString())))
        coVerify(exactly=1) { b.launchApp(uuid) }
        coVerify(exactly=0) { a.launchApp(any()); lp.launchApp(any()) }
    }

    @Test fun appMessageDispatchPreservesTypesTransactionTargetAndAckNack() = runTest {
        val a = watch("A"); val b = watch("B"); val handler = handler(lp(a,b))
        val data = slot<AppMessageData>()
        coEvery { b.sendAppMessage(capture(data)) } returns AppMessageResult.ACK(7u)
        val cmd = command(CommandCatalog.APPMESSAGE_SEND, "B", "uuid" to uuid.toString(), "dict_json" to """{"1":"0123","2":"","3":{"type":"uint","value":4294967295},"4":{"type":"bytes","value":[0,255]}}""")
        assertEquals("true", ok(handler.handle(cmd))["acked"])
        assertEquals(uuid, data.captured.uuid)
        assertEquals(7.toUByte(), data.captured.transactionId)
        assertEquals("0123", data.captured.data[1]); assertEquals("", data.captured.data[2])
        assertEquals(UInt.MAX_VALUE, data.captured.data[3])
        assertContentEquals(byteArrayOf(0,-1), data.captured.data[4] as ByteArray)
        coEvery { b.sendAppMessage(any()) } returns AppMessageResult.NACK(7u)
        assertEquals("false", ok(handler.handle(cmd))["acked"])
        coVerify(exactly=0) { a.sendAppMessage(any()) }
    }

    @Test fun appMessageDeadlineIsInsideExecutorDeadlineAndCancelsWork() = runTest {
        val b = watch("B"); var cancelled = false
        coEvery { b.sendAppMessage(any()) } coAnswers {
            try { delay(10_000); AppMessageResult.ACK(7u) } finally { cancelled = true }
        }
        val start = currentTime
        failure(handler(lp(b)).handle(command(CommandCatalog.APPMESSAGE_SEND, "B", "uuid" to uuid.toString(), "dict_json" to """{"1":1}""")), ErrorCode.TIMEOUT)
        assertEquals(4500L, currentTime - start)
        assertTrue(cancelled)
    }

    @Test fun unsupportedTransportAndBadEnableNeverStartDeveloperConnection() = runTest {
        val b = watch("B"); val handler = handler(lp(b))
        for (transport in listOf("websocket", "lan", "bluetooth", "default")) {
            failure(handler.handle(command(CommandCatalog.DEV_TOGGLE_CONNECTION,"B","transport" to transport)), ErrorCode.UNSUPPORTED_COMMAND)
        }
        failure(handler.handle(command(CommandCatalog.DEV_TOGGLE_CONNECTION,"B","enable" to "garbage")))
        coVerify(exactly=0) { b.startDevConnection(any()); b.stopDevConnection() }
        assertEquals("true", ok(handler.handle(command(CommandCatalog.DEV_TOGGLE_CONNECTION,"B","enable" to "true")))["dev_enabled"])
        assertEquals("false", ok(handler.handle(command(CommandCatalog.DEV_TOGGLE_CONNECTION,"B","enable" to "false")))["dev_enabled"])
        coVerify(exactly=1) { b.startDevConnection(false); b.stopDevConnection() }
    }

    @Test fun badArgsAreRejectedWithoutCoercionOrMutation() = runTest {
        val b = watch("B"); val lp = lp(b); val handler = handler(lp)
        val commands = listOf(
            command(CommandCatalog.SYSTEM_PING,"B","cookie" to "-1"),
            command(CommandCatalog.WATCH_LAUNCH_APP,"B","uuid" to "bad"),
            command(CommandCatalog.WATCH_SET_PREF,null,"pref_key" to "clock24h","pref_value" to "garbage"),
            command(CommandCatalog.WATCH_SET_PREF,null,"pref_key" to "missing","pref_value" to "1"),
            command(CommandCatalog.WATCH_SET_PREF,null,"pref_key" to "lightTimeoutMs","pref_value" to "-1"),
            command(CommandCatalog.WATCH_SET_PREF,null,"pref_key" to "clock24h"),
            command(CommandCatalog.WATCH_SET_QUICK_LAUNCH,null,"button" to "up","press" to "typo"),
            command(CommandCatalog.WATCH_SET_QUICK_LAUNCH,null,"button" to "invalid"),
            command(CommandCatalog.WATCH_SET_QUICK_LAUNCH,null,"button" to "up","uuid" to "bad"),
            command(CommandCatalog.SYSTEM_GET_LOCKER,null,"type" to "unknown"),
            command(CommandCatalog.NOTIFICATION_SEND,null,"actions_json" to "not json"),
            command(CommandCatalog.NOTIFICATION_SEND,null,"actions_json" to """[{"id":"a","label":"A"},{"id":"a","label":"B"}]"""),
            command(CommandCatalog.APPMESSAGE_SEND,"B","uuid" to uuid.toString(),"dict_json" to """{"1":true}"""),
        )
        for (cmd in commands) failure(handler.handle(cmd))
        verify(exactly=0) { lp.setWatchPref(any()) }
        coVerify(exactly=0) { lp.sendNotification(any(),any()); b.launchApp(any()); b.sendAppMessage(any()); b.sendPing(any()) }
    }

    @Test fun unknownAndUnimplementedCommandsFailExplicitly() = runTest {
        val handler = handler(lp())
        for (type in listOf("unknown", "notification.muteApp", "timeline.insert", "timeline.delete", "watch.screenshot", "health.snapshot", "fw.check")) {
            failure(handler.handle(command(type)), ErrorCode.UNSUPPORTED_COMMAND)
            assertFalse(type in CommandCatalog.types)
        }
    }

    @Test fun subscriptionUsesVerifiedIdentityAndResolvesSerialToAddress() = runTest {
        val handler = handler(lp(watch("A"), watch("B")))
        AutomationAppMessageHook.clear()
        AutomationAppMessageHook.ownerAllowed = { it == "trusted.package" }
        try {
            val cmd = command(CommandCatalog.APPMESSAGE_SUBSCRIBE, "B", "uuid" to uuid.toString(), "owner" to "spoofed.package", "ownership" to "tasker")
            assertEquals("tasker", ok(handler.handle(cmd, "trusted.package"))["ownership"])
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address-B", uuid.toString()))
            assertTrue(AutomationAppMessageHook.hasAuthorizedOwnership("address-B", uuid.toString()))
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address-A", uuid.toString()))
            // Clearing caller-controlled owner must not remove the real owner's subscription.
            AutomationAppMessageHook.clearOwner("spoofed.package")
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address-B", uuid.toString()))
            ok(handler.handle(cmd.copy(args = cmd.args + ("enable" to "false")), "trusted.package"))
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address-B", uuid.toString()))
            ok(handler.handle(cmd.copy(watch = null), "trusted.package"))
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address-A", uuid.toString()))
            AutomationAppMessageHook.clearOwner("trusted.package")
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address-A", uuid.toString()))
            failure(handler.handle(cmd.copy(watch = "missing"), "trusted.package"))
            failure(handler.handle(cmd.copy(args = mapOf("uuid" to "bad")), "trusted.package"))
            failure(handler.handle(cmd.copy(args = cmd.args + ("enable" to "bad")), "trusted.package"))
            failure(handler.handle(cmd.copy(args = cmd.args + ("ownership" to "steal")), "trusted.package"))
            assertEquals("observe", ok(handler.handle(cmd.copy(args = cmd.args - "ownership"), "trusted.package"))["ownership"])
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address-B", uuid.toString()))
            assertFalse(AutomationAppMessageHook.hasAuthorizedOwnership("address-B", uuid.toString()))
            failure(handler.handle(cmd, ""), ErrorCode.NOT_AUTHORIZED)
        } finally {
            AutomationAppMessageHook.clear()
            AutomationAppMessageHook.ownerAllowed = null
        }
    }

    @Test fun lockerResultsContainAppUuidTitleTypeAndFilter() = runTest {
        val lp = lp()
        val app = mockk<LockerWrapper.NormalApp> {
            every { properties.id } returns uuid
            every { properties.title } returns "App title"
            every { properties.type } returns AppType.Watchapp
        }
        every { lp.getLocker(AppType.Watchapp, null, 1000) } returns MutableStateFlow(listOf(app))
        val handler = handler(lp)
        val entries = ok(handler.handle(command(CommandCatalog.SYSTEM_GET_LOCKER,null,"type" to "watchapp"))).getValue("entries")
        assertTrue(entries.contains(uuid.toString()))
        assertTrue(entries.contains("App title"))
        assertTrue(entries.contains("watchapp"))
        assertEquals("[]", ok(handler.handle(command(CommandCatalog.SYSTEM_GET_LOCKER,null,"type" to "watchface")))["entries"])
    }

    @Test fun mismatchedPingResponseIsNotSuccessfulRtt() = runTest {
        val b = watch("B")
        coEvery { b.sendPing(1u) } returns 2u
        failure(handler(lp(b)).handle(command(CommandCatalog.SYSTEM_PING,"B","cookie" to "1")), ErrorCode.INTERNAL)
    }

    @Test fun generatedPingCookiesDoNotReuseDefaultZero() = runTest {
        val b = watch("B"); val cookies = mutableListOf<UInt>()
        coEvery { b.sendPing(any()) } answers { firstArg<Int>().toUInt().also { cookies += it } }
        val handler = handler(lp(b))
        ok(handler.handle(command(CommandCatalog.SYSTEM_PING,"B")))
        ok(handler.handle(command(CommandCatalog.SYSTEM_PING,"B")))
        assertEquals(2, cookies.distinct().size)
    }
    @Test fun unavailableWatchDoesNotBlockOtherSubscriptions() = runTest {
        AutomationAppMessageHook.clear()
        AutomationAppMessageHook.ownerAllowed = { true }
        try {
            val data = ok(handler(lp(watch("A"))).handle(command(CommandCatalog.APPMESSAGE_SUBSCRIBE, null,
                "mode" to "replace", "subscriptions_json" to """[{"uuid":"$uuid","watch":"gone","ownership":"tasker"},{"uuid":"$uuid","watch":"A","ownership":"tasker"}]"""), "plugin"))
            assertEquals("[\"gone\"]", data["deferred_watches"])
            assertTrue(AutomationAppMessageHook.hasAuthorizedOwnership("address-A", uuid.toString()))
            assertFalse(AutomationAppMessageHook.hasAuthorizedOwnership("gone", uuid.toString()))
        } finally { AutomationAppMessageHook.clear(); AutomationAppMessageHook.ownerAllowed = null }
    }

    @Test fun subscriptionReplacementRemovesOldOwnershipAndPreservesOtherOwners() = runTest {
        AutomationAppMessageHook.clear()
        AutomationAppMessageHook.ownerAllowed = { true }
        try {
            val first=uuid.toString(); val second="00000000-0000-0000-0000-000000000002"
            AutomationAppMessageHook.subscribe("other",first,"address-A",exclusive=true)
            AutomationAppMessageHook.subscribe("plugin",first,"address-B",exclusive=true)
            val handler=handler(lp(watch("A"),watch("B")))
            ok(handler.handle(command(CommandCatalog.APPMESSAGE_SUBSCRIBE,null,"mode" to "replace",
                "subscriptions_json" to """[{"uuid":"$second","watch":"B","ownership":"observe"}]"""),"plugin"))
            assertTrue(AutomationAppMessageHook.hasAuthorizedOwnership("address-A",first))
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address-B",first))
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address-B",second))
            assertFalse(AutomationAppMessageHook.hasAuthorizedOwnership("address-B",second))
            failure(handler.handle(command(CommandCatalog.APPMESSAGE_SUBSCRIBE,null,"mode" to "replace",
                "subscriptions_json" to """[{"uuid":"bad"}]"""),"plugin"))
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address-B",second))
            ok(handler.handle(command(CommandCatalog.APPMESSAGE_SUBSCRIBE,null,"mode" to "replace","subscriptions_json" to "[]"),"plugin"))
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address-B",second))
            assertTrue(AutomationAppMessageHook.hasAuthorizedOwnership("address-A",first))
        } finally {AutomationAppMessageHook.clear();AutomationAppMessageHook.ownerAllowed=null}
    }

}
