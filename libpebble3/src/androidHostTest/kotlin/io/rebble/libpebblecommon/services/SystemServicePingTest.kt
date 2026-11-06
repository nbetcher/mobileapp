package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.PingPong
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemServicePingTest {
    private class Protocol : PebbleProtocolHandler {
        override val inboundMessages = MutableSharedFlow<PebblePacket>()
        override val rawInboundMessages: Flow<ByteArray> = emptyFlow()
        var immediate = false
        var failSend = false
        val sent = mutableListOf<PebblePacket>()
        override suspend fun send(message: PebblePacket, priority: PacketPriority) {
            if (failSend) error("transport failed")
            sent += message
            if (immediate && message is PingPong.Ping) inboundMessages.emit(PingPong.Pong(message.cookie.get()))
        }
        override suspend fun send(message: ByteArray, priority: PacketPriority) {}
    }

    private fun TestScope.service(protocol: Protocol) = SystemService(
        protocol, ConnectionCoroutineScope(backgroundScope.coroutineContext), PhoneCapabilities(emptySet()), PlatformFlags(0u),
    )

    @Test fun immediateResponseIsObservedAndUnsubscribed() = runTest {
        val protocol = Protocol().apply { immediate = true }
        assertEquals(UInt.MAX_VALUE, service(protocol).sendPing(UInt.MAX_VALUE))
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
    }

    @Test fun concurrentCookiesCompleteIndependentlyInReverseOrder() = runTest {
        val protocol = Protocol(); val service = service(protocol)
        val first = async { service.sendPing(1u) }; val second = async { service.sendPing(2u) }
        runCurrent()
        assertEquals(2, protocol.inboundMessages.subscriptionCount.value)
        protocol.inboundMessages.emit(PingPong.Pong(2u)); runCurrent()
        assertTrue(second.isCompleted); assertFalse(first.isCompleted)
        protocol.inboundMessages.emit(PingPong.Pong(999u)); runCurrent()
        assertFalse(first.isCompleted)
        protocol.inboundMessages.emit(PingPong.Pong(1u))
        assertEquals(1u, first.await()); assertEquals(2u, second.await())
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
    }

    @Test fun timeoutBoundsMissingOrWrongResponseAndRemovesWaiter() = runTest {
        val protocol = Protocol(); val service = service(protocol)
        val start = currentTime
        val call = async { runCatching { service.sendPing(1u) } }
        runCurrent(); protocol.inboundMessages.emit(PingPong.Pong(2u))
        assertIs<TimeoutCancellationException>(call.await().exceptionOrNull())
        assertEquals(5000L, currentTime - start)
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
        protocol.immediate = true
        assertEquals(3u, service.sendPing(3u))
    }

    @Test fun cancellationAndTransportFailureCleanOnlyTheirOwnWaiter() = runTest {
        val protocol = Protocol(); val service = service(protocol)
        val cancelled = launch { service.sendPing(1u) }; val remaining = async { service.sendPing(2u) }
        runCurrent(); cancelled.cancelAndJoin()
        assertEquals(1, protocol.inboundMessages.subscriptionCount.value)
        protocol.inboundMessages.emit(PingPong.Pong(1u)); runCurrent()
        assertFalse(remaining.isCompleted)
        protocol.inboundMessages.emit(PingPong.Pong(2u)); assertEquals(2u, remaining.await())
        protocol.failSend = true
        assertFailsWith<IllegalStateException> { service.sendPing(3u) }
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
    }
}
