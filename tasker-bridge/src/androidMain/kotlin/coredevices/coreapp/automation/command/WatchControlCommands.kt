package coredevices.coreapp.automation.command

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import coredevices.coreapp.automation.AutomationFiles
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.events.toWatchRef
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.database.entity.WatchPref
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Watch control, firmware, preference and diagnostics commands (see [CommandCatalog]). */
class WatchControlCommands(
    private val libPebble: LibPebble,
    private val control: WatchControl,
    private val jobs: AutomationJobs,
    private val files: AutomationFiles,
    private val cooldowns: Cooldowns = Cooldowns(),
    private val clock: Clock = Clock.System,
) {
    private val watches = WatchSelector(libPebble)
    private val json = Json { explicitNulls = false }

    fun isAvailable(type: String): Boolean = when (type) {
        CommandCatalog.WATCH_PRESS_BUTTON, CommandCatalog.WATCH_SWIPE -> control.remoteInputAvailable
        else -> true
    }

    suspend fun handle(command: CommandEnvelope, clientIdentity: String): CommandResult = when (command.type) {
        CommandCatalog.WATCH_REBOOT -> reboot(command)
        CommandCatalog.WATCH_PRESS_BUTTON -> pressButton(command)
        CommandCatalog.WATCH_SWIPE -> swipe(command)
        CommandCatalog.WATCH_STOP_APP -> stopApp(command)
        CommandCatalog.WATCH_SCREENSHOT -> screenshot(command, clientIdentity)
        CommandCatalog.WATCH_SYNC_TIME -> syncTime(command)
        CommandCatalog.WATCH_CHECK_FIRMWARE -> checkFirmware(command)
        CommandCatalog.WATCH_INSTALL_FIRMWARE -> installFirmware(command)
        CommandCatalog.WATCH_LIST_PREFS -> listPrefs(command)
        CommandCatalog.WATCH_GET_PREF -> getPref(command)
        CommandCatalog.WATCH_GATHER_LOGS -> gatherLogs(command, clientIdentity)
        CommandCatalog.WATCH_FACTORY_RESET -> factoryReset(command)
        else -> CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "unknown command '${command.type}'")
    }

    private suspend fun reboot(command: CommandEnvelope): CommandResult {
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        val wait = cooldowns.tryAcquire("reboot:${device.identifier.asString}", REBOOT_COOLDOWN_MS)
        if (wait > 0) return cooldown(command.type, wait)
        control.reboot(device)
        return CommandResult.Ok(mapOf("serial" to device.serial, "rebooting" to "true"))
    }

    private suspend fun pressButton(command: CommandEnvelope): CommandResult {
        val a = command.args
        val button = CommandArgs.buttonId(a["button"])
            ?: return invalid("'button' must be back, up, select or down")
        val presses = CommandArgs.boundedInt(a["presses"], 1..255, 1) ?: return invalid("'presses' must be 1-255")
        val holdMs = CommandArgs.boundedInt(a["hold_ms"], 0..65_535, DEFAULT_HOLD_MS) ?: return invalid("'hold_ms' must be 0-65535")
        val gapMs = CommandArgs.boundedInt(a["gap_ms"], 0..65_535, DEFAULT_GAP_MS) ?: return invalid("'gap_ms' must be 0-65535")
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        return remoteInput(device, control.pressButton(device, button, presses, holdMs, gapMs))
    }

    private suspend fun swipe(command: CommandEnvelope): CommandResult {
        val direction = CommandArgs.swipeDirection(command.args["direction"])
            ?: return invalid("'direction' must be up, down, left or right")
        val durationMs = CommandArgs.boundedInt(command.args["duration_ms"], 1..MAX_SWIPE_MS, DEFAULT_SWIPE_MS)
            ?: return invalid("'duration_ms' must be 1-$MAX_SWIPE_MS")
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        return remoteInput(device, control.swipe(device, direction, durationMs))
    }

    private fun remoteInput(device: ConnectedPebbleDevice, outcome: RemoteInputOutcome): CommandResult = when (outcome) {
        RemoteInputOutcome.OK -> CommandResult.Ok(mapOf("serial" to device.serial, "accepted" to "true"))
        RemoteInputOutcome.BUSY -> CommandResult.Failure(ErrorCode.WATCH_BUSY, "the watch is still running another injected input")
        RemoteInputOutcome.INVALID -> CommandResult.Failure(ErrorCode.INVALID_ARGS, "the watch rejected the input (unsupported on this watch or out of range)")
        RemoteInputOutcome.UNSUPPORTED -> CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "remote input needs watch firmware 4.35.0 or later")
        RemoteInputOutcome.TIMEOUT -> CommandResult.Failure(ErrorCode.TIMEOUT, "the watch did not acknowledge the input; whether it ran is unknown")
    }

    private suspend fun stopApp(command: CommandEnvelope): CommandResult {
        val requested = command.args["uuid"]?.takeIf { it.isNotBlank() }
        val parsed = requested?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: return invalid("invalid 'uuid'") }
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        val uuid = parsed ?: device.runningApp.value ?: return invalid("no app is running")
        device.stopApp(uuid)
        return CommandResult.Ok(mapOf("serial" to device.serial, "uuid" to uuid.toString()))
    }

    private fun screenshot(command: CommandEnvelope, clientIdentity: String): CommandResult {
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        if (clientIdentity.isBlank()) return noIdentity()
        val jobId = jobs.start(command.type, device.identifier.asString, clientIdentity, device.toWatchRef(), SCREENSHOT_TIMEOUT_MS) {
            val image = device.takeScreenshot() ?: error("the watch returned no screenshot")
            val file = files.newFile("screenshots", "png")
            file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            mapOf("uri" to files.share(file, clientIdentity), "mime" to "image/png",
                "width" to image.width.toString(), "height" to image.height.toString())
        } ?: return CommandResult.Failure(ErrorCode.WATCH_BUSY, "a screenshot of this watch is already in progress")
        return CommandResult.Ok(mapOf("serial" to device.serial, "job_id" to jobId))
    }

    private suspend fun syncTime(command: CommandEnvelope): CommandResult {
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        val key = "time:${device.identifier.asString}"
        val wait = cooldowns.tryAcquire(key, TIME_RETRY_COOLDOWN_MS)
        if (wait > 0) return cooldown(command.type, wait)
        val outcome = try {
            control.syncTime(device)
        } finally {
            cooldowns.finish(key, TIME_RETRY_COOLDOWN_MS)
        }
        return when (outcome) {
            is TimeSyncOutcome.Verified -> {
                cooldowns.finish(key, TIME_SUCCESS_COOLDOWN_MS)
                CommandResult.Ok(mapOf("serial" to device.serial, "verified" to "true", "skew_s" to outcome.skewSeconds.toString()))
            }
            TimeSyncOutcome.Unverified -> CommandResult.Ok(mapOf("serial" to device.serial, "verified" to "false"))
            is TimeSyncOutcome.Mismatch -> CommandResult.Failure(ErrorCode.INTERNAL, "the watch clock is still ${outcome.skewSeconds}s off")
            TimeSyncOutcome.Timeout -> CommandResult.Failure(ErrorCode.TIMEOUT, "the watch did not report its time back")
        }
    }

    private suspend fun checkFirmware(command: CommandEnvelope): CommandResult {
        val force = when (command.args["force"]?.let(CommandArgs::normalizeBool)) {
            null, "0" -> false
            "1" -> true
            else -> return invalid("invalid 'force'")
        }
        val device = watches.connected(command.watch) ?: return watches.noWatch(command.watch)
        device.checkforFirmwareUpdate(force)
        val result = withTimeoutOrNull(FIRMWARE_CHECK_WAIT_MS) {
            var sawChecking = false
            libPebble.watches.map { list ->
                (list.firstOrNull { it.identifier == device.identifier } as? CommonConnectedDevice)?.firmwareUpdateAvailable
            }.first { state ->
                if (state?.checkingForUpdates == true) sawChecking = true
                sawChecking && state?.checkingForUpdates == false
            }?.result
        }
        return CommandResult.Ok(buildMap {
            put("serial", device.serial)
            when (result) {
                is FirmwareUpdateCheckResult.FoundUpdate -> { put("status", "available"); put("version", result.version.stringVersion) }
                FirmwareUpdateCheckResult.FoundNoUpdate -> put("status", "none")
                is FirmwareUpdateCheckResult.UpdateCheckFailed -> { put("status", "failed"); put("error", result.error) }
                null -> put("status", "pending")
            }
        })
    }

    private fun installFirmware(command: CommandEnvelope): CommandResult {
        val device = watches.connected(command.watch) ?: return watches.noWatch(command.watch)
        if (device.firmwareUpdateState !is FirmwareUpdateStatus.NotInProgress) {
            return CommandResult.Failure(ErrorCode.WATCH_BUSY, "a firmware update is already in progress")
        }
        val update = device.firmwareUpdateAvailable.result as? FirmwareUpdateCheckResult.FoundUpdate
        if (update == null) {
            val checkedAt = control.firmwareCheckedAt(device)
            return if (checkedAt != null && clock.now() - checkedAt < 24.hours) {
                CommandResult.Failure(ErrorCode.FIRMWARE_UPDATE_UNAVAILABLE, "no firmware update is available")
            } else {
                CommandResult.Failure(ErrorCode.FIRMWARE_CHECK_STALE,
                    "no firmware update is available and no update check has succeeded in the last 24 hours; run watch.checkFirmware first")
            }
        }
        val expected = command.args["version"]?.takeIf { it.isNotBlank() }
        if (expected != null && expected != update.version.stringVersion) {
            return invalid("the available update is ${update.version.stringVersion}, not $expected")
        }
        device.updateFirmware(update)
        return CommandResult.Ok(mapOf("serial" to device.serial, "version" to update.version.stringVersion, "started" to "true"))
    }

    private suspend fun listPrefs(command: CommandEnvelope): CommandResult {
        val device = watches.connected(command.watch) ?: return watches.noWatch(command.watch)
        val stored = libPebble.watchPrefs.first().associateBy { it.pref.id }
        val entries = PrefListing.listable().map { PrefListing.entry(it, stored[it.id], control.prefSupport(device, it.id)) }
        return CommandResult.Ok(mapOf("serial" to device.serial, "count" to entries.size.toString(), "prefs" to json.encodeToString(entries)))
    }

    private suspend fun getPref(command: CommandEnvelope): CommandResult {
        val key = command.args["pref_key"]?.trim()
        if (key.isNullOrEmpty()) return invalid("missing 'pref_key'")
        val pref = WatchPref.from(key)?.takeIf { !it.isDebugSetting } ?: return invalid("unknown pref '$key'")
        val device = watches.connected(command.watch) ?: return watches.noWatch(command.watch)
        val support = control.prefSupport(device, pref.id)
        if (support == PrefSupport.UNSUPPORTED) return unsupportedPref(pref, device)
        val stored = libPebble.watchPrefs.first().firstOrNull { it.pref.id == pref.id }
        val entry = PrefListing.entry(pref, stored, support)
        return CommandResult.Ok(buildMap {
            put("serial", device.serial)
            put("pref_key", entry.key)
            put("label", entry.label)
            entry.description?.let { put("description", it) }
            put("type", entry.type)
            put("value", entry.value)
            put("default", entry.default)
            put("support", entry.support)
            entry.options?.let { put("options", json.encodeToString(it)) }
            entry.min?.let { put("min", it.toString()) }
            entry.max?.let { put("max", it.toString()) }
            entry.unit?.let { put("unit", it) }
        })
    }

    private fun gatherLogs(command: CommandEnvelope, clientIdentity: String): CommandResult {
        val device = watches.connected(command.watch) ?: return watches.noWatch(command.watch)
        if (clientIdentity.isBlank()) return noIdentity()
        val jobId = jobs.start(command.type, device.identifier.asString, clientIdentity, device.toWatchRef(), LOGS_TIMEOUT_MS) {
            val dump = File(device.gatherLogs()?.toString() ?: error("the watch returned no logs"))
            val file = files.newFile("logs", "txt")
            try { dump.copyTo(file, overwrite = true) } finally { dump.delete() }
            mapOf("uri" to files.share(file, clientIdentity), "mime" to "text/plain", "bytes" to file.length().toString())
        } ?: return CommandResult.Failure(ErrorCode.WATCH_BUSY, "log gathering for this watch is already in progress")
        return CommandResult.Ok(mapOf("serial" to device.serial, "job_id" to jobId))
    }

    private fun factoryReset(command: CommandEnvelope): CommandResult {
        val device = pebble(command.watch) ?: return watches.noWatch(command.watch)
        if (command.args["confirm_serial"] != device.serial) {
            return invalid("'confirm_serial' must equal the target watch's serial")
        }
        device.factoryReset()
        return CommandResult.Ok(mapOf("serial" to device.serial, "factory_reset" to "started"))
    }

    private fun pebble(selector: String?): ConnectedPebbleDevice? = watches.connected(selector) as? ConnectedPebbleDevice

    private fun invalid(message: String) = CommandResult.Failure(ErrorCode.INVALID_ARGS, message)

    private fun noIdentity() = CommandResult.Failure(ErrorCode.NOT_AUTHORIZED, "missing verified client identity")

    private fun cooldown(type: String, waitMs: Long) =
        CommandResult.Failure(ErrorCode.RATE_LIMITED, "'$type' is cooling down for this watch; retry in ${(waitMs + 999) / 1000}s")

    companion object {
        val TYPES: Set<String> = setOf(
            CommandCatalog.WATCH_REBOOT, CommandCatalog.WATCH_PRESS_BUTTON, CommandCatalog.WATCH_SWIPE,
            CommandCatalog.WATCH_STOP_APP, CommandCatalog.WATCH_SCREENSHOT, CommandCatalog.WATCH_SYNC_TIME,
            CommandCatalog.WATCH_CHECK_FIRMWARE, CommandCatalog.WATCH_INSTALL_FIRMWARE, CommandCatalog.WATCH_LIST_PREFS,
            CommandCatalog.WATCH_GET_PREF, CommandCatalog.WATCH_GATHER_LOGS, CommandCatalog.WATCH_FACTORY_RESET,
        )
        const val REBOOT_COOLDOWN_MS = 60_000L
        const val TIME_SUCCESS_COOLDOWN_MS = 30 * 60_000L
        const val TIME_RETRY_COOLDOWN_MS = 30_000L
        const val DEFAULT_HOLD_MS = 50
        const val DEFAULT_GAP_MS = 100
        const val DEFAULT_SWIPE_MS = 150
        /** PebbleOS SWIPE_MAX_DURATION_MS; a slower contact is read as a pan. */
        const val MAX_SWIPE_MS = 300
        private const val FIRMWARE_CHECK_WAIT_MS = 4_500L
        private const val SCREENSHOT_TIMEOUT_MS = 60_000L
        private const val LOGS_TIMEOUT_MS = 10 * 60_000L

        internal fun unsupportedPref(pref: WatchPref<*>, device: CommonConnectedDevice) =
            CommandResult.Failure(ErrorCode.PREF_UNSUPPORTED, "'${pref.id}' is not supported by watch ${device.serial}")
    }
}
