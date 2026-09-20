package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Typed JSON dictionary using unsigned decimal Pebble keys; byte arrays use hex strings. */
class AppMessageCollector(
    private val dispatcher: EventDispatcher,
    private val libPebble: LibPebble,
) {
    @Suppress("UNUSED_PARAMETER")
    fun start(scope: CoroutineScope) {
        AutomationAppMessageHook.onReceived = { address, uuid, transactionId, dictionary ->
            val watch = libPebble.watches.value.firstOrNull { it.identifier.asString == address }
            if (watch == null) false else dispatcher.emit(
                category = "apps", type = "appmsg.received", watch = watch.toIdentityRef(),
                data = dictionaryPayload(uuid, transactionId, dictionary),
            )
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
internal fun dictionaryPayload(uuid: String, transactionId: Int, dictionary: Map<Int, Any>): Map<String, String> {
    val values = linkedMapOf<String, JsonPrimitive>()
    val types = linkedMapOf<String, JsonPrimitive>()
    for ((key, value) in dictionary) {
        val (type, encoded) = when (value) {
            is String -> "string" to JsonPrimitive(value)
            is ByteArray -> "bytes" to JsonPrimitive(value.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
            is UByteArray -> "bytes" to JsonPrimitive(value.joinToString("") { it.toString(16).padStart(2, '0') })
            // Framework tuples normalize every unsigned width to ULong (wire maximum is uint32).
            is ULong -> {
                require(value <= UInt.MAX_VALUE.toULong())
                "uint" to JsonPrimitive(value.toLong())
            }
            is UInt -> "uint" to JsonPrimitive(value.toLong())
            is UShort -> "uint" to JsonPrimitive(value.toInt())
            is UByte -> "uint" to JsonPrimitive(value.toInt())
            is Number -> "int" to JsonPrimitive(value)
            is Boolean -> "bool" to JsonPrimitive(value)
            else -> error("Unsupported AppMessage value")
        }
        val wireKey = key.toUInt().toString()
        values[wireKey] = encoded
        types[wireKey] = JsonPrimitive(type)
    }
    return mapOf("uuid" to uuid, "transaction_id" to transactionId.toString(),
        "dict_json" to JsonObject(values).toString(), "dict_types_json" to JsonObject(types).toString())
}
