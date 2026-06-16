package coredevices.coreapp.automation.command

/**
 * Pure, hardware-independent argument mapping shared by the command handlers. Extracted so the
 * Phase-3 decisions (quick-launch button routing, boolean spelling, notification vibe) are unit
 * testable without a [io.rebble.libpebblecommon.connection.LibPebble] or Android on the classpath.
 */
internal object CommandArgs {

    /**
     * Maps a Quick Launch button + press kind to the libpebble3 quick-launch pref id, or null if the
     * button is unrecognized. "Hold" is the default; "short"/"tap"/"single" select the single-click
     * prefs (only up/down have those). Combos accept "back+up" / "up+down" spellings.
     */
    fun quickLaunchPrefId(button: String, press: String): String? {
        val hold = press.trim().lowercase().let { it != "short" && it != "tap" && it != "single" }
        return when (button.trim().lowercase()) {
            "up" -> if (hold) "qlUp" else "qlSingleClickUp"
            "down" -> if (hold) "qlDown" else "qlSingleClickDown"
            "select" -> "qlSelect"
            "back" -> "qlBack"
            "back+up", "backup", "combobackup" -> "qlComboBackUp"
            "up+down", "updown", "comboupdown" -> "qlComboUpDown"
            else -> null
        }
    }

    /** Normalizes common truthy/falsy spellings to the "1"/"0" wire form BoolWatchPref expects. */
    fun normalizeBool(v: String): String = when (v.trim().lowercase()) {
        "1", "true", "on", "yes", "enabled" -> "1"
        "0", "false", "off", "no", "disabled" -> "0"
        else -> v
    }

    /**
     * Maps the notification "vibe" hint to a vibration pattern (ms on/off), or null for the watch's
     * default (covers "none" and blank).
     */
    fun vibePattern(vibe: String?): List<UInt>? = when (vibe?.trim()?.lowercase()) {
        "short" -> listOf(180u)
        "long" -> listOf(600u)
        "double" -> listOf(160u, 120u, 160u)
        else -> null
    }
}
