package coredevices.coreapp.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutomationFilesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun filesLeftByAPreviousProcessStillExpire() = runTest {
        val dir = File(context.cacheDir, "automation/logs").apply { mkdirs() }
        val old = File(dir, "old.txt").apply { writeText("x"); setLastModified(0) }
        val recent = File(dir, "recent.txt").apply { writeText("x"); setLastModified(1_000) }
        val files = AutomationFiles(context, backgroundScope) { AutomationFiles.RETENTION_MS + testScheduler.currentTime }

        files.restoreExpiries()
        runCurrent()
        assertFalse(old.exists())
        assertTrue(recent.exists())

        advanceTimeBy(1_001)
        assertFalse(recent.exists())
    }
}
