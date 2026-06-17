package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import kotlinx.coroutines.CoroutineScope

/**
 * Bridges inbound watch AppMessages (the ★ AutoPebble channel, HOOKS.md §2.3) into appmsg.received
 * events. Registers the libpebble3 inbound hook, so every watchapp's messages are captured at the
 * single choke point without per-UUID subscription. The dictionary is flattened into d.<key>=<value>
 * args alongside the source uuid (mirrors the appmessage.send command's arg shape).
 */
class AppMessageCollector(
    private val dispatcher: EventDispatcher,
) {
    @Suppress("UNUSED_PARAMETER")
    fun start(scope: CoroutineScope) {
        AutomationAppMessageHook.onReceived = { uuid, data ->
            val args = buildMap {
                put("uuid", uuid)
                data.forEach { (key, value) -> put("d.$key", stringifyValue(value)) }
            }
            dispatcher.emit(category = "apps", type = "appmsg.received", data = args)
        }
    }

    /** Byte payloads must be hex-encoded; a (U)ByteArray.toString() is a useless identity hash. */
    private fun stringifyValue(value: Any): String = when (value) {
        is UByteArray -> value.joinToString("") { it.toString(16).padStart(2, '0') }
        is ByteArray -> value.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        else -> value.toString()
    }
}
