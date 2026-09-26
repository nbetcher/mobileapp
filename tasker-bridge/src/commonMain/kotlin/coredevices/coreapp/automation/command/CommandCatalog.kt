package coredevices.coreapp.automation.command

import coredevices.coreapp.automation.ClientRecord

/**
 * Closed allowlist of executable command types and their required command tier (HLDD-002 §6,
 * PLAN §5.5). The [CommandExecutor] consults this map BEFORE dispatching — an unknown [type] is
 * rejected with UNSUPPORTED_COMMAND, and a type whose [tier] exceeds the caller's granted tier is
 * rejected with COMMAND_NOT_AUTHORIZED. No reflection: only types present here can ever run.
 *
 * This map is part of the frozen IPC contract — entries are append-only; never repurpose a type
 * string or downgrade its tier without a protocol bump.
 */
enum class CommandTier(val rank: Int) {
    NONE(-1),
    /** Read/benign actuation: launch app, set watchface, send notification, ping, read info. */
    NORMAL(0),

    /** User-visible side effects on connectivity/notifications: connect, disconnect, mute, prefs. */
    SENSITIVE(1),

    /** Destructive / security-relevant: dev connection, reboot, remote input, firmware install. Requires the
     *  app's explicit "dangerous commands" toggle in addition to the tier grant (PLAN §5.5). */
    DANGEROUS(2),

    /** Exposes sensitive personal data or destroys data irrecoverably: log gathering, factory reset.
     *  Needs the dangerous toggle plus the client's accepted one-time disclaimer. */
    EXTREMELY_DANGEROUS(3);

    companion object {
        const val GRANT_EXTREMELY_DANGEROUS = "extremely_dangerous"

        /** Parse a persisted ClientRecord.tier string ("normal"|"sensitive"|"dangerous"|"extremely_dangerous"). */
        fun fromGrant(grant: String): CommandTier = when (grant.lowercase()) {
            "sensitive" -> SENSITIVE
            "dangerous" -> DANGEROUS
            GRANT_EXTREMELY_DANGEROUS -> EXTREMELY_DANGEROUS
            "normal" -> NORMAL
            else -> NONE
        }

        /** The tier a client may actually use: an extreme grant without an accepted disclaimer is only dangerous. */
        fun forClient(record: ClientRecord): CommandTier = fromGrant(record.tier).let {
            if (it == EXTREMELY_DANGEROUS && record.extremeDisclaimerAcceptedAtMs == null) DANGEROUS else it
        }
    }
}

/**
 * The v1 command allowlist (PLAN §6.4, mapped to HOOKS.md §3). Each value is the minimum tier a
 * caller must have been granted to run it. The accompanying field/result documentation lives in the
 * KDoc on each handler method and in the plugin-side contract.
 */
object CommandCatalog {
    // --- type constants (frozen) ---
    const val WATCH_GET_INFO = "watch.getInfo"
    const val NOTIFICATION_SEND = "notification.send"
    const val WATCH_LAUNCH_APP = "watch.launchApp"
    const val WATCH_SET_WATCHFACE = "watch.setWatchface"
    const val WATCH_SET_PREF = "watch.setPref"
    const val APPMESSAGE_SEND = "appmessage.send"
    const val APPMESSAGE_SUBSCRIBE = "appmessage.subscribe"
    const val SYSTEM_PING = "system.ping"
    const val WATCH_CONNECT = "watch.connect"
    const val WATCH_DISCONNECT = "watch.disconnect"
    const val WATCH_SET_QUICK_LAUNCH = "watch.setQuickLaunch"
    const val DEV_TOGGLE_CONNECTION = "dev.toggleConnection"

    /** Read-only: list the installed locker apps/faces (UUID + title) so clients can offer a picker. */
    const val SYSTEM_GET_LOCKER = "system.getLocker"

    const val WATCH_REBOOT = "watch.reboot"
    const val WATCH_PRESS_BUTTON = "watch.pressButton"
    const val WATCH_SWIPE = "watch.swipe"
    const val WATCH_STOP_APP = "watch.stopApp"
    const val WATCH_SCREENSHOT = "watch.screenshot"
    const val WATCH_SYNC_TIME = "watch.syncTime"
    const val WATCH_CHECK_FIRMWARE = "watch.checkFirmware"
    const val WATCH_INSTALL_FIRMWARE = "watch.installFirmware"
    const val WATCH_LIST_PREFS = "watch.listPrefs"
    const val WATCH_GET_PREF = "watch.getPref"
    const val WATCH_GATHER_LOGS = "watch.gatherLogs"
    const val WATCH_FACTORY_RESET = "watch.factoryReset"

    /** type -> minimum required tier. The set of keys IS the allowlist. */
    val tiers: Map<String, CommandTier> = mapOf(
        WATCH_GET_INFO to CommandTier.NORMAL,
        NOTIFICATION_SEND to CommandTier.NORMAL,
        WATCH_LAUNCH_APP to CommandTier.NORMAL,
        WATCH_SET_WATCHFACE to CommandTier.NORMAL,
        APPMESSAGE_SEND to CommandTier.NORMAL,
        APPMESSAGE_SUBSCRIBE to CommandTier.NORMAL,
        SYSTEM_PING to CommandTier.NORMAL,
        SYSTEM_GET_LOCKER to CommandTier.NORMAL,
        WATCH_SET_PREF to CommandTier.SENSITIVE,
        WATCH_SET_QUICK_LAUNCH to CommandTier.SENSITIVE,
        WATCH_CONNECT to CommandTier.SENSITIVE,
        WATCH_DISCONNECT to CommandTier.SENSITIVE,
        DEV_TOGGLE_CONNECTION to CommandTier.DANGEROUS,
        WATCH_STOP_APP to CommandTier.NORMAL,
        WATCH_SYNC_TIME to CommandTier.NORMAL,
        WATCH_CHECK_FIRMWARE to CommandTier.NORMAL,
        WATCH_LIST_PREFS to CommandTier.NORMAL,
        WATCH_GET_PREF to CommandTier.NORMAL,
        // The screen can show notification text regardless of the notification-content setting.
        WATCH_SCREENSHOT to CommandTier.SENSITIVE,
        WATCH_REBOOT to CommandTier.DANGEROUS,
        WATCH_PRESS_BUTTON to CommandTier.DANGEROUS,
        WATCH_SWIPE to CommandTier.DANGEROUS,
        WATCH_INSTALL_FIRMWARE to CommandTier.DANGEROUS,
        WATCH_GATHER_LOGS to CommandTier.EXTREMELY_DANGEROUS,
        WATCH_FACTORY_RESET to CommandTier.EXTREMELY_DANGEROUS,
    )

    /** Exact implemented commands: advertise each as `command.<type>`. */
    val types: Set<String> get() = tiers.keys

    /** These operate on shared phone-side state and never accept a watch selector. */
    val globalTypes: Set<String> = setOf(
        NOTIFICATION_SEND, WATCH_SET_PREF, WATCH_SET_QUICK_LAUNCH, SYSTEM_GET_LOCKER,
    )

    fun isKnown(type: String): Boolean = tiers.containsKey(type)
    fun tierOf(type: String): CommandTier? = tiers[type]
}
