package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.automation.TimeSyncResult
import io.rebble.libpebblecommon.packets.ResetMessage
import io.rebble.libpebblecommon.packets.TimeMessage
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Clock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalUnsignedTypes::class)
class SystemServiceControlTest {
    private class Protocol : PebbleProtocolHandler {
        override val inboundMessages = MutableSharedFlow<PebblePacket>()
        override val rawInboundMessages: Flow<ByteArray> = emptyFlow()
        val sent = mutableListOf<PebblePacket>()
        var watchClockOffset: Long? = 0
        override suspend fun send(message: PebblePacket, priority: PacketPriority) {
            sent += message
            val offset = watchClockOffset
            if (message is TimeMessage.GetTimeRequest && offset != null) {
                inboundMessages.emit(TimeMessage.GetTimeResponse((Clock.System.now().epochSeconds + offset).toUInt()))
            }
        }
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

    @Test fun getTimeResponseDecodesBigEndianSeconds() {
        val packet = PebblePacket.deserialize(ubyteArrayOf(0x00u, 0x05u, 0x00u, 0x0bu, 0x01u, 0x6bu, 0x49u, 0xd2u, 0x00u))
        assertEquals(1_800_000_000u, assertIs<TimeMessage.GetTimeResponse>(packet).time.get())
    }

    @Test fun verifiedSyncSetsTimeThenClassifiesTheReadback() = runTest {
        val protocol = Protocol()
        val service = service(protocol)
        assertIs<TimeSyncResult.Success>(service.updateTimeVerified())
        assertIs<TimeMessage.SetUTC>(protocol.sent[0])
        assertIs<TimeMessage.GetTimeRequest>(protocol.sent[1])

        protocol.watchClockOffset = 120
        val mismatch = assertIs<TimeSyncResult.Mismatch>(service.updateTimeVerified())
        assertTrue(mismatch.skewSeconds in 118..122)

        protocol.watchClockOffset = null
        val start = currentTime
        assertEquals(TimeSyncResult.Timeout, service.updateTimeVerified())
        assertEquals(5_000L, currentTime - start)
        assertEquals(0, protocol.inboundMessages.subscriptionCount.value)
    }
}
