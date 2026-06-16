package coredevices.coreapp.automation.command

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import io.rebble.libpebblecommon.connection.ActiveDevice
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlin.uuid.Uuid

/**
 * LibPebble-backed implementation of the command allowlist (HOOKS.md §3). Pure actuation: the
 * [CommandExecutor] has already authorized/gated/rate-limited the command, so this class only resolves
 * the target watch and calls LibPebble. Every branch returns a typed [CommandResult]; unimplemented
 * (payload-heavy) commands return UNSUPPORTED_COMMAND so the host can light them up incrementally
 * without breaking the contract.
 *
 * Watch selection: [CommandEnvelope.watch] is matched against a connected device's serial or BT
 * address; absent ⇒ the single/active connected watch. Facade-level commands (launchApp, ping) fan out
 * to all connected watches via [LibPebble] and ignore the selector by design.
 */
class LibPebbleCommandHandler(
    private val libPebble: LibPebble,
) : CommandHandler {
    private val logger = Logger.withTag("AutomationBridge")

    override suspend fun handle(command: CommandEnvelope): CommandResult = when (command.type) {
        CommandCatalog.WATCH_GET_INFO -> getInfo(command)
        CommandCatalog.SYSTEM_PING -> ping(command)
        CommandCatalog.WATCH_LAUNCH_APP -> launchApp(command)
        CommandCatalog.WATCH_SET_WATCHFACE -> launchApp(command) // launching a face UUID switches it
        CommandCatalog.WATCH_CONNECT -> connect(command)
        CommandCatalog.WATCH_DISCONNECT -> disconnect(command)
        CommandCatalog.DEV_TOGGLE_CONNECTION -> toggleDev(command)
        CommandCatalog.APPMESSAGE_SEND -> sendAppMessage(command)

        // Payload-heavy commands: builder types (TimelineNotification, typed WatchPreference<*>) are
        // out of this representative slice. Wire these in the host where those builders are in scope.
        CommandCatalog.NOTIFICATION_SEND,
        CommandCatalog.WATCH_SET_PREF,
        -> CommandResult.Failure(
            ErrorCode.UNSUPPORTED_COMMAND,
            "command '${command.type}' is not yet implemented in the bridge handler",
        )

        else -> CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "unknown command '${command.type}'")
    }

    // --- watch.getInfo ---
    private fun getInfo(command: CommandEnvelope): CommandResult {
        val device = resolveConnected(command.watch)
            ?: return noWatch(command.watch)
        return CommandResult.Ok(
            buildMap {
                put("serial", device.serial)
                put("name", device.name)
                device.nickname?.let { put("nickname", it) }
                put("model", device.watchInfo.board)
                put("fw", device.runningFwVersion)
                device.batteryLevel?.let { put("battery", it.toString()) }
                put("address", device.identifier.asString)
                put("connected", "true")
                (device as? ConnectedPebbleDevice)?.runningApp?.value?.let { put("running_app", it.toString()) }
            },
        )
    }

    // --- system.ping (facade fan-out) ---
    private suspend fun ping(command: CommandEnvelope): CommandResult {
        val cookie = command.args["cookie"]?.toUIntOrNull() ?: 0u
        libPebble.sendPing(cookie)
        return CommandResult.Ok(mapOf("sent" to "true"))
    }

    // --- watch.launchApp / watch.setWatchface (facade fan-out) ---
    private suspend fun launchApp(command: CommandEnvelope): CommandResult {
        val uuid = command.args["uuid"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing/invalid 'uuid'")
        libPebble.launchApp(uuid)
        return CommandResult.Ok(mapOf("uuid" to uuid.toString()))
    }

    // --- watch.connect ---
    private fun connect(command: CommandEnvelope): CommandResult {
        val device = resolveKnown(command.watch) ?: return noWatch(command.watch)
        device.connect()
        return CommandResult.Ok(mapOf("serial" to device.serial))
    }

    // --- watch.disconnect ---
    private fun disconnect(command: CommandEnvelope): CommandResult {
        val device = resolveConnected(command.watch) ?: return noWatch(command.watch)
        (device as ActiveDevice).disconnect()
        return CommandResult.Ok(mapOf("serial" to device.serial))
    }

    // --- dev.toggleConnection (dangerous) ---
    private suspend fun toggleDev(command: CommandEnvelope): CommandResult {
        val device = resolveConnected(command.watch) as? ConnectedPebbleDevice
            ?: return noWatch(command.watch)
        val enable = when (command.args["enable"]?.lowercase()) {
            "true", "1", "on" -> true
            "false", "0", "off" -> false
            null -> !device.devConnectionActive.value // toggle
            else -> return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid 'enable'")
        }
        if (enable) device.startDevConnection() else device.stopDevConnection()
        return CommandResult.Ok(mapOf("dev_enabled" to enable.toString()))
    }

    // --- appmessage.send ---
    private suspend fun sendAppMessage(command: CommandEnvelope): CommandResult {
        val device = resolveConnected(command.watch) as? ConnectedPebbleDevice
            ?: return noWatch(command.watch)
        val uuid = command.args["uuid"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing/invalid 'uuid'")
        // dict is "key=intValue" pairs in args under keys like "d.<int>"; values are sent as Int/String.
        val dict: Map<Int, Any> = command.args
            .filterKeys { it.startsWith("d.") }
            .mapNotNull { (k, v) ->
                val key = k.removePrefix("d.").toIntOrNull() ?: return@mapNotNull null
                val value: Any = v.toIntOrNull() ?: v
                key to value
            }
            .toMap()
        if (dict.isEmpty()) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "empty appmessage dict")
        val txn = device.transactionSequence.next()
        val result = device.sendAppMessage(AppMessageData(txn, uuid, dict))
        val acked = result is AppMessageResult.ACK
        return CommandResult.Ok(mapOf("uuid" to uuid.toString(), "acked" to acked.toString()))
    }

    // --- watch resolution helpers ---
    private fun resolveConnected(selector: String?): CommonConnectedDevice? {
        val connected = libPebble.watches.value.filterIsInstance<CommonConnectedDevice>()
        return if (selector.isNullOrBlank()) {
            connected.firstOrNull()
        } else {
            connected.firstOrNull { it.serial == selector || it.identifier.asString == selector }
        }
    }

    private fun resolveKnown(selector: String?): KnownPebbleDevice? {
        val known = libPebble.watches.value.filterIsInstance<KnownPebbleDevice>()
        return if (selector.isNullOrBlank()) {
            known.firstOrNull()
        } else {
            known.firstOrNull { it.serial == selector || it.identifier.asString == selector }
        }
    }

    private fun noWatch(selector: String?): CommandResult {
        logger.d { "no matching watch for selector=$selector" }
        return CommandResult.Failure(
            ErrorCode.INVALID_ARGS,
            if (selector.isNullOrBlank()) "no connected watch" else "no watch matching '$selector'",
        )
    }
}
