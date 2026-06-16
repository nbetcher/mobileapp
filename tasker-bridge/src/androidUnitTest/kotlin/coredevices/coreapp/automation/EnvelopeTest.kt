package coredevices.coreapp.automation

import coredevices.coreapp.automation.events.EventEnvelope
import coredevices.coreapp.automation.events.WatchRef
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvelopeTest {

    @Test
    fun eventEnvelopeRoundTrips() {
        val env = EventEnvelope(
            bootId = "b", seq = 7, ts = 123, category = "connectivity", type = "watch.connected",
            watch = WatchRef(serial = "S", name = "N", battery = 80),
        )
        val back = BridgeJson.json.decodeFromString<EventEnvelope>(BridgeJson.json.encodeToString(env))
        assertEquals(env, back)
    }

    @Test
    fun eventBatchRoundTrips() {
        val batch = EventBatch(
            bootId = "b",
            events = listOf(EventEnvelope(bootId = "b", seq = 1, ts = 1, category = "c", type = "t")),
        )
        val back = BridgeJson.json.decodeFromString<EventBatch>(BridgeJson.json.encodeToString(batch))
        assertEquals(batch, back)
    }
}
