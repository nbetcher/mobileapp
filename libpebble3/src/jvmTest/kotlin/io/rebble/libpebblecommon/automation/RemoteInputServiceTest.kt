package io.rebble.libpebblecommon.automation

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.RemoteInputAck
import io.rebble.libpebblecommon.packets.RemoteInputMessage
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalUnsignedTypes::class)
class RemoteInputServiceTest {
    private class Protocol : PebbleProtocolHandler {
        override val inboundMessages = MutableSharedFlow<PebblePacket>()
        override val rawInboundMessages: Flow<ByteArray> = emptyFlow()
        val sent = mutableListOf<PebblePacket>()
        var reply: ((RemoteInputMessage) -> RemoteInputAck?)? = null
        override suspend fun send(message: PebblePacket, priority: PacketPriority) {
            sent += message
            reply?.invoke(message as RemoteInputMessage)?.let { inboundMessages.emit(it) }
        }
        override suspend fun send(message: ByteArray, priority: PacketPriority) {}
    }

    private fun version(major: Int, minor: Int, patch: Int, recovery: Boolean = false) = FirmwareVersion(
        "v$major.$minor.$patch", Instant.DISTANT_PAST, major, minor, patch, null, "", recovery, false, false,
    )

    private fun ack(command: UByte, status: Int) = PebblePacket.deserialize(
        ubyteArrayOf(0x00u, 0x02u, 0xf0u, 0x0du, command, status.toUByte()),
    ) as RemoteInputAck

    private fun service(protocol: Protocol, fw: FirmwareVersion? = version(4, 38, 2)) =
        RemoteInputService(protocol).apply { fw?.let(::init) }

    @Test fun messagesSerializeWithBigEndianFields() {
        assertContentEquals(
            ubyteArrayOf(0x00u, 0x07u, 0xf0u, 0x0du, 0x00u, 0x02u, 0x03u, 0x03u, 0xe8u, 0x00u, 0x64u),
            RemoteInputMessage.Button(2u, 3u, 1000u, 100u).serialize(),
        )
        assertContentEquals(
            ubyteArrayOf(0x00u, 0x04u, 0xf0u, 0x0du, 0x01u, 0x02u, 0x00u, 0x96u),
            RemoteInputMessage.Swipe(2u, 150u).serialize(),
        )
    }

    @Test fun ackStatusMapsToResults() = runTest {
        val protocol = Protocol()
        val service = service(protocol)
        for ((status, expected) in listOf(0 to RemoteInputResult.Ok, 1 to RemoteInputResult.Busy, 2 to RemoteInputResult.Invalid, 9 to RemoteInputResult.Invalid)) {
            protocol.reply = { ack(it.command.get(), status) }
            assertEquals(expected, service.pressButton(RemoteInputButton.Select, 1, 50, 100))
        }
        protocol.reply = { ack(it.command.get(), 0) }
        assertEquals(RemoteInputResult.Ok, service.swipe(RemoteInputSwipeDirection.Left, 150))
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
    }

    @Test fun ackForAnotherCommandIsIgnoredUntilTimeout() = runTest {
        val protocol = Protocol().apply { reply = { ack(RemoteInputMessage.COMMAND_SWIPE, 0) } }
        val start = currentTime
        assertEquals(RemoteInputResult.Timeout, service(protocol).pressButton(RemoteInputButton.Back, 1, 50, 100))
        assertEquals(5_000L, currentTime - start)
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
    }

    @Test fun requestsAreSerializedSoAcksStayUnambiguous() = runTest {
        val protocol = Protocol()
        val service = service(protocol)
        val first = async { service.pressButton(RemoteInputButton.Up, 1, 50, 100) }
        val second = async { service.pressButton(RemoteInputButton.Down, 1, 50, 100) }
        runCurrent()
        assertEquals(1, protocol.sent.size)
        protocol.inboundMessages.emit(ack(RemoteInputMessage.COMMAND_BUTTON, 0)); runCurrent()
        assertEquals(RemoteInputResult.Ok, first.await())
        assertEquals(2, protocol.sent.size)
        protocol.inboundMessages.emit(ack(RemoteInputMessage.COMMAND_BUTTON, 1))
        assertEquals(RemoteInputResult.Busy, second.await())
    }

    @Test fun olderRecoveryOrUnknownFirmwareIsUnsupportedWithoutSending() = runTest {
        for (fw in listOf(null, version(4, 34, 9), version(4, 4, 3), version(4, 38, 0, recovery = true))) {
            val protocol = Protocol()
            assertEquals(RemoteInputResult.Unsupported, service(protocol, fw).pressButton(RemoteInputButton.Up, 1, 50, 100))
            assertTrue(protocol.sent.isEmpty())
        }
        assertTrue(RemoteInputService.supports(version(4, 35, 0)))
        assertTrue(RemoteInputService.supports(version(5, 0, 0)))
    }

    @Test fun outOfRangeArgumentsAreInvalidWithoutSending() = runTest {
        val protocol = Protocol()
        val service = service(protocol)
        assertEquals(RemoteInputResult.Invalid, service.pressButton(RemoteInputButton.Up, 0, 50, 100))
        assertEquals(RemoteInputResult.Invalid, service.pressButton(RemoteInputButton.Up, 256, 50, 100))
        assertEquals(RemoteInputResult.Invalid, service.pressButton(RemoteInputButton.Up, 1, 70_000, 100))
        assertEquals(RemoteInputResult.Invalid, service.swipe(RemoteInputSwipeDirection.Up, 301))
        assertTrue(protocol.sent.isEmpty())
    }
}
