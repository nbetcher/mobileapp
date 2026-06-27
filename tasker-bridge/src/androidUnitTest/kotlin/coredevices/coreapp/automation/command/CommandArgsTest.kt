package coredevices.coreapp.automation.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic tests for the Phase-3 command argument mapping (no Android / no watch). Locks in the
 * quick-launch button routing, boolean normalization, and notification vibe pattern.
 */
class CommandArgsTest {

    @Test
    fun quickLaunch_holdVsTap_andCombos() {
        assertEquals("qlUp", CommandArgs.quickLaunchPrefId("up", "long"))
        assertEquals("qlSingleClickUp", CommandArgs.quickLaunchPrefId("up", "short"))
        assertEquals("qlDown", CommandArgs.quickLaunchPrefId("Down", "long"))
        assertEquals("qlSingleClickDown", CommandArgs.quickLaunchPrefId("down", "tap"))
        assertEquals("qlSelect", CommandArgs.quickLaunchPrefId("select", "long"))
        assertEquals("qlBack", CommandArgs.quickLaunchPrefId("back", "long"))
        assertEquals("qlComboBackUp", CommandArgs.quickLaunchPrefId("back+up", "long"))
        assertEquals("qlComboUpDown", CommandArgs.quickLaunchPrefId("up+down", "long"))
        // select/back have no single-click variant -> hold pref regardless of press.
        assertEquals("qlSelect", CommandArgs.quickLaunchPrefId("select", "short"))
        assertNull(CommandArgs.quickLaunchPrefId("middle", "long"))
    }

    @Test
    fun bool_normalization_passesThroughNonBoolEncodings() {
        assertEquals("1", CommandArgs.normalizeBool("true"))
        assertEquals("1", CommandArgs.normalizeBool("ON"))
        assertEquals("1", CommandArgs.normalizeBool("1"))
        assertEquals("0", CommandArgs.normalizeBool("false"))
        assertEquals("0", CommandArgs.normalizeBool("no"))
        // Enum/number prefs encode as raw integers — must pass through untouched.
        assertEquals("7", CommandArgs.normalizeBool("7"))
    }

    @Test
    fun vibePattern_mapsKnownHints_elseNull() {
        assertEquals(listOf(180u), CommandArgs.vibePattern("short"))
        assertEquals(listOf(600u), CommandArgs.vibePattern("long"))
        assertEquals(3, CommandArgs.vibePattern("double")?.size)
        assertNull(CommandArgs.vibePattern("none"))
        assertNull(CommandArgs.vibePattern(null))
        assertNull(CommandArgs.vibePattern(""))
    }

    @Test
    fun vibePattern_parsesCustomCsv_clampsAndDropsJunk() {
        // Plain on/off/on CSV.
        assertEquals(listOf(200u, 100u, 200u), CommandArgs.vibePattern("200,100,200"))
        // Space/semicolon separators and surrounding whitespace are tolerated.
        assertEquals(listOf(300u, 150u), CommandArgs.vibePattern(" 300 ; 150 "))
        // Zero and non-numeric entries are dropped; values clamp into 10..10000.
        assertEquals(listOf(10u, 10000u), CommandArgs.vibePattern("5,abc,0,99999"))
        // No usable numbers -> null (watch default).
        assertNull(CommandArgs.vibePattern("abc,xyz"))
    }
}
