package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.events.EventAccessPolicy
import coredevices.coreapp.automation.events.EventDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomationJobsTest {
    @Test fun resultReachesOnlyTheOwnerAndSlotsAreSingleFlight() = runTest {
        val dispatcher = EventDispatcher("boot")
        val jobs = AutomationJobs(backgroundScope, dispatcher)
        val gate = CompletableDeferred<Unit>()
        val id = assertNotNull(jobs.start("watch.screenshot", "A", "pkg", null, 60_000) { gate.await(); mapOf("uri" to "content://x") })
        assertNull(jobs.start("watch.screenshot", "A", "pkg", null, 60_000) { emptyMap() })
        assertNotNull(jobs.start("watch.gatherLogs", "A", "pkg", null, 60_000) { emptyMap() })
        gate.complete(Unit)
        runCurrent()

        val event = dispatcher.snapshot(0).events.single { it.data["job_id"] == id }
        assertEquals("job.done", event.type)
        assertEquals("ok", event.data["status"])
        assertEquals("content://x", event.data["uri"])
        assertEquals("watch.screenshot", event.data["command"])
        assertNotNull(EventAccessPolicy.event(event, setOf("system"), "pkg"))
        assertNull(EventAccessPolicy.event(event, setOf("system"), "other"))
        assertNull(EventAccessPolicy.event(event, setOf("system")))
        assertNull(EventAccessPolicy.event(event, setOf("apps"), "pkg"))

        assertNotNull(jobs.start("watch.screenshot", "A", "pkg", null, 60_000) { emptyMap() })
    }

    @Test fun failuresAndTimeoutsReportFailedAndFreeTheSlot() = runTest {
        val dispatcher = EventDispatcher("boot")
        val jobs = AutomationJobs(backgroundScope, dispatcher)
        val failed = jobs.start("watch.gatherLogs", "A", "pkg", null, 60_000) { error("no logs") }
        runCurrent()
        val slow = jobs.start("watch.gatherLogs", "A", "pkg", null, 1_000) { delay(5_000); emptyMap() }
        advanceTimeBy(1_001)
        runCurrent()
        val events = dispatcher.snapshot(0).events.associateBy { it.data["job_id"] }
        assertEquals("failed", events[failed]?.data?.get("status"))
        assertEquals("no logs", events[failed]?.data?.get("error"))
        assertEquals("timed out", events[slow]?.data?.get("error"))
        assertNotNull(jobs.start("watch.gatherLogs", "A", "pkg", null, 1_000) { emptyMap() })
    }

    @Test fun ordinaryEventsStillReachEveryPermittedClient() {
        val event = EventDispatcher("boot").let { it.emit("system", "bt.state"); it.snapshot(0).events.single() }
        assertNotNull(EventAccessPolicy.event(event, setOf("system"), "pkg"))
        assertNotNull(EventAccessPolicy.event(event, setOf("system")))
    }
}
