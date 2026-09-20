package coredevices.coreapp.automation.events

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import coredevices.coreapp.automation.EventBatch
import coredevices.coreapp.automation.BridgeJson
import kotlinx.serialization.encodeToString
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
    private var discardedThrough: Long = 0
    private val ring = ArrayDeque<EventEnvelope>(RING)

    @Volatile
    var latestSeq: Long = 0
        private set

    /**
     * Optional consent gate (PLAN §5.4). When set and it returns false for an event's category, the
     * event is dropped entirely — no sequence number, no ring-buffer entry — so a disabled category
     * never reaches any client (even via getEventsSince recovery). Set once by the bridge at startup.
     */
    @Volatile
    var categoryGate: ((String) -> Boolean)? = null

    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = OVERFLOW, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    @Volatile var deliveryFilter: ((EventEnvelope) -> EventEnvelope?)? = null

    fun emit(
        category: String,
        type: String,
        watch: WatchRef? = null,
        data: Map<String, String> = emptyMap(),
    ): Boolean {
        val envelope: EventEnvelope
        synchronized(lock) {
            if (categoryGate?.invoke(category) == false) return false
            envelope = EventEnvelope(
                bootId = bootId,
                seq = seq + 1,
                ts = Clock.System.now().toEpochMilliseconds(),
                category = category,
                type = type,
                watch = watch,
                data = data,
            )
            // AIDL strings are UTF-16 in a Parcel. Reject a single oversized payload before
            // accepting/ACKing it; pagination cannot make one such event safe.
            if (BridgeJson.json.encodeToString(envelope).length > MAX_EVENT_CHARS) {
                logger.w { "Rejected oversized automation event: $type" }
                return false
            }
            seq = envelope.seq
            latestSeq = envelope.seq
            ring.addLast(envelope)
            while (ring.size > RING) discardedThrough = ring.removeFirst().seq
            _events.tryEmit(envelope)
            _changes.value += 1
        }
        logger.d { "event #${envelope.seq} ${envelope.category}/${envelope.type} watch=${watch?.serial ?: watch?.address}" }
        return true
    }

    /** Events still buffered with seq > [fromSeq], oldest first. */
    fun since(fromSeq: Long): List<EventEnvelope> = snapshot(fromSeq).events

    fun snapshot(fromSeq: Long, transform: (EventEnvelope) -> EventEnvelope? = { it }, permitted: (EventEnvelope) -> Boolean = { true }): EventBatch = synchronized(lock) {
        val page = ArrayList<EventEnvelope>()
        var chars = 1024 // envelope, cursor and registration token headroom
        var cursor = fromSeq.coerceAtMost(latestSeq)
        var more = false
        var lost = fromSeq >= 0 && fromSeq < discardedThrough
        for (original in ring) {
            if (original.seq <= fromSeq) continue
            val visible = if (categoryGate?.invoke(original.category) != false && permitted(original)) {
                val filtered = deliveryFilter?.invoke(original) ?: if (deliveryFilter == null) original else null
                filtered?.let(transform)
            } else null
            if (visible != null) {
                val eventChars = BridgeJson.json.encodeToString(visible).length
                if (eventChars > MAX_EVENT_CHARS) { lost = true; cursor = original.seq; continue }
                val size = eventChars + 1 // Array delimiter belongs to the batch, not the event.
                if (chars + size > MAX_BATCH_CHARS) { more = true; break }
                chars += size
                page += visible
            }
            cursor = original.seq // filtered events also advance the scanned cursor
        }
        EventBatch(bootId = bootId, events = page, more = more,
            cursor = if (more) cursor else latestSeq, historyLost = lost)
    }

    fun reconcilePolicy() = synchronized(lock) {
        val retained = ring.filter { categoryGate?.invoke(it.category) != false }
            .mapNotNull { event -> deliveryFilter?.invoke(event) ?: if (deliveryFilter == null) event else null }
        ring.clear()
        ring.addAll(retained)
        _changes.value += 1
    }

    companion object {
        /** Ring-buffer depth — last N events for missed-event recovery (HLDD-001 §12). */
        const val RING: Int = 512
        const val OVERFLOW: Int = 64
        const val MAX_EVENT_CHARS: Int = 40 * 1024
        const val MAX_BATCH_CHARS: Int = 96 * 1024
    }
}
