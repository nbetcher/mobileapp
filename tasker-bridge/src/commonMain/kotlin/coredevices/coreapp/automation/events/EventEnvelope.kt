package coredevices.coreapp.automation.events

import kotlinx.serialization.Serializable

/**
 * Versioned JSON envelope for one automation event (HLDD-002 §4.3).
 *
 * NOTE (skeleton): [data] is a flat string map for now; HLDD-002 specifies a richer per-type JSON
 * object — this becomes a JsonObject once the AIDL/command layer lands.
 */
@Serializable
data class EventEnvelope(
    val v: Int = PROTOCOL_VERSION,
    val kind: String = "event",
    val bootId: String,
    val seq: Long,
    val ts: Long,
    val category: String,
    val type: String,
    val watch: WatchRef? = null,
    val data: Map<String, String> = emptyMap(),
) {
    companion object {
        const val PROTOCOL_VERSION: Int = 1
    }
}

/** Identity + headline state of a watch attached to an event (HLDD-002 §4.3 `watch`). */
@Serializable
data class WatchRef(
    val serial: String,
    val name: String,
    val nickname: String? = null,
    val model: String? = null,
    val fw: String? = null,
    val battery: Int? = null,
    val address: String? = null,
)
