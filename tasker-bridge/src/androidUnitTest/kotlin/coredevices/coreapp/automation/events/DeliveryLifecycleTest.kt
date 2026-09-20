package coredevices.coreapp.automation.events

import coredevices.coreapp.automation.BridgeJson
import coredevices.coreapp.automation.EventBatch
import coredevices.coreapp.automation.IBridgeEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeliveryLifecycleTest {
    private class Listener(val receive: (EventBatch) -> Unit, val goodbye: () -> Unit = {}) : IBridgeEventListener.Stub() {
        override fun onEvents(json: String) { receive(BridgeJson.json.decodeFromString<EventBatch>(json)) }
        override fun onBridgeGoodbye(reason: String) { goodbye() }
    }
    @Test fun concurrentEmissionsPreserveJournalOrder() {
        val dispatcher = EventDispatcher("boot")
        val pool = Executors.newFixedThreadPool(8)
        try {
            (1..200).map { pool.submit { dispatcher.emit("connectivity", "watch.connected") } }
                .forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals((1L..200L).toList(), dispatcher.since(0).map { it.seq })
            assertEquals(200L, dispatcher.snapshot(0).cursor)
        } finally { pool.shutdownNow() }
    }
    @Test fun overflowIsExplicitButFilteredSequenceHolesAreNotLoss() {
        val dispatcher = EventDispatcher("boot")
        dispatcher.emit("health", "health.updated")
        dispatcher.emit("connectivity", "watch.connected")
        val restricted = dispatcher.snapshot(0) { it.category == "connectivity" }
        assertEquals(listOf(2L), restricted.events.map { it.seq })
        assertEquals(2L, restricted.cursor)
        assertFalse(restricted.historyLost)
        repeat(EventDispatcher.RING + 2) { dispatcher.emit("connectivity", "watch.connected") }
        assertTrue(dispatcher.snapshot(0).historyLost)
        assertEquals(EventDispatcher.RING, dispatcher.snapshot(0).events.size)
    }
    @Test fun privacyTighteningPermanentlyRemovesRetainedPayload() {
        val dispatcher = EventDispatcher("boot")
        dispatcher.emit("notifications", "notif.sent", data = mapOf("text" to "private", "pkg" to "source"))
        dispatcher.deliveryFilter = { it.copy(data = it.data - "text") }
        dispatcher.reconcilePolicy()
        dispatcher.deliveryFilter = null
        assertFalse(dispatcher.since(0).single().data.containsKey("text"))
        dispatcher.categoryGate = { false }
        dispatcher.reconcilePolicy()
        dispatcher.categoryGate = null
        assertTrue(dispatcher.since(0).isEmpty())
        assertFalse(dispatcher.snapshot(0).historyLost)
    }
    @Test fun metadataCannotBypassAnEventCategoryGrant() {
        val ref = WatchRef("serial", "Watch", battery = 80, currentAppUuid = "private-app", devEnabled = true, fwStatus = "in_progress")
        val event = EventEnvelope(bootId = "boot", seq = 1, ts = 0, category = "connectivity", type = "watch.connected", watch = ref)
        val filtered = EventAccessPolicy.event(event, setOf("connectivity"))!!
        assertEquals(80, filtered.watch!!.battery)
        assertNull(filtered.watch.currentAppUuid)
        assertNull(filtered.watch.devEnabled)
        assertNull(filtered.watch.fwStatus)
        assertNull(EventAccessPolicy.event(event, setOf("health")))
    }
    @Test fun replayCompletesBeforeLiveTransitionForTheSameListener() {
        val dispatcher = EventDispatcher("boot")
        dispatcher.emit("connectivity", "watch.disconnected")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = ListenerHub(dispatcher)
        hub.start(scope)
        try {
            hub.register("token", Listener({ batch ->
                if (batch.events.any { it.seq == 1L }) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                seen.addAll(batch.events.map { it.seq })
                if (2L in seen) complete.countDown()
            }), 0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            dispatcher.emit("connectivity", "watch.connected")
            release.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), seen.toList())
        } finally { release.countDown(); hub.invalidateAll(); scope.cancel() }
    }
    @Test fun slowClientDoesNotBlockOtherClientsAndRevocationStopsDelivery() {
        val dispatcher = EventDispatcher("boot")
        dispatcher.emit("connectivity", "watch.connected")
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fast = CountDownLatch(1)
        val goodbye = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = ListenerHub(dispatcher)
        hub.start(scope)
        try {
            hub.register("slow", Listener({ blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }), 0)
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            hub.register("fast", Listener({ fast.countDown() }, { goodbye.countDown() }), 0, authorized = { allowed.get() })
            assertTrue(fast.await(5, TimeUnit.SECONDS))
            allowed.set(false)
            hub.revalidate()
            assertFalse(hub.registered("fast"))
            assertTrue(goodbye.await(5, TimeUnit.SECONDS))
        } finally { release.countDown(); hub.invalidateAll(); scope.cancel() }
    }
    @Test fun hundredEventsWhileCallbackIsPausedAreRecoveredFromJournal() {
        val dispatcher = EventDispatcher("boot")
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = ListenerHub(dispatcher)
        hub.start(scope)
        try {
            hub.register("token", Listener({ batch ->
                if (first.getAndSet(false)) { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                seen.addAll(batch.events.map { it.seq })
                assertFalse(batch.historyLost)
                if (batch.cursor == 100L) complete.countDown()
            }), 0)
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            repeat(100) { dispatcher.emit("connectivity", "watch.battery") }
            release.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals((1L..100L).toList(), seen.toList())
        } finally { release.countDown(); hub.invalidateAll(); scope.cancel() }
    }
}
