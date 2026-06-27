package coredevices.coreapp.automation.command

import co.touchlab.kermit.Logger
import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.events.EventDispatcher
import io.rebble.libpebblecommon.SystemAppIDs.ANDROID_NOTIFICATIONS_UUID
import io.rebble.libpebblecommon.connection.ActiveDevice
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.database.entity.WatchPrefType
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
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
    private val dispatcher: EventDispatcher,
) : CommandHandler {
    private val logger = Logger.withTag("AutomationBridge")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(command: CommandEnvelope): CommandResult = when (command.type) {
        CommandCatalog.WATCH_GET_INFO -> getInfo(command)
        CommandCatalog.SYSTEM_PING -> ping(command)
        CommandCatalog.WATCH_LAUNCH_APP -> launchApp(command)
        CommandCatalog.WATCH_SET_WATCHFACE -> launchApp(command) // launching a face UUID switches it
        CommandCatalog.WATCH_CONNECT -> connect(command)
        CommandCatalog.WATCH_DISCONNECT -> disconnect(command)
        CommandCatalog.DEV_TOGGLE_CONNECTION -> toggleDev(command)
        CommandCatalog.APPMESSAGE_SEND -> sendAppMessage(command)
        CommandCatalog.NOTIFICATION_SEND -> sendNotification(command)
        CommandCatalog.WATCH_SET_PREF -> setPref(command)
        CommandCatalog.WATCH_SET_QUICK_LAUNCH -> setQuickLaunch(command)
        CommandCatalog.SYSTEM_GET_LOCKER -> getLocker(command)

        else -> CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "unknown command '${command.type}'")
    }

    // --- system.getLocker (read-only locker list for client-side pickers) ---
    @Serializable
    private data class LockerEntryDto(val uuid: String, val title: String, val type: String)

    private suspend fun getLocker(command: CommandEnvelope): CommandResult {
        val types = when (command.args["type"]?.lowercase()) {
            "watchface" -> listOf(AppType.Watchface)
            "watchapp" -> listOf(AppType.Watchapp)
            else -> listOf(AppType.Watchapp, AppType.Watchface)
        }
        val entries = buildList {
            for (t in types) {
                libPebble.getLocker(t, null, 1000).first().forEach { w ->
                    add(LockerEntryDto(w.properties.id.toString(), w.properties.title, w.properties.type.code))
                }
            }
        }
        return CommandResult.Ok(mapOf("entries" to json.encodeToString(entries)))
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

    // --- notification.send (builds a TimelineNotification; custom actions round-trip as notif.action) ---
    @Serializable
    private data class NotifAction(
        val id: String? = null,
        val label: String = "",
        val type: String = "generic",
    )

    private suspend fun sendNotification(command: CommandEnvelope): CommandResult {
        val a = command.args
        val titleArg = a["title"]?.takeIf { it.isNotBlank() }
        val bodyArg = a["body"]?.takeIf { it.isNotBlank() }
        val subtitleArg = a["subtitle"]?.takeIf { it.isNotBlank() }
        val iconArg = a["icon"]?.let { name -> TimelineIcon.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val vibePattern: List<UInt>? = CommandArgs.vibePattern(a["vibe"])
        val actionDefs: List<NotifAction> = a["actions_json"]?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { json.decodeFromString<List<NotifAction>>(raw) }.getOrNull()
        } ?: emptyList()

        val notification = buildTimelineNotification(
            parentId = ANDROID_NOTIFICATIONS_UUID,
            timestamp = Clock.System.now(),
        ) {
            attributes {
                titleArg?.let { v -> title { v } }
                bodyArg?.let { v -> body { v } }
                subtitleArg?.let { v -> subtitle { v } }
                iconArg?.let { ic -> tinyIcon { ic } }
                vibePattern?.let { vp -> vibrationPattern { vp } }
            }
            if (actionDefs.isNotEmpty()) {
                actions {
                    actionDefs.forEach { def -> action(def.type.toActionType()) { attributes { title { def.label } } } }
                }
            }
        }

        // Custom-action round-trip: each press emits a notif.action event the plugin's E6 catches.
        val handlers: Map<UByte, CustomTimelineActionHandler>? = if (actionDefs.isEmpty()) {
            null
        } else {
            actionDefs.mapIndexed { index, def ->
                val handler: CustomTimelineActionHandler = { _ ->
                    dispatcher.emit(
                        category = "notifications",
                        type = "notif.action",
                        data = mapOf(
                            "item_id" to notification.itemId.toString(),
                            "action_id" to index.toString(),
                            "label" to def.label,
                            "type" to def.type,
                        ),
                    )
                    TimelineActionResult(success = true, icon = TimelineIcon.GenericConfirmation, title = "OK")
                }
                index.toUByte() to handler
            }.toMap()
        }

        libPebble.sendNotification(notification, handlers)
        return CommandResult.Ok(mapOf("item_id" to notification.itemId.toString(), "delivered" to "true"))
    }

    private fun String.toActionType(): TimelineItem.Action.Type = when (trim().lowercase()) {
        "dismiss" -> TimelineItem.Action.Type.Dismiss
        "reply", "response" -> TimelineItem.Action.Type.Response
        "open", "openwatchapp", "launch" -> TimelineItem.Action.Type.OpenWatchapp
        else -> TimelineItem.Action.Type.Generic
    }

    // --- watch.setPref (generic typed pref via WatchPref.decodeValue) ---
    private fun setPref(command: CommandEnvelope): CommandResult {
        val prefKey = command.args["pref_key"]?.trim()
        if (prefKey.isNullOrEmpty()) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing 'pref_key'")
        val pref = WatchPref.from(prefKey)
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "unknown pref '$prefKey'")
        val raw = command.args["pref_value"].orEmpty()
        val encoded = if (pref.type == WatchPrefType.TypeBoolean) CommandArgs.normalizeBool(raw) else raw
        return applyPref(pref, encoded)
    }

    /** Star-capture helper: decode the string with the pref's own codec and write it. */
    private fun <T> applyPref(pref: WatchPref<T>, encoded: String): CommandResult {
        val value = try {
            pref.decodeValue(encoded)
        } catch (e: Exception) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "bad value for '${pref.id}': ${e.message}")
        }
        libPebble.setWatchPref(WatchPreference(pref, value))
        return CommandResult.Ok(mapOf("pref_id" to pref.id, "value" to pref.encodeValue(value)))
    }

    // --- watch.setQuickLaunch (maps button+press -> a Quick Launch pref) ---
    private fun setQuickLaunch(command: CommandEnvelope): CommandResult {
        val button = command.args["button"]?.trim()?.lowercase()
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing 'button'")
        val press = command.args["press"]?.trim()?.lowercase() ?: "long"
        val prefId = CommandArgs.quickLaunchPrefId(button, press)
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "unknown button/press '$button'/'$press'")
        val pref = WatchPref.from(prefId) as? QuicklaunchWatchPref
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "no quick-launch pref '$prefId'")
        val uuidStr = command.args["uuid"]?.trim()?.takeIf { it.isNotEmpty() }
        val uuid = uuidStr?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (uuidStr != null && uuid == null) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid 'uuid'")
        }
        val setting = QuickLaunchSetting(enabled = uuid != null, uuid = uuid)
        libPebble.setWatchPref(WatchPreference(pref, setting))
        return CommandResult.Ok(mapOf("pref_id" to prefId, "enabled" to setting.enabled.toString()))
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
