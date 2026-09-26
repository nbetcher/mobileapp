package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.util.Endian

/** PebbleOS remote input endpoint (`src/fw/kernel/remote_input.c`). Multi-byte fields are big-endian. */
sealed class RemoteInputMessage(command: UByte) : PebblePacket(ProtocolEndpoint.REMOTE_INPUT) {
    val command = SUByte(m, command)

    class Button(buttonId: UByte, presses: UByte, holdMs: UShort, gapMs: UShort) : RemoteInputMessage(COMMAND_BUTTON) {
        val buttonId = SUByte(m, buttonId)
        val presses = SUByte(m, presses)
        val holdMs = SUShort(m, holdMs, Endian.Big)
        val gapMs = SUShort(m, gapMs, Endian.Big)
    }

    class Swipe(direction: UByte, durationMs: UShort) : RemoteInputMessage(COMMAND_SWIPE) {
        val direction = SUByte(m, direction)
        val durationMs = SUShort(m, durationMs, Endian.Big)
    }

    companion object {
        const val COMMAND_BUTTON: UByte = 0x00u
        const val COMMAND_SWIPE: UByte = 0x01u
    }
}

/** The watch's answer to every [RemoteInputMessage]: the command it answers and a status (0 ok, 1 busy, 2 invalid). */
class RemoteInputAck : PebblePacket(ProtocolEndpoint.REMOTE_INPUT) {
    val command = SUByte(m)
    val status = SUByte(m)
}

fun remoteInputPacketsRegister() {
    PacketRegistry.register(ProtocolEndpoint.REMOTE_INPUT) { RemoteInputAck() }
}
