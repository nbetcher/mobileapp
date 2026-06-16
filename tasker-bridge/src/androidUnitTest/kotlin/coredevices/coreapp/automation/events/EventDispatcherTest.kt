package coredevices.coreapp.automation.events

import kotlin.test.Test
import kotlin.test.assertEquals

class EventDispatcherTest {

    @Test
    fun seqIsMonotonic_andSinceReturnsTail() {
        val d = EventDispatcher(bootId = "boot")
        repeat(5) { d.emit("connectivity", "watch.connected") }
        assertEquals(5L, d.latestSeq)
        assertEquals(listOf(3L, 4L, 5L), d.since(2).map { it.seq })
        assertEquals(emptyList(), d.since(5).map { it.seq })
    }

    @Test
    fun ringBufferCapsAndDropsOldest() {
        val d = EventDispatcher(bootId = "boot")
        repeat(EventDispatcher.RING + 10) { d.emit("c", "t") }
        val all = d.since(0)
        assertEquals(EventDispatcher.RING, all.size)
        // 60 emitted, ring holds 50 → oldest kept is seq 11
        assertEquals(11L, all.first().seq)
        assertEquals(60L, all.last().seq)
    }
}
