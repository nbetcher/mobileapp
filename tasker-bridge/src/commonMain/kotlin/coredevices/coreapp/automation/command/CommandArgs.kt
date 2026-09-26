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
        if (press.trim().lowercase() !in setOf("long", "hold", "short", "tap", "single")) return null
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
     * Maps the notification "vibe" to a vibration pattern (ms on/off), or null for the watch's default
     * (covers "none" and blank).
     *
     * Accepts the named hints ("short"/"long"/"double") AND a custom CSV of on/off durations in ms
     * ("on,off,on,..." starting with an on/buzz), e.g. "200,100,200". Separators may be comma, space, or
     * semicolon; non-numeric/zero entries are dropped and each value is clamped to a sane 10–10000 ms.
     */
    fun vibePattern(vibe: String?): List<UInt>? {
        val v = vibe?.trim() ?: return null
        when (v.lowercase()) {
            "short" -> return listOf(180u)
            "long" -> return listOf(600u)
            "double" -> return listOf(160u, 120u, 160u)
            "none", "" -> return null
        }
        val nums = v.split(',', ' ', ';', '\t')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
            .filter { it > 0 }
            .map { it.coerceIn(10L, 10_000L).toUInt() }
        return nums.ifEmpty { null }
    }

    /** PebbleOS ButtonId: back=0, up=1, select=2, down=3. */
    fun buttonId(name: String?): Int? = when (name?.trim()?.lowercase()) {
        "back" -> 0
        "up" -> 1
        "select", "middle" -> 2
        "down" -> 3
        else -> null
    }

    /** PebbleOS RemoteInputSwipeDirection: up=0, down=1, left=2, right=3. */
    fun swipeDirection(name: String?): Int? = when (name?.trim()?.lowercase()) {
        "up" -> 0
        "down" -> 1
        "left" -> 2
        "right" -> 3
        else -> null
    }

    /** [raw] as an int in [range], [default] when absent/blank, or null when present but invalid. */
    fun boundedInt(raw: String?, range: IntRange, default: Int): Int? =
        if (raw.isNullOrBlank()) default else raw.trim().toIntOrNull()?.takeIf { it in range }
}
