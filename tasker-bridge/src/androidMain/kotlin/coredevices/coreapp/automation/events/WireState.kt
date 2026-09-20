package coredevices.coreapp.automation.events

import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import kotlin.math.roundToInt

/** Stable protocol vocabulary, independent of Kotlin class names. */
internal fun FirmwareUpdateStatus.wireStatus(): String = when (this) {
    is FirmwareUpdateStatus.NotInProgress.Idle -> if (lastFailure == null) "idle" else "failed"
    is FirmwareUpdateStatus.NotInProgress.ErrorStarting -> "failed"
    is FirmwareUpdateStatus.WaitingToStart -> "waiting"
    is FirmwareUpdateStatus.InProgress -> "in_progress"
    is FirmwareUpdateStatus.WaitingForReboot -> "rebooting"
}

internal fun FirmwareUpdateStatus.wireProgress(): Int? =
    (this as? FirmwareUpdateStatus.InProgress)?.progress?.value?.takeIf { it.isFinite() }
        ?.let { (it.coerceIn(0f, 1f) * 100).roundToInt() }

internal val SUPPORTED_STATE_CAPABILITIES = listOf(
    "state.bluetooth", "state.dev", "state.firmware", "state.running_app",
)
