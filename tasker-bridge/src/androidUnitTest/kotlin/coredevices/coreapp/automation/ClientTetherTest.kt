package coredevices.coreapp.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import coredevices.coreapp.automation.trust.*
import io.mockk.every
import io.mockk.mockk
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClientTetherTest {
    private class BindingContext(context: Context) : ContextWrapper(context) {
        val connections = mutableListOf<ServiceConnection>()
        val released = mutableListOf<ServiceConnection>()
        var accept = true
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connections += conn
            return accept
        }
        override fun unbindService(conn: ServiceConnection) { released += conn }
    }
    private class Inspector : PackageInspector {
        var matches = true
        override fun packagesForUid(uid: Int) = listOf("plugin")
        override fun hasSigningCert(pkg: String, sha256: ByteArray) = matches
        override fun signingCertSha256(pkg: String) = byteArrayOf(1, 2)
        override fun installSource(pkg: String): String? = null
    }
    private lateinit var context: BindingContext
    private lateinit var trust: ClientTrustStore
    private lateinit var inspector: Inspector
    private lateinit var watches: MutableStateFlow<List<PebbleDevice>>
    private lateinit var tether: ClientTether
    @Before fun setup() {
        context = BindingContext(ApplicationProvider.getApplicationContext())
        context.getSharedPreferences("automation_trust", Context.MODE_PRIVATE).edit().clear().commit()
        trust = ClientTrustStore(context)
        trust.approve(ClientRecord("plugin", "0102", "Plugin", approvedAtMs = 0))
        trust.setMasterEnabled(true)
        inspector = Inspector()
        watches = MutableStateFlow(listOf(mockk<ConnectedPebbleDevice>(relaxed = true)))
        val lib = mockk<LibPebble>()
        every { lib.watches } returns watches
        tether = ClientTether(context, lib, trust, inspector)
    }
    @Test fun successfulConnectionCancelsInitialDeadline() = runTest {
        tether.start(backgroundScope); runCurrent()
        assertEquals(1, context.connections.size)
        context.connections.single().onServiceConnected(null, Binder())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, context.connections.size)
        assertTrue(context.released.isEmpty())
        tether.stop()
    }
    @Test fun packageReplacementReleasesDeadBindingAndCreatesFreshConnection() = runTest {
        tether.start(backgroundScope); runCurrent()
        val first = context.connections.single()
        first.onServiceConnected(null, Binder())
        first.onBindingDied(null)
        assertEquals(listOf(first), context.released)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, context.connections.size)
        context.connections.last().onServiceConnected(null, Binder())
        first.onBindingDied(null)
        assertEquals(1, context.released.size)
        tether.stop()
    }
    @Test fun nullBindingIsReleasedAndRetryStopsAfterRevocation() = runTest {
        tether.start(backgroundScope); runCurrent()
        val first = context.connections.single()
        first.onNullBinding(null)
        trust.revoke("plugin"); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf(first), context.released)
        assertEquals(1, context.connections.size)
        tether.stop()
    }
    @Test fun ordinaryDisconnectionLetsAndroidReconnectSameBinding() = runTest {
        tether.start(backgroundScope); runCurrent()
        val first = context.connections.single()
        first.onServiceConnected(null, Binder())
        first.onServiceDisconnected(null)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, context.connections.size)
        assertTrue(context.released.isEmpty())
        first.onServiceConnected(null, Binder())
        tether.stop()
    }
    @Test fun acceptedBindWithoutCallbackHasBoundedRecovery() = runTest {
        tether.start(backgroundScope); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, context.connections.size)
        assertEquals(3, context.released.size)
        tether.stop()
    }
    @Test fun missingKeepAliveServiceCannotCreateUnboundedRetryLoop() = runTest {
        context.accept = false
        tether.start(backgroundScope); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, context.connections.size)
        tether.stop()
    }
    @Test fun signerChangeBeforeRetryPreventsBindingReplacementApp() = runTest {
        tether.start(backgroundScope); runCurrent()
        context.connections.single().onBindingDied(null)
        inspector.matches = false
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, context.connections.size)
        tether.stop()
    }
    @Test fun stopCancelsRetryAndIgnoresLateCallbacks() = runTest {
        tether.start(backgroundScope); runCurrent()
        val first = context.connections.single()
        first.onNullBinding(null)
        tether.stop()
        first.onServiceConnected(null, Binder())
        first.onBindingDied(null)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, context.connections.size)
        assertEquals(1, context.released.size)
    }
}
