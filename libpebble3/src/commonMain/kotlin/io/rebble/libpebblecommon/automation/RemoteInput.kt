package io.rebble.libpebblecommon.automation

import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.RemoteInputAck
import io.rebble.libpebblecommon.packets.RemoteInputMessage
import io.rebble.libpebblecommon.services.FirmwareVersion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

enum class RemoteInputButton(val id: UByte) { Back(0u), Up(1u), Select(2u), Down(3u) }

enum class RemoteInputSwipeDirection(val id: UByte) { Up(0u), Down(1u), Left(2u), Right(3u) }

enum class RemoteInputResult {
    Ok,
    /** Another injected sequence is still running on the watch. */
    Busy,
    /** Rejected by the watch or out of range here (e.g. a swipe on a watch without touch). */
    Invalid,
    /** The running firmware has no remote input endpoint. */
    Unsupported,
    /** No acknowledgement; whether the input ran is unknown. */
    Timeout,
}

/** Injected button presses and swipes. The watch acknowledges when it admits a sequence, not when it ends. */
interface RemoteInput {
    suspend fun pressButton(button: RemoteInputButton, presses: Int, holdMs: Int, gapMs: Int): RemoteInputResult
    suspend fun swipe(direction: RemoteInputSwipeDirection, durationMs: Int): RemoteInputResult

    object Unavailable : RemoteInput {
        override suspend fun pressButton(button: RemoteInputButton, presses: Int, holdMs: Int, gapMs: Int) = RemoteInputResult.Unsupported
        override suspend fun swipe(direction: RemoteInputSwipeDirection, durationMs: Int) = RemoteInputResult.Unsupported
    }
}

class RemoteInputService(private val protocolHandler: PebbleProtocolHandler) : RemoteInput {
    // Acks carry only the command byte, so one request at a time keeps them unambiguous.
    private val mutex = Mutex()
    @Volatile private var runningFwVersion: FirmwareVersion? = null

    fun init(runningFwVersion: FirmwareVersion) {
        this.runningFwVersion = runningFwVersion
    }

    override suspend fun pressButton(button: RemoteInputButton, presses: Int, holdMs: Int, gapMs: Int): RemoteInputResult {
        if (presses !in 1..255 || holdMs !in 0..0xFFFF || gapMs !in 0..0xFFFF) return RemoteInputResult.Invalid
        return send(RemoteInputMessage.Button(button.id, presses.toUByte(), holdMs.toUShort(), gapMs.toUShort()))
    }

    override suspend fun swipe(direction: RemoteInputSwipeDirection, durationMs: Int): RemoteInputResult {
        if (durationMs !in 1..MAX_SWIPE_MS) return RemoteInputResult.Invalid
        return send(RemoteInputMessage.Swipe(direction.id, durationMs.toUShort()))
    }

    private suspend fun send(message: RemoteInputMessage): RemoteInputResult {
        if (!supports(runningFwVersion)) return RemoteInputResult.Unsupported
        val command = message.command.get()
        return mutex.withLock {
            withTimeoutOrNull(ACK_TIMEOUT) {
                coroutineScope {
                    val ack = async(start = CoroutineStart.UNDISPATCHED) {
                        protocolHandler.inboundMessages.first { it is RemoteInputAck && it.command.get() == command } as RemoteInputAck
                    }
                    protocolHandler.send(message)
                    when (ack.await().status.get().toInt()) {
                        0 -> RemoteInputResult.Ok
                        1 -> RemoteInputResult.Busy
                        else -> RemoteInputResult.Invalid
                    }
                }
            } ?: RemoteInputResult.Timeout
        }
    }

    companion object {
        /** PebbleOS SWIPE_MAX_DURATION_MS; a slower contact is read as a pan. */
        const val MAX_SWIPE_MS = 300
        private val ACK_TIMEOUT = 5.seconds

        /** Remote input first shipped in PebbleOS v4.35.0. */
        fun supports(version: FirmwareVersion?): Boolean =
            version != null && !version.isRecovery &&
                version.major * 1_000_000 + version.minor * 1_000 + version.patch >= 4_035_000
    }
}
