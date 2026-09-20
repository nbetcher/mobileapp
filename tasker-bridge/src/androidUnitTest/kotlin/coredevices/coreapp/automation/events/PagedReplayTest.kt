package coredevices.coreapp.automation.events

import android.os.Parcel
import coredevices.coreapp.automation.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PagedReplayTest {
    private fun journal(): EventDispatcher = EventDispatcher("boot").also { dispatcher ->
        repeat(512) { assertTrue(dispatcher.emit("apps", "appmsg.received", data = mapOf("dict_json" to "x".repeat(4096)))) }
    }
    @Test fun pagesFitParcelAndCoverAllSequencesExactlyOnce() {
        val dispatcher = journal()
        val sequences = mutableListOf<Long>()
        var cursor = 0L
        var pages = 0
        do {
            val batch = dispatcher.snapshot(cursor).copy(subscriptionToken = "token")
            val parcel = Parcel.obtain()
            try {
                parcel.writeString(BridgeJson.json.encodeToString(batch))
                assertTrue(parcel.dataSize() < 200 * 1024)
            } finally { parcel.recycle() }
            assertFalse(batch.historyLost)
            assertTrue(batch.cursor!! > cursor)
            sequences += batch.events.map { it.seq }
            cursor = batch.cursor!!
            pages++
            assertTrue(pages < 100)
        } while (batch.more)
        assertTrue(pages > 1)
        assertEquals((1L..512L).toList(), sequences)
    }
    @Test fun listenerDrainsAllPagesWithoutAnotherEmission() {
        val dispatcher = journal()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = ListenerHub(dispatcher).also { it.start(scope) }
        val done = CountDownLatch(1)
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        try {
            hub.register("token", object : IBridgeEventListener.Stub() {
                override fun onEvents(json: String) {
                    val batch = BridgeJson.json.decodeFromString<EventBatch>(json)
                    seen += batch.events.map { it.seq }
                    if (!batch.more && batch.cursor == 512L) done.countDown()
                }
                override fun onBridgeGoodbye(reason: String) {}
            }, 0)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals((1L..512L).toList(), seen.toList())
        } finally { hub.unregister("token"); scope.cancel() }
    }
    @Test fun eventAtExactLimitRemainsReplayable() {
        val dispatcher = EventDispatcher("boot")
        assertTrue(dispatcher.emit("apps", "appmsg.received", data = mapOf("payload" to "")))
        val sample = dispatcher.snapshot(0).events.single()
        val overhead = BridgeJson.json.encodeToString(sample).length
        assertTrue(dispatcher.emit("apps", "appmsg.received", data = mapOf(
            "payload" to "x".repeat(EventDispatcher.MAX_EVENT_CHARS - overhead))))
        val batch = dispatcher.snapshot(0)
        assertEquals(listOf(1L, 2L), batch.events.map { it.seq })
        assertEquals(EventDispatcher.MAX_EVENT_CHARS, BridgeJson.json.encodeToString(batch.events.last()).length)
        assertFalse(batch.historyLost)
    }
    @Test fun filtersAdvanceCursorAndOversizedEventIsNotAccepted() {
        val dispatcher = journal()
        val hidden = dispatcher.snapshot(0, permitted = { false })
        assertEquals(512L, hidden.cursor); assertFalse(hidden.more); assertTrue(hidden.events.isEmpty())
        val proof = dispatcher.snapshot(Long.MAX_VALUE)
        assertTrue(proof.events.isEmpty()); assertEquals(512L, proof.cursor)
        assertFalse(dispatcher.emit("apps", "appmsg.received", data = mapOf("payload" to "x".repeat(50_000))))
        assertEquals(512L, dispatcher.latestSeq)
    }
}
