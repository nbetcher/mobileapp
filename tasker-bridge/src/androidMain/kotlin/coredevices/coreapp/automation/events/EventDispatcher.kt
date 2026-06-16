package coredevices.coreapp.automation.events

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Clock

/**
 * Sole sequence authority + fan-out source for automation events (HLDD-001 §4).
 *
 * [bootId] is unique per bridge process; [seq] is monotonic within a boot, so (bootId, seq)
 * uniquely identifies an event for client-side dedupe. A [RING]-deep buffer backs
 * [since] for missed-event recovery (HLDD-002 §3 getEventsSince).
 */
class EventDispatcher(
    val bootId: String,
) {
    private val logger = Logger.withTag("AutomationBridge")
    private val lock = Any()
    private var seq: Long = 0
    private val ring = ArrayDeque<EventEnvelope>(RING)

    @Volatile
    var latestSeq: Long = 0
        private set

    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = OVERFLOW)
    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()

    fun emit(
        category: String,
        type: String,
        watch: WatchRef? = null,
        data: Map<String, String> = emptyMap(),
    ) {
        val envelope: EventEnvelope
        synchronized(lock) {
            envelope = EventEnvelope(
                bootId = bootId,
                seq = ++seq,
                ts = Clock.System.now().toEpochMilliseconds(),
                category = category,
                type = type,
                watch = watch,
                data = data,
            )
            latestSeq = envelope.seq
            ring.addLast(envelope)
            while (ring.size > RING) ring.removeFirst()
        }
        _events.tryEmit(envelope)
        logger.d { "event #${envelope.seq} ${envelope.category}/${envelope.type} watch=${watch?.serial ?: watch?.address}" }
    }

    /** Events still buffered with seq > [fromSeq], oldest first. */
    fun since(fromSeq: Long): List<EventEnvelope> = synchronized(lock) { ring.filter { it.seq > fromSeq } }

    companion object {
        /** Ring-buffer depth — last N events for missed-event recovery (HLDD-001 §12). */
        const val RING: Int = 50
        const val OVERFLOW: Int = 64
    }
}
