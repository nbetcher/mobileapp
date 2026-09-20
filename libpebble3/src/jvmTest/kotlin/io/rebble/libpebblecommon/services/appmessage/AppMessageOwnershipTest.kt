package io.rebble.libpebblecommon.services.appmessage

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.packets.AppMessageTuple
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppMessageOwnershipTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private class Protocol : PebbleProtocolHandler {
        override val inboundMessages = MutableSharedFlow<PebblePacket>()
        override val rawInboundMessages: Flow<ByteArray> = emptyFlow()
        val sent = mutableListOf<PebblePacket>()
        var immediateAck = false
        override suspend fun send(message: PebblePacket, priority: PacketPriority) {
            sent += message
            if (immediateAck && message is AppMessage.AppMessagePush) {
                inboundMessages.emit(AppMessage.AppMessageACK(message.transactionId.get()))
            }
        }
        override suspend fun send(message: ByteArray, priority: PacketPriority) {}
    }
    private suspend fun reset() {
        AutomationAppMessageHook.clear()
        AutomationAppMessageHook.ownerAllowed = null
        AutomationAppMessageHook.onReceived = null
    }

    @Test fun explicitOwnershipAcksAcceptedAndNacksRejectedWithoutFeedingCompanion() = runTest {
        reset()
        try {
            val p = Protocol()
            val service = AppMessageService(p, ConnectionCoroutineScope(backgroundScope.coroutineContext), "address".asPebbleBleIdentifier())
            service.setAutomationEligibility(uuid, true)
            service.init()
            val companionMessages = mutableListOf<AppMessageData>()
            backgroundScope.launch { service.inboundAppMessages(uuid).collect { companionMessages += it } }
            AutomationAppMessageHook.subscribe("owner", uuid.toString(), "address", exclusive = true)
            AutomationAppMessageHook.ownerAllowed = { true }
            var accept = true
            AutomationAppMessageHook.onReceived = { address, app, transaction, _ ->
                assertEquals("address", address); assertEquals(uuid.toString(), app)
                assertTrue(transaction == 3 || transaction == 4)
                accept
            }
            runCurrent()
            p.inboundMessages.emit(AppMessage.AppMessagePush(3u, uuid, emptyList()))
            runCurrent()
            assertTrue(p.sent.single() is AppMessage.AppMessageACK)
            assertEquals(3u.toUByte(), (p.sent.single() as AppMessage).transactionId.get())
            accept = false
            p.inboundMessages.emit(AppMessage.AppMessagePush(4u, uuid, emptyList()))
            runCurrent()
            assertTrue(p.sent.last() is AppMessage.AppMessageNACK)
            assertEquals(4u.toUByte(), (p.sent.last() as AppMessage).transactionId.get())
            assertTrue(companionMessages.isEmpty())
        } finally { reset() }
    }

    @Test fun declaredOrUnknownCompanionKeepsExclusiveAckOwnership() = runTest {
        reset()
        try {
            val p = Protocol()
            val service = AppMessageService(p, ConnectionCoroutineScope(backgroundScope.coroutineContext), "address".asPebbleBleIdentifier())
            service.init()
            val messages = mutableListOf<AppMessageData>()
            backgroundScope.launch { service.inboundAppMessages(uuid).collect { messages += it } }
            AutomationAppMessageHook.subscribe("owner", uuid.toString(), exclusive = true)
            AutomationAppMessageHook.ownerAllowed = { true }
            var observed = 0
            AutomationAppMessageHook.onReceived = { _, _, _, _ -> observed++; true }
            runCurrent()
            p.inboundMessages.emit(AppMessage.AppMessagePush(5u, uuid, emptyList()))
            runCurrent()
            service.setAutomationEligibility(uuid, false)
            p.inboundMessages.emit(AppMessage.AppMessagePush(6u, uuid, emptyList()))
            runCurrent()
            assertEquals(2, observed)
            assertEquals(2, messages.size)
            assertTrue(p.sent.isEmpty())
        } finally { reset() }
    }

    @Test fun revocationAtAdmissionNacksAndMissingAuthorityFailsClosed() = runTest {
        reset()
        try {
            val p = Protocol()
            val service = AppMessageService(p, ConnectionCoroutineScope(backgroundScope.coroutineContext), "address".asPebbleBleIdentifier())
            service.setAutomationEligibility(uuid, true)
            service.init()
            AutomationAppMessageHook.subscribe("owner", uuid.toString(), exclusive = true)
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address", uuid.toString()))
            var allowed = true
            AutomationAppMessageHook.ownerAllowed = { allowed }
            AutomationAppMessageHook.onReceived = { _, _, _, _ -> allowed = false; true }
            runCurrent()
            p.inboundMessages.emit(AppMessage.AppMessagePush(7u, uuid, emptyList()))
            runCurrent()
            assertTrue(p.sent.single() is AppMessage.AppMessageNACK)
            assertEquals(7u.toUByte(), (p.sent.single() as AppMessage).transactionId.get())
            allowed = true
            AutomationAppMessageHook.clearOwner("owner")
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("address", uuid.toString()))
        } finally { reset() }
    }

    @Test fun passiveLegacyObservationNeverTakesAckOwnershipAndCanDowngradeClaim() = runTest {
        reset()
        try {
            val p = Protocol()
            val service = AppMessageService(p, ConnectionCoroutineScope(backgroundScope.coroutineContext), "address".asPebbleBleIdentifier())
            service.setAutomationEligibility(uuid, true)
            service.init()
            val messages = mutableListOf<AppMessageData>()
            backgroundScope.launch { service.inboundAppMessages(uuid).collect { messages += it } }
            AutomationAppMessageHook.ownerAllowed = { true }
            AutomationAppMessageHook.subscribe("owner", uuid.toString())
            AutomationAppMessageHook.onReceived = { _, _, _, _ -> true }
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("address", uuid.toString()))
            assertFalse(AutomationAppMessageHook.hasAuthorizedOwnership("address", uuid.toString()))
            runCurrent()
            p.inboundMessages.emit(AppMessage.AppMessagePush(8u, uuid, emptyList()))
            runCurrent()
            assertEquals(1, messages.size)
            assertTrue(p.sent.isEmpty())
            AutomationAppMessageHook.subscribe("owner", uuid.toString(), exclusive = true)
            assertTrue(AutomationAppMessageHook.hasAuthorizedOwnership("address", uuid.toString()))
            AutomationAppMessageHook.subscribe("owner", uuid.toString(), exclusive = false)
            assertFalse(AutomationAppMessageHook.hasAuthorizedOwnership("address", uuid.toString()))
            p.inboundMessages.emit(AppMessage.AppMessagePush(9u, uuid, emptyList()))
            runCurrent()
            assertEquals(2, messages.size)
            assertTrue(p.sent.isEmpty())
        } finally { reset() }
    }

    @Test fun subscriptionsAreIsolatedByWatchAndOwner() = runTest {
        reset()
        try {
            AutomationAppMessageHook.ownerAllowed = { it == "allowed" }
            AutomationAppMessageHook.subscribe("denied", uuid.toString())
            AutomationAppMessageHook.subscribe("allowed", uuid.toString(), "one")
            assertTrue(AutomationAppMessageHook.hasAuthorizedSubscription("one", uuid.toString()))
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("two", uuid.toString()))
            AutomationAppMessageHook.unsubscribe("allowed", uuid.toString(), "one")
            assertFalse(AutomationAppMessageHook.hasAuthorizedSubscription("one", uuid.toString()))
        } finally { reset() }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test fun outgoingPacketPreservesTypedDictionaryValues() = runTest {
        val p = Protocol().apply { immediateAck = true }
        val service = AppMessageService(p, ConnectionCoroutineScope(backgroundScope.coroutineContext), "address".asPebbleBleIdentifier())
        service.sendAppMessage(AppMessageData(9u, uuid,
            mapOf(0 to "123", 1 to "", 2 to UInt.MAX_VALUE, 3 to byteArrayOf(0, -1), 4 to -123)))
        val packet = p.sent.single() as AppMessage.AppMessagePush
        val unsignedTuple = packet.dictionary.list.single { it.key.get() == 2u }
        assertEquals(AppMessageTuple.Type.UInt.value, unsignedTuple.type.get())
        assertEquals(4u.toUShort(), unsignedTuple.dataLength.get())
        val signedTuple = packet.dictionary.list.single { it.key.get() == 4u }
        assertEquals(AppMessageTuple.Type.Int.value, signedTuple.type.get())
        assertEquals(4u.toUShort(), signedTuple.dataLength.get())
        val values = packet.dictionary.list.associate { it.key.get() to it.getTypedData() }
        assertEquals("123", values[0u])
        assertEquals("", values[1u])
        assertEquals(UInt.MAX_VALUE.toULong(), values[2u])
        assertContentEquals(ubyteArrayOf(0u, 255u), values[3u] as UByteArray)
        assertEquals(-123L, values[4u])
    }

    @Test fun immediateAckIsObservedAndCallerCancellationRemovesWaiter() = runTest {
        val p = Protocol()
        val service = AppMessageService(p, ConnectionCoroutineScope(backgroundScope.coroutineContext), "address".asPebbleBleIdentifier())
        p.immediateAck = true
        assertTrue(service.sendAppMessage(AppMessageData(1u, uuid, emptyMap())) is AppMessageResult.ACK)
        p.immediateAck = false
        val call = launch { service.sendAppMessage(AppMessageData(2u, uuid, emptyMap())) }
        runCurrent()
        assertEquals(1, p.inboundMessages.subscriptionCount.value)
        call.cancelAndJoin()
        assertEquals(0, p.inboundMessages.subscriptionCount.value)
    }
}
