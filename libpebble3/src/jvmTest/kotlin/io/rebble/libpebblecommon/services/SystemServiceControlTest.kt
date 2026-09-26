package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.ResetMessage
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalUnsignedTypes::class)
class SystemServiceControlTest {
    private class Protocol : PebbleProtocolHandler {
        override val inboundMessages = MutableSharedFlow<PebblePacket>()
        override val rawInboundMessages: Flow<ByteArray> = emptyFlow()
        val sent = mutableListOf<PebblePacket>()
        override suspend fun send(message: PebblePacket, priority: PacketPriority) { sent += message }
        override suspend fun send(message: ByteArray, priority: PacketPriority) {}
    }

    private fun TestScope.service(protocol: Protocol) = SystemService(
        protocol, ConnectionCoroutineScope(backgroundScope.coroutineContext), PhoneCapabilities(emptySet()), PlatformFlags(0u),
    )

    @Test fun resetSendsThePlainRebootCommand() = runTest {
        val protocol = Protocol()
        service(protocol).reset()
        runCurrent()
        val packet = assertIs<ResetMessage.Reset>(protocol.sent.single())
        assertEquals(0x00u.toUByte(), packet.command.get())
    }
}
