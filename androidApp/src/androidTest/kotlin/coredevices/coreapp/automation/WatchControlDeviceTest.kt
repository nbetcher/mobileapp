package coredevices.coreapp.automation

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.automation.command.CommandCatalog
import coredevices.coreapp.automation.command.CommandHandler
import coredevices.coreapp.automation.command.CommandResult
import io.rebble.libpebblecommon.SystemAppIDs.QUIET_TIME_TOGGLE_UUID
import io.rebble.libpebblecommon.automation.RemoteInputService
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * On-watch checks for the automation watch-control commands. Needs a paired watch connected to the
 * phone and runs inside the installed Pebble app, driving the bridge's real command handler. Start it
 * with `tools/watch-device-tests/run.ps1` (or `run.sh`), which installs in place and keeps pairings.
 *
 * A skipped test is "deferred": the watch cannot run it (older firmware, no touch screen) or the
 * outcome cannot be observed automatically, and it needs a manual check. Screen changes are detected
 * by comparing watch screenshots.
 *
 * Instrumentation arguments: `watch` (serial or address, when several are connected), `app_uuid`
 * (watchapp for the launch/close test), `reboot=true` (also reboot the watch).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WatchControlDeviceTest {
    private val koin get() = GlobalContext.get()
    private val libPebble: LibPebble get() = koin.get()
    private val handler: CommandHandler get() = koin.get()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var watch: ConnectedPebbleDevice

    @Before
    fun connectedWatch() = runBlocking {
        val selector = args.getString("watch")
        watch = withTimeoutOrNull(90.seconds) {
            libPebble.watches.map { list ->
                val connected = list.filterIsInstance<ConnectedPebbleDevice>()
                if (selector == null) connected.singleOrNull()
                else connected.firstOrNull { it.serial == selector || it.identifier.asString == selector }
            }.filterNotNull().first()
        } ?: fail("no single connected watch within 90 s; connect one, or pass -e watch <serial>")
    }

    @Test
    fun t01_timeSyncIsVerifiedByReadingTheWatchClockBack() = device {
        val result = ok(run(CommandCatalog.WATCH_SYNC_TIME))
        assertEquals("true", result["verified"], "watch clock off after sync: $result")
    }

    @Test
    fun t02_screenshotReturnsTheWatchScreen() = device {
        val image = assertNotNull(current().takeScreenshot(), "no screenshot")
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun t03_selectOpensTheLauncherAndBackReturns() = device {
        requireRemoteInput()
        goToWatchface()
        val face = screen()
        ok(press("select"))
        delay(UI_SETTLE)
        val launcher = screen()
        assertTrue(changed(face, launcher) > SCREEN_CHANGED, "select press did not change the screen")
        ok(press("back"))
        delay(UI_SETTLE)
        assertTrue(changed(launcher, screen()) > SCREEN_CHANGED, "back press did not leave the launcher")
    }

    @Test
    fun t04_overlappingSequencesAreRefusedAsBusy() = device {
        requireRemoteInput()
        goToWatchface()
        ok(press("back", presses = 3, gapMs = 400))
        val second = run(CommandCatalog.WATCH_PRESS_BUTTON, "button" to "back")
        assertEquals(ErrorCode.WATCH_BUSY, assertIs<CommandResult.Failure>(second, "second sequence ran: $second").code)
        delay(2.seconds)
    }

    @Test
    fun t05_heldBackTogglesQuietTimeAndTheWatchSyncsItBack() = device {
        requireRemoteInput()
        val prefs = libPebble.watchPrefs.first()
        val ql = prefs.firstOrNull { it.pref == QuicklaunchWatchPref.QlBack }?.valueOrDefault() as? QuickLaunchSetting
        assumeTrue("deferred: hold Back is not set to toggle Quiet Time", ql?.enabled == true && ql.uuid == QUIET_TIME_TOGGLE_UUID)
        val before = quietTime()
        goToWatchface()
        ok(press("back", holdMs = LONG_PRESS_MS))
        val toggled = withTimeoutOrNull(20.seconds) { libPebble.watchPrefs.first { quietTimeIn(it) != before } }
        // Restore before asserting, so a failure does not leave Quiet Time flipped.
        if (toggled != null) {
            goToWatchface()
            ok(press("back", holdMs = LONG_PRESS_MS))
            withTimeoutOrNull(20.seconds) { libPebble.watchPrefs.first { quietTimeIn(it) == before } }
        }
        assertNotNull(toggled, "holding Back did not change Quiet Time, or the change was not synced to the phone")
        assertEquals(before, quietTime(), "Quiet Time was not restored; check the watch")
    }

    @Test
    fun t06_swipeChangesTheScreenOnTouchWatches() = device {
        requireRemoteInput()
        for (direction in listOf("left", "right", "up", "down")) {
            goToWatchface()
            val before = screen()
            val result = run(CommandCatalog.WATCH_SWIPE, "direction" to direction)
            if (result is CommandResult.Failure && result.code == ErrorCode.INVALID_ARGS) {
                assumeTrue("deferred: the watch refused the swipe (no touch screen, or touch is off)", false)
            }
            ok(result)
            delay(UI_SETTLE)
            if (changed(before, screen()) > SCREEN_CHANGED) {
                goToWatchface()
                return@device
            }
        }
        goToWatchface()
        assumeTrue("deferred: swipes were accepted but no screen change was seen; check touch by hand", false)
    }

    @Test
    fun t07_launchedAppIsClosedByStopApp() = device {
        val candidates = args.getString("app_uuid")?.let { listOf(Uuid.parse(it)) } ?: launchableApps()
        assumeTrue("deferred: no watchapp to launch; pass -e app_uuid <uuid>", candidates.isNotEmpty())
        val launched = candidates.take(3).firstOrNull { uuid ->
            ok(run(CommandCatalog.WATCH_LAUNCH_APP, "uuid" to uuid.toString()))
            withTimeoutOrNull(10.seconds) { watch.runningApp.first { it == uuid } } != null
        }
        assumeTrue("deferred: none of ${candidates.take(3)} reported running after launch", launched != null)
        assertEquals(launched.toString(), ok(run(CommandCatalog.WATCH_STOP_APP))["uuid"])
        val closed = withTimeoutOrNull(10.seconds) { watch.runningApp.first { it != launched } }
        assertNotNull(closed, "app $launched still running after watch.stopApp")
    }

    @Test
    fun t08_prefSupportIsLearnedFromTheWatch() = device {
        val learned = withTimeoutOrNull(60.seconds) { watch.acceptedWatchPrefs.first { it.isNotEmpty() } }
        assertNotNull(learned, "no supported preference reported within 60 s; did the full settings sync run?")
        val prefs = JSONArray(ok(run(CommandCatalog.WATCH_LIST_PREFS))["prefs"])
        val supported = (0 until prefs.length()).count { prefs.getJSONObject(it).getString("support") == "supported" }
        assertTrue(supported > 0, "watch.listPrefs reports no supported preference")
        val sample = learned.firstOrNull { WatchPref.from(it)?.isDebugSetting == false }
            ?: fail("the watch reported only keys the app does not list: $learned")
        assertEquals("supported", ok(run(CommandCatalog.WATCH_GET_PREF, "pref_key" to sample))["support"], "watch.getPref disagrees for $sample")
    }

    @Test
    fun t09_firmwareCheckRecordsWhenItRan() = device {
        val status = ok(run(CommandCatalog.WATCH_CHECK_FIRMWARE, "force" to "true"))["status"]
        assumeTrue("deferred: firmware check was $status (network or update server)", status == "available" || status == "none")
        assertNotNull(current().firmwareUpdateAvailable.checkedAt, "check succeeded but no check time was recorded")
    }

    @Test
    fun t10_concurrentLogDumpsUseSeparateFiles() = device(3.minutes) {
        val first = async { current().gatherLogs() }
        val second = async { current().gatherLogs() }
        val a = assertNotNull(first.await(), "first dump failed").toString()
        val b = assertNotNull(second.await(), "second dump failed").toString()
        assertNotEquals(a, b, "both dumps wrote the same file")
        for (path in listOf(a, b)) assertTrue(File(path).length() > 0, "empty dump $path")
    }

    @Test
    fun t11_rebootedWatchReconnects() = device(4.minutes) {
        assumeTrue("deferred: pass -e reboot true to include the reboot test", args.getString("reboot") == "true")
        ok(run(CommandCatalog.WATCH_REBOOT))
        val gone = withTimeoutOrNull(60.seconds) {
            libPebble.watches.first { list -> list.none { it is ConnectedPebbleDevice && it.identifier == watch.identifier } }
        }
        assertNotNull(gone, "watch did not disconnect after watch.reboot")
        val back = withTimeoutOrNull(3.minutes) {
            libPebble.watches.first { list -> list.any { it is ConnectedPebbleDevice && it.identifier == watch.identifier } }
        }
        assertNotNull(back, "watch did not reconnect within 3 minutes of rebooting")
    }

    private fun device(timeout: Duration = 90.seconds, body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(timeout) { body() } }

    private fun current(): ConnectedPebbleDevice =
        libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull { it.identifier == watch.identifier }
            ?: fail("watch disconnected during the test")

    private fun requireRemoteInput() {
        val fw = watch.watchInfo.runningFwVersion
        assumeTrue("deferred: remote input needs PebbleOS 4.35.0+, watch runs ${fw.stringVersion}", RemoteInputService.supports(fw))
    }

    private suspend fun run(type: String, vararg args: Pair<String, String>): CommandResult =
        handler.handle(CommandEnvelope(type = type, watch = watch.serial, args = args.toMap()), CLIENT)

    private fun ok(result: CommandResult): Map<String, String> =
        assertIs<CommandResult.Ok>(result, "command failed: $result").data

    private suspend fun press(button: String, presses: Int = 1, holdMs: Int = 50, gapMs: Int = 100) =
        run(CommandCatalog.WATCH_PRESS_BUTTON, "button" to button, "presses" to "$presses", "hold_ms" to "$holdMs", "gap_ms" to "$gapMs")

    /** Short Back presses; on the watchface they do nothing, so this lands there from any menu depth. */
    private suspend fun goToWatchface() {
        ok(press("back", presses = 4, gapMs = 250))
        delay(2.seconds)
    }

    private suspend fun screen(): IntArray {
        val bitmap = assertNotNull(current().takeScreenshot(), "no screenshot").asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    private fun changed(a: IntArray, b: IntArray): Double =
        if (a.size != b.size) 1.0 else a.indices.count { a[it] != b[it] }.toDouble() / a.size

    private suspend fun quietTime(): Boolean = quietTimeIn(libPebble.watchPrefs.first())

    private fun quietTimeIn(prefs: List<WatchPreference<*>>): Boolean =
        prefs.firstOrNull { it.pref == BoolWatchPref.QuietTimeManuallyEnabled }?.valueOrDefault() as? Boolean ?: false

    private suspend fun launchableApps(): List<Uuid> =
        libPebble.getLocker(AppType.Watchapp, null, 100).first()
            .sortedBy { if (it is LockerWrapper.NormalApp) 0 else 1 }
            .map { it.properties.id }

    private companion object {
        const val CLIENT = "watch-device-test"
        const val LONG_PRESS_MS = 1_500
        /** Fraction of pixels that must differ; well above a clock digit ticking over. */
        const val SCREEN_CHANGED = 0.2
        const val UI_SETTLE = 1_500L
    }
}
