package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.buffer
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Facade-level event collectors (HLDD-001 Â§9, PLAN Â§6.2) â€” sources that live on [LibPebble] itself
 * rather than on a single connection:
 *   - currentCall        -> calls.state
 *   - userFacingErrors   -> system.error
 *   - healthDataUpdated  -> health.updated
 *   - bluetoothEnabled  -> bt.state
 */
class SystemEventCollector(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var hadCall = false
            libPebble.currentCall.collect { call ->
                if (call != null) {
                    hadCall = true
                    dispatcher.emit(
                        category = "calls",
                        type = "calls.state",
                        data = mapOf(
                            "state" to when (call) {
                                is Call.RingingCall -> "ringing"
                                is Call.DialingCall -> "dialing"
                                is Call.ActiveCall -> "active"
                                is Call.HoldingCall -> "holding"
                            },
                            "number" to call.contactNumber,
                            "caller_name" to (call.contactName ?: ""),
                        ),
                    )
                } else if (hadCall) {
                    // Call ended: emit the closing transition so a 'while in call' automation can reset.
                    hadCall = false
                    dispatcher.emit(category = "calls", type = "calls.state", data = mapOf("state" to "ended"))
                }
            }
        }
        scope.launch {
            libPebble.userFacingErrors.buffer(64).collect { error ->
                dispatcher.emit(
                    category = "system",
                    type = "system.error",
                    data = mapOf(
                        "error_type" to when (error) {
                            is UserFacingError.FailedToDownloadPbw -> "failed_to_download_pbw"
                            is UserFacingError.FailedToRemovePbwFromLocker -> "failed_to_remove_pbw"
                            is UserFacingError.FailedToSideloadApp -> "failed_to_sideload_app"
                            is UserFacingError.FailedToScan -> "failed_to_scan"
                            is UserFacingError.MissingCompanionApp -> "missing_companion_app"
                        },
                        "message" to error.message,
                    ),
                )
            }
        }
        scope.launch {
            libPebble.healthDataUpdated.collect {
                // LibPebble health storage is a global aggregate, not a per-watch measurement.
                val data = buildMap {
                    try {
                        val now = Instant.now()
                        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                        libPebble.getTotalHealthData(start, now.epochSecond)?.steps?.let { put("steps_today", it.toString()) }
                        libPebble.getLatestHeartRateReading()?.let {
                            put("latest_hr", it.bpm.toString())
                            put("latest_hr_timestamp", it.timestampEpochSec.toString())
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { put("available", "false") }
                }
                dispatcher.emit(category = "health", type = "health.updated", data = data)
            }
        }
        scope.launch {
            libPebble.bluetoothEnabled.collect { state ->
                dispatcher.emit("system", "bt.state", data = mapOf("enabled" to (state == BluetoothState.Enabled).toString()))
            }
        }
        // Actual app/watchface transitions come from per-watch runningApp. activeWatchface is a
        // phone-side selection and must not impersonate an observed launch on every watch.
    }
}
