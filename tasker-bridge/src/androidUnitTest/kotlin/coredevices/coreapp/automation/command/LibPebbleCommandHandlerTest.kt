package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.events.ConnectivityCollector
import coredevices.coreapp.automation.events.EventDispatcher
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleConnectionEvent
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioral integration tests: drive the REAL command handler + connectivity collector against a
 * SIMULATED connected watch (LibPebble mocked; the Bluetooth link is the only thing stubbed).
 *
 * These cover the Phase 2/3 acceptance behaviors that don't strictly need RF to the wrist — watch
 * info, notification build + custom-action round-trip, set-pref, quick-launch routing, and the
 * watch.connected event — at the actuation layer, not just compilation.
 */
class LibPebbleCommandHandlerTest {

    private fun fakeWatch(serialId: String = "Q3050ABC"): CommonConnectedDevice = mockk(relaxed = true) {
        every { serial } returns serialId
        every { name } returns "Pebble Time 2"
        every { runningFwVersion } returns "4.6.0"
        every { batteryLevel } returns 88
    }

    private fun handler(lp: LibPebble) = LibPebbleCommandHandler(lp, EventDispatcher(bootId = "test"))

    @Test
    fun getInfo_returnsSimulatedWatchInfo() = runTest {
        val lp = mockk<LibPebble>(relaxed = true)
        every { lp.watches } returns MutableStateFlow(listOf<PebbleDevice>(fakeWatch("Q3050ABC")))

        val result = handler(lp).handle(CommandEnvelope(type = CommandCatalog.WATCH_GET_INFO))

        assertTrue(result is CommandResult.Ok)
        assertEquals("Q3050ABC", result.data["serial"])
        assertEquals("4.6.0", result.data["fw"])
        assertEquals("88", result.data["battery"])
    }

    @Test
    fun sendNotification_buildsActions_andCustomActionRoundTrips() = runTest {
        val lp = mockk<LibPebble>(relaxed = true)
        val notifSlot = slot<TimelineNotification>()
        var capturedHandlers: Map<UByte, CustomTimelineActionHandler>? = null
        coEvery { lp.sendNotification(capture(notifSlot), any()) } answers { capturedHandlers = secondArg() }

        val dispatcher = EventDispatcher(bootId = "test")
        val result = LibPebbleCommandHandler(lp, dispatcher).handle(
            CommandEnvelope(
                type = CommandCatalog.NOTIFICATION_SEND,
                args = mapOf(
                    "title" to "Build done",
                    "body" to "All green",
                    "actions_json" to """[{"id":"a","label":"Ack","type":"generic"},{"id":"b","label":"Mute","type":"dismiss"}]""",
                ),
            ),
        )

        assertTrue(result is CommandResult.Ok)
        assertEquals("true", result.data["delivered"])
        // Both custom actions were parsed onto the notification.
        assertEquals(2, notifSlot.captured.content.actions.size)

        // Simulate the user pressing action 0 on the watch -> a notif.action event is emitted (the round-trip).
        val pressed = capturedHandlers?.get(0u)
        assertNotNull(pressed)
        pressed.invoke(emptyList())
        val events = dispatcher.since(0)
        assertEquals(1, events.size)
        assertEquals("notif.action", events.first().type)
        assertEquals("Ack", events.first().data["label"])
    }

    @Test
    fun setPref_writesTypedBooleanPreference_fromFriendlyInput() = runTest {
        val lp = mockk<LibPebble>(relaxed = true)
        val prefSlot = slot<WatchPreference<*>>()
        every { lp.setWatchPref(capture(prefSlot)) } just Runs

        val result = handler(lp).handle(
            CommandEnvelope(
                type = CommandCatalog.WATCH_SET_PREF,
                args = mapOf("pref_key" to "clock24h", "pref_value" to "true"),
            ),
        )

        assertTrue(result is CommandResult.Ok)
        assertEquals("clock24h", prefSlot.captured.pref.id)
        assertEquals(true, prefSlot.captured.value)
    }

    @Test
    fun setQuickLaunch_mapsHoldUp_toQlUpWithUuid() = runTest {
        val lp = mockk<LibPebble>(relaxed = true)
        val prefSlot = slot<WatchPreference<*>>()
        every { lp.setWatchPref(capture(prefSlot)) } just Runs
        val uuid = "ed429c16-f674-4220-95da-454f303f15e2"

        val result = handler(lp).handle(
            CommandEnvelope(
                type = CommandCatalog.WATCH_SET_QUICK_LAUNCH,
                args = mapOf("button" to "up", "press" to "long", "uuid" to uuid),
            ),
        )

        assertTrue(result is CommandResult.Ok)
        assertEquals("qlUp", prefSlot.captured.pref.id)
        val setting = prefSlot.captured.value as QuickLaunchSetting
        assertTrue(setting.enabled)
        assertEquals(uuid, setting.uuid.toString())
    }

    @Test
    fun connectivityCollector_emitsWatchConnected_onConnectEvent() = runTest {
        val connectionEvents = MutableSharedFlow<PebbleConnectionEvent>(extraBufferCapacity = 8)
        val lp = mockk<LibPebble>(relaxed = true)
        every { lp.connectionEvents } returns connectionEvents
        every { lp.watches } returns MutableStateFlow(emptyList<PebbleDevice>())
        val dispatcher = EventDispatcher(bootId = "test")

        ConnectivityCollector(lp, dispatcher).start(backgroundScope)
        runCurrent() // let the collectors subscribe
        connectionEvents.emit(PebbleConnectionEvent.PebbleConnectedEvent(fakeWatch("Q3050XYZ")))
        runCurrent() // process the emission

        val events = dispatcher.since(0)
        assertTrue(events.any { it.type == "watch.connected" && it.watch?.serial == "Q3050XYZ" })
    }
}
