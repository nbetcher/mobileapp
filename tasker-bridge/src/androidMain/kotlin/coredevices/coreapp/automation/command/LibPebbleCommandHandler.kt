package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.CommandEnvelope
import coredevices.coreapp.automation.ErrorCode
import coredevices.coreapp.automation.events.EventDispatcher
import io.rebble.libpebblecommon.SystemAppIDs.ANDROID_NOTIFICATIONS_UUID
import io.rebble.libpebblecommon.automation.AutomationAppMessageHook
import io.rebble.libpebblecommon.connection.ActiveDevice
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.database.entity.WatchPrefType
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * LibPebble-backed implementation of the command allowlist (HOOKS.md §3). Pure actuation: the
 * [CommandExecutor] has already authorized/gated/rate-limited the command, so this class only resolves
 * the target watch and calls LibPebble. Every branch returns a typed [CommandResult]; unimplemented
 * (payload-heavy) commands return UNSUPPORTED_COMMAND so the host can light them up incrementally
 * without breaking the contract.
 *
 * Watch selection: [CommandEnvelope.watch] is matched against a connected device's serial or BT
 * address; absent requires exactly one connected watch. Global phone-side operations reject selectors.
 */
class LibPebbleCommandHandler(
    private val libPebble: LibPebble,
    private val dispatcher: EventDispatcher,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val control: WatchControl = LibPebbleWatchControl(),
    private val controlCommands: WatchControlCommands? = null,
) : CommandHandler {
    private val watches = WatchSelector(libPebble)
    private val json = Json { ignoreUnknownKeys = true }
    private val pingCookies = AtomicInteger(Random.nextInt())

    override suspend fun handle(command: CommandEnvelope, clientIdentity: String): CommandResult =
        if (command.type == CommandCatalog.APPMESSAGE_SUBSCRIBE) subscribeAppMessage(command, clientIdentity)
        else if (command.type == CommandCatalog.NOTIFICATION_SEND && command.watch.isNullOrBlank()) sendNotification(command, clientIdentity)
        else if (controlCommands != null && command.type in WatchControlCommands.TYPES) controlCommands.handle(command, clientIdentity)
        else handle(command)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        if (controlCommands != null && command.type in WatchControlCommands.TYPES) return controlCommands.handle(command, "")
        if (command.type in CommandCatalog.globalTypes && !command.watch.isNullOrBlank()) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "${command.type} is global; a watch selector is unsupported")
        }
        return when (command.type) {
        CommandCatalog.WATCH_GET_INFO -> getInfo(command)
        CommandCatalog.SYSTEM_PING -> ping(command)
        CommandCatalog.WATCH_LAUNCH_APP -> launchApp(command)
        CommandCatalog.WATCH_SET_WATCHFACE -> launchApp(command) // launching a face UUID switches it
        CommandCatalog.WATCH_CONNECT -> connect(command)
        CommandCatalog.WATCH_DISCONNECT -> disconnect(command)
        CommandCatalog.DEV_TOGGLE_CONNECTION -> toggleDev(command)
        CommandCatalog.APPMESSAGE_SEND -> sendAppMessage(command)
        CommandCatalog.APPMESSAGE_SUBSCRIBE -> CommandResult.Failure(ErrorCode.NOT_AUTHORIZED, "missing verified client identity")
        CommandCatalog.NOTIFICATION_SEND -> sendNotification(command)
        CommandCatalog.WATCH_SET_PREF -> setPref(command)
        CommandCatalog.WATCH_SET_QUICK_LAUNCH -> setQuickLaunch(command)
        CommandCatalog.SYSTEM_GET_LOCKER -> getLocker(command)

        else -> CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "unknown command '${command.type}'")
        }
    }

    // --- system.getLocker (read-only locker list for client-side pickers) ---
    @Serializable
    private data class LockerEntryDto(val uuid: String, val title: String, val type: String)

    private suspend fun getLocker(command: CommandEnvelope): CommandResult {
        val types = when (command.args["type"]?.lowercase()) {
            "watchface" -> listOf(AppType.Watchface)
            "watchapp" -> listOf(AppType.Watchapp)
            null, "", "all" -> listOf(AppType.Watchapp, AppType.Watchface)
            else -> return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid locker type")
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
    private suspend fun getInfo(command: CommandEnvelope): CommandResult {
        val device = resolveConnected(command.watch)
            ?: return noWatch(command.watch)
        val runningApp = (device as? ConnectedPebbleDevice)?.runningApp?.value
        val face = runningApp?.let { uuid ->
            libPebble.getLockerApp(uuid).first()?.takeIf { it.properties.type == AppType.Watchface }
        }
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
                runningApp?.let { put("running_app", it.toString()) }
                face?.let {
                    put("watchface", it.properties.title)
                    put("watchface_uuid", it.properties.id.toString())
                }
            },
        )
    }

    // --- system.ping (watch response, measured using a monotonic clock) ---
    private suspend fun ping(command: CommandEnvelope): CommandResult {
        val cookie = command.args["cookie"]?.let {
            it.toUIntOrNull() ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid cookie")
        } ?: pingCookies.getAndIncrement().toUInt()
        val device = resolveConnected(command.watch) as? ConnectedPebbleDevice ?: return noWatch(command.watch)
        val started = timeSource.markNow()
        val response = device.sendPing(cookie)
        if (response != cookie) return CommandResult.Failure(ErrorCode.INTERNAL, "ping response cookie mismatch")
        return CommandResult.Ok(mapOf("serial" to device.serial, "sent" to "true", "rtt_ms" to started.elapsedNow().inWholeMilliseconds.toString()))
    }

    // --- watch.launchApp / watch.setWatchface (selected watch only) ---
    private suspend fun launchApp(command: CommandEnvelope): CommandResult {
        val uuid = command.args["uuid"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing/invalid 'uuid'")
        val device = resolveConnected(command.watch) as? ConnectedPebbleDevice ?: return noWatch(command.watch)
        if (command.type == CommandCatalog.WATCH_SET_WATCHFACE &&
            libPebble.getLockerApp(uuid).first()?.properties?.type != AppType.Watchface) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "uuid is not an installed watchface")
        }
        device.launchApp(uuid)
        return CommandResult.Ok(mapOf("uuid" to uuid.toString(), "serial" to device.serial))
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
        if (!command.args["transport"].isNullOrBlank()) {
            return CommandResult.Failure(ErrorCode.UNSUPPORTED_COMMAND, "explicit developer transport is unsupported; use app configuration")
        }
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
        val dict = try { AppMessageArgs.decode(command.args) } catch (e: IllegalArgumentException) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, e.message ?: "invalid dictionary")
        }
        val txn = device.transactionSequence.next()
        val result = withTimeoutOrNull(4_500) { device.sendAppMessage(AppMessageData(txn, uuid, dict)) }
            ?: return CommandResult.Failure(ErrorCode.TIMEOUT, "AppMessage acknowledgement timed out; delivery is unknown")
        val acked = result is AppMessageResult.ACK
        return CommandResult.Ok(mapOf("uuid" to uuid.toString(), "acked" to acked.toString(), "serial" to device.serial, "transaction_id" to txn.toString()))
    }

    @Serializable
    private data class DesiredSubscription(val uuid: String, val watch: String? = null, val ownership: String = "observe")
    private suspend fun subscribeAppMessage(command: CommandEnvelope, clientIdentity: String): CommandResult {
        if (clientIdentity.isBlank()) return CommandResult.Failure(ErrorCode.NOT_AUTHORIZED, "missing verified client identity")
        if (command.args["mode"] == "replace") {
            if (!command.watch.isNullOrBlank()) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "replace uses per-entry watch selectors")
            val entries = runCatching { json.decodeFromString<List<DesiredSubscription>>(command.args["subscriptions_json"] ?: "") }.getOrNull()
                ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid subscriptions_json")
            if (entries.size > 256) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "too many subscriptions")
            val desired = ArrayList<AutomationAppMessageHook.DesiredSubscription>()
            val deferred = ArrayList<String>()
            for (entry in entries) {
                val uuid = runCatching { Uuid.parse(entry.uuid).toString() }.getOrNull()
                    ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid subscription UUID")
                if (entry.ownership !in setOf("observe", "tasker")) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid ownership")
                val address = if (entry.watch.isNullOrBlank()) null else {
                    val known = resolveKnown(entry.watch)?.identifier?.asString
                    if (known == null) { deferred += entry.watch; continue }
                    known
                }
                desired += AutomationAppMessageHook.DesiredSubscription(uuid, address, entry.ownership == "tasker")
            }
            AutomationAppMessageHook.replaceOwner(clientIdentity, desired)
            return CommandResult.Ok(mapOf("subscriptions" to desired.size.toString(), "deferred_watches" to json.encodeToString(deferred.distinct())))
        }
        if (command.args["mode"] != null) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid subscription mode")
        val uuid = command.args["uuid"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing/invalid uuid")
        val enable = when (command.args["enable"]?.lowercase()) {
            null, "true", "1", "on" -> true
            "false", "0", "off" -> false
            else -> return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid enable")
        }
        val ownership = command.args["ownership"]?.lowercase() ?: "observe"
        if (ownership !in setOf("observe", "tasker")) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "ownership must be observe or tasker")
        }
        val address = if (command.watch.isNullOrBlank()) null else
            resolveKnown(command.watch)?.identifier?.asString ?: return noWatch(command.watch)
        if (enable) AutomationAppMessageHook.subscribe(clientIdentity, uuid.toString(), address, exclusive = ownership == "tasker")
        else AutomationAppMessageHook.unsubscribe(clientIdentity, uuid.toString(), address)
        return CommandResult.Ok(mapOf("uuid" to uuid.toString(), "subscribed" to enable.toString(), "ownership" to ownership))
    }

    // --- notification.send (builds a TimelineNotification; custom actions round-trip as notif.action) ---
    @Serializable
    private data class NotifAction(
        val id: String? = null,
        val label: String = "",
        val type: String = "generic",
    )

    private suspend fun sendNotification(command: CommandEnvelope, clientIdentity: String = ""): CommandResult {
        val a = command.args
        val titleArg = a["title"]?.takeIf { it.isNotBlank() }
        val bodyArg = a["body"]?.takeIf { it.isNotBlank() }
        val subtitleArg = a["subtitle"]?.takeIf { it.isNotBlank() }
        val iconArg = a["icon"]?.let { name -> TimelineIcon.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val vibePattern: List<UInt>? = CommandArgs.vibePattern(a["vibe"])
        val actionDefs: List<NotifAction> = a["actions_json"]?.takeIf { it.isNotBlank() }?.let { raw ->
            try { json.decodeFromString<List<NotifAction>>(raw) } catch (e: IllegalArgumentException) {
                return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid actions_json")
            }
        } ?: emptyList()
        val actionIds = actionDefs.mapIndexed { index, def -> def.id ?: index.toString() }
        if (actionDefs.size > 255 || actionIds.any { it.isBlank() } || actionIds.distinct().size != actionIds.size ||
            actionDefs.any { it.label.isBlank() || it.type.trim().lowercase() !in setOf("generic", "dismiss", "reply", "response", "open", "openwatchapp", "launch") }) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid or duplicate notification actions")
        }

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
                            "action_id" to actionIds[index],
                            "action" to def.label,
                            "pkg" to clientIdentity,
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
        return CommandResult.Ok(mapOf("item_id" to notification.itemId.toString(), "queued" to "true", "delivered" to "false", "delivery_status" to "queued"))
    }

    private fun String.toActionType(): TimelineItem.Action.Type = when (trim().lowercase()) {
        "dismiss" -> TimelineItem.Action.Type.Dismiss
        "reply", "response" -> TimelineItem.Action.Type.Response
        "open", "openwatchapp", "launch" -> TimelineItem.Action.Type.OpenWatchapp
        else -> TimelineItem.Action.Type.Generic
    }

    // --- watch.setPref (generic typed pref via WatchPref.decodeValue) ---
    private suspend fun setPref(command: CommandEnvelope): CommandResult {
        val prefKey = command.args["pref_key"]?.trim()
        if (prefKey.isNullOrEmpty()) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing 'pref_key'")
        val pref = WatchPref.from(prefKey)
            ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "unknown pref '$prefKey'")
        val raw = command.args["pref_value"] ?: return CommandResult.Failure(ErrorCode.INVALID_ARGS, "missing pref_value")
        val encoded = if (pref.type == WatchPrefType.TypeBoolean) CommandArgs.normalizeBool(raw) else raw
        if (pref.type == WatchPrefType.TypeBoolean && encoded !in setOf("0", "1")) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid boolean pref_value")
        }
        if (pref is NumberWatchPref && encoded.toLongOrNull()?.let { it in pref.min..pref.max } != true) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "numeric pref_value out of range")
        }
        return applyPref(pref, encoded)
    }

    /**
     * Star-capture helper: decode the string with the pref's own codec and write it. Prefs are
     * phone-global; with exactly one connected watch, that watch's support is checked before and
     * after the write.
     */
    private suspend fun <T> applyPref(pref: WatchPref<T>, encoded: String): CommandResult {
        val value = try {
            pref.decodeValue(encoded)
        } catch (e: Exception) {
            return CommandResult.Failure(ErrorCode.INVALID_ARGS, "bad value for '${pref.id}': ${e.message}")
        }
        if (pref.encodeValue(value) != encoded) return CommandResult.Failure(ErrorCode.INVALID_ARGS, "invalid value for '${pref.id}'")
        val write = { libPebble.setWatchPref(WatchPreference(pref, value)) }
        val target = resolveConnected(null)
        val status = if (target == null) {
            write()
            PrefSupport.UNKNOWN
        } else {
            if (control.prefSupport(target, pref.id) == PrefSupport.UNSUPPORTED) return WatchControlCommands.unsupportedPref(pref, target)
            control.writePrefAndAwait(target, pref.id, PREF_ACK_TIMEOUT_MS, write)
                .also { if (it == PrefSupport.UNSUPPORTED) return WatchControlCommands.unsupportedPref(pref, target) }
        }
        return CommandResult.Ok(mapOf("pref_id" to pref.id, "value" to pref.encodeValue(value), "watch_status" to status.wire))
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

    private fun resolveConnected(selector: String?) = watches.connected(selector)
    private fun resolveKnown(selector: String?) = watches.known(selector)
    private fun noWatch(selector: String?) = watches.noWatch(selector)

    private companion object {
        const val PREF_ACK_TIMEOUT_MS = 3_000L
    }
}
