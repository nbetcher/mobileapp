package coredevices.coreapp.automation.command

/**
 * Closed allowlist of executable command types and their required command tier (HLDD-002 §6,
 * PLAN §5.5). The [CommandExecutor] consults this map BEFORE dispatching — an unknown [type] is
 * rejected with UNSUPPORTED_COMMAND, and a type whose [tier] exceeds the caller's granted tier is
 * rejected with NOT_AUTHORIZED. No reflection: only types present here can ever run.
 *
 * This map is part of the frozen IPC contract — entries are append-only; never repurpose a type
 * string or downgrade its tier without a protocol bump.
 */
enum class CommandTier(val rank: Int) {
    /** Read/benign actuation: launch app, set watchface, send notification, ping, read info. */
    NORMAL(0),

    /** User-visible side effects on connectivity/notifications: connect, disconnect, mute, prefs. */
    SENSITIVE(1),

    /** Destructive / security-relevant: forget watch, dev connection, firmware sideload. Requires the
     *  app's explicit "dangerous commands" toggle in addition to the tier grant (PLAN §5.5). */
    DANGEROUS(2);

    companion object {
        /** Parse a persisted ClientRecord.tier string ("normal"|"sensitive"|"dangerous"). */
        fun fromGrant(grant: String): CommandTier = when (grant.lowercase()) {
            "sensitive" -> SENSITIVE
            "dangerous" -> DANGEROUS
            else -> NORMAL
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
    const val SYSTEM_PING = "system.ping"
    const val WATCH_CONNECT = "watch.connect"
    const val WATCH_DISCONNECT = "watch.disconnect"
    const val WATCH_SET_QUICK_LAUNCH = "watch.setQuickLaunch"
    const val DEV_TOGGLE_CONNECTION = "dev.toggleConnection"

    /** Read-only: list the installed locker apps/faces (UUID + title) so clients can offer a picker. */
    const val SYSTEM_GET_LOCKER = "system.getLocker"

    /** type -> minimum required tier. The set of keys IS the allowlist. */
    val tiers: Map<String, CommandTier> = mapOf(
        WATCH_GET_INFO to CommandTier.NORMAL,
        NOTIFICATION_SEND to CommandTier.NORMAL,
        WATCH_LAUNCH_APP to CommandTier.NORMAL,
        WATCH_SET_WATCHFACE to CommandTier.NORMAL,
        APPMESSAGE_SEND to CommandTier.NORMAL,
        SYSTEM_PING to CommandTier.NORMAL,
        SYSTEM_GET_LOCKER to CommandTier.NORMAL,
        WATCH_SET_PREF to CommandTier.SENSITIVE,
        WATCH_SET_QUICK_LAUNCH to CommandTier.SENSITIVE,
        WATCH_CONNECT to CommandTier.SENSITIVE,
        WATCH_DISCONNECT to CommandTier.SENSITIVE,
        DEV_TOGGLE_CONNECTION to CommandTier.DANGEROUS,
    )

    fun isKnown(type: String): Boolean = tiers.containsKey(type)
    fun tierOf(type: String): CommandTier? = tiers[type]
}
