package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Facade-level event collectors (HLDD-001 §9, PLAN §6.2) — sources that live on [LibPebble] itself
 * rather than on a single connection:
 *   - currentCall        -> calls.state
 *   - userFacingErrors   -> system.error
 *   - healthDataUpdated  -> health.updated
 *   - activeWatchface    -> apps.run_state (kind=watchface)
 */
class SystemEventCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            libPebble.currentCall.collect { call ->
                if (call != null) {
                    dispatcher.emit(
                        category = "calls",
                        type = "calls.state",
                        data = mapOf(
                            "state" to (call::class.simpleName ?: "Call"),
                            "number" to call.contactNumber,
                            "name" to (call.contactName ?: ""),
                        ),
                    )
                }
            }
        }
        scope.launch {
            libPebble.userFacingErrors.collect { error ->
                dispatcher.emit(
                    category = "system",
                    type = "system.error",
                    data = mapOf(
                        "type" to (error::class.simpleName ?: "Error"),
                        "message" to error.message,
                    ),
                )
            }
        }
        scope.launch {
            libPebble.healthDataUpdated.collect {
                dispatcher.emit(category = "health", type = "health.updated")
            }
        }
        scope.launch {
            libPebble.activeWatchface.collect { face ->
                if (face != null) {
                    dispatcher.emit(
                        category = "apps",
                        type = "apps.run_state",
                        data = mapOf(
                            "kind" to "watchface",
                            "uuid" to face.properties.id.toString(),
                            "name" to face.properties.title,
                        ),
                    )
                }
            }
        }
    }
}
