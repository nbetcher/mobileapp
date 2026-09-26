package coredevices.coreapp.automation.command

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.ColorWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.RgbColorWatchPref
import io.rebble.libpebblecommon.database.entity.ScheduleWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.timeline.TimelineColor
import kotlinx.serialization.Serializable

@Serializable
data class PrefOption(val value: String, val label: String)

/** One watch preference as automation clients see it. [value]/[default]/[options] use the `watch.setPref` wire form. */
@Serializable
data class PrefEntry(
    val key: String,
    val label: String,
    val description: String? = null,
    val type: String,
    val value: String,
    val default: String,
    val support: String,
    val options: List<PrefOption>? = null,
    val min: Int? = null,
    val max: Int? = null,
    val unit: String? = null,
)

internal object PrefListing {
    /** Debug settings are hidden in the app's settings screen, so they are hidden here too. */
    fun listable(): List<WatchPref<*>> = WatchPref.enumeratePrefs().filterNot { it.isDebugSetting }

    @Suppress("UNCHECKED_CAST")
    fun encodedValue(stored: WatchPreference<*>): String = (stored.pref as WatchPref<Any?>).encodeValue(stored.valueOrDefault())

    fun entry(pref: WatchPref<*>, stored: WatchPreference<*>?, support: PrefSupport): PrefEntry {
        @Suppress("UNCHECKED_CAST")
        val typed = pref as WatchPref<Any?>
        return PrefEntry(
            key = pref.id,
            label = pref.displayName,
            description = pref.description,
            type = typeName(pref),
            value = stored?.let(::encodedValue) ?: typed.encodeValue(pref.defaultValue),
            default = typed.encodeValue(pref.defaultValue),
            support = support.wire,
            options = options(pref),
            min = (pref as? NumberWatchPref)?.min,
            max = (pref as? NumberWatchPref)?.max,
            unit = (pref as? NumberWatchPref)?.unit?.takeIf { it.isNotBlank() },
        )
    }

    private fun typeName(pref: WatchPref<*>): String = when (pref) {
        is BoolWatchPref -> "boolean"
        is NumberWatchPref -> "number"
        is EnumWatchPref -> "enum"
        is ColorWatchPref -> "color"
        is RgbColorWatchPref -> "rgb_color"
        is QuicklaunchWatchPref -> "quick_launch"
        is ScheduleWatchPref -> "schedule"
    }

    private fun options(pref: WatchPref<*>): List<PrefOption>? = when (pref) {
        is BoolWatchPref -> listOf(PrefOption("1", "On"), PrefOption("0", "Off"))
        is EnumWatchPref -> pref.options.map { PrefOption(pref.encodeValue(it), it.displayName) }
        is ColorWatchPref -> (pref.availableColors ?: TimelineColor.entries).map { PrefOption(pref.encodeValue(it), it.displayName) }
        is RgbColorWatchPref -> pref.presets.map { PrefOption(pref.encodeValue(it.rgb), it.displayName) }
        is NumberWatchPref, is QuicklaunchWatchPref, is ScheduleWatchPref -> null
    }
}
