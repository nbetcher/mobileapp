package coredevices.coreapp.automation.trust

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coredevices.coreapp.automation.ClientRecord
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConsentLifecycleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private class Inspector : PackageInspector {
        var cert = byteArrayOf(1, 2)
        override fun packagesForUid(uid: Int) = if (uid == 1) listOf("plugin") else emptyList()
        override fun hasSigningCert(pkg: String, sha256: ByteArray) = cert.contentEquals(sha256)
        override fun signingCertSha256(pkg: String) = cert
        override fun installSource(pkg: String): String? = null
    }
    @Before fun reset() {
        context.getSharedPreferences("automation_trust", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
    }
    @Test fun firstRequestCanBeReviewedWhileMasterRemainsOff() {
        val store = ClientTrustStore(context)
        val inspector = Inspector()
        val result = CallerVerifier(inspector, store).verify(1)
        assertTrue(result is CallerVerifier.Result.Unknown)
        ConsentController(context, store, inspector).onUnknownClient("plugin", "0102", null)
        assertEquals(1, store.pending.value.size)
        assertFalse(store.masterEnabled.value)
        assertEquals(CallerVerifier.Result.NotAuthorized, CallerVerifier(inspector, store).verify(99))
    }
    @Test fun requestAlreadyApprovedDuringHandshakeDoesNotReopenConsent() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        assertTrue(controller.approve("plugin", "Plugin", "0102", setOf("apps"), "normal"))
        controller.onUnknownClient("plugin", "0102", null)
        assertTrue(store.pending.value.isEmpty())
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun ignoredRequestsAreCoalescedAndDoNotRepostDismissedAlerts() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        controller.onUnknownClient("plugin", "0102", null)
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        repeat(100) { controller.onUnknownClient("plugin", "0102", null) }
        assertEquals(1, store.pending.value.size)
        assertEquals(0, manager.activeNotifications.size)
        val restored = ClientTrustStore(context)
        ConsentController(context, restored, Inspector()).onUnknownClient("plugin", "0102", null)
        assertEquals(1, restored.pending.value.size)
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun denialPersistsAndWinsOverRepeatedRequestsEvenWithMasterOff() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        controller.onUnknownClient("plugin", "0102", null)
        controller.deny("plugin")
        val restored = ClientTrustStore(context)
        assertEquals(CallerVerifier.Result.Denied, CallerVerifier(Inspector(), restored).verify(1))
        repeat(20) { ConsentController(context, restored, Inspector()).onUnknownClient("plugin", "0102", null) }
        assertTrue(restored.pending.value.isEmpty())
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun explicitReviewReopensDenialButDoesNotApprove() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        controller.onUnknownClient("plugin", "0102", null)
        controller.deny("plugin")
        controller.reconsider("plugin")
        assertFalse(store.isDenied("plugin", "0102"))
        assertEquals(1, store.pending.value.size)
        assertNull(store.get("plugin"))
        assertFalse(store.masterEnabled.value)
    }
    @Test fun approvalRevalidatesSignerAndDoesNotApproveStalePendingIdentity() {
        val store = ClientTrustStore(context)
        val inspector = Inspector()
        val controller = ConsentController(context, store, inspector)
        controller.onUnknownClient("plugin", "0102", null)
        inspector.cert = byteArrayOf(3, 4)
        assertFalse(controller.approve("plugin", "Plugin", "0102", setOf("apps"), "normal"))
        assertNull(store.get("plugin"))
        assertEquals("0304", store.pending.value.single().certSha256Hex)
        assertTrue(controller.approve("plugin", "Plugin", "0304", setOf("apps"), "normal"))
        assertEquals("0304", store.get("plugin")!!.certSha256)
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun approvalAndGrantEditsPersistAndRequireMasterEnabledToUse() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        controller.onUnknownClient("plugin", "0102", null)
        assertTrue(controller.approve("plugin", "Plugin", "0102", setOf("connectivity", "health", "notifications"), "sensitive"))
        assertEquals(CallerVerifier.Result.NotAuthorized, CallerVerifier(Inspector(), store).verify(1))
        store.setMasterEnabled(true)
        assertTrue(CallerVerifier(Inspector(), store).verify(1) is CallerVerifier.Result.Verified)
        controller.approve("plugin", "Plugin", "0102", setOf("connectivity"), "normal")
        val restored = ClientTrustStore(context)
        assertEquals(setOf("connectivity"), restored.get("plugin")!!.categories)
        assertEquals("normal", restored.get("plugin")!!.tier)
    }
    @Test fun revocationPersistsNegativeDecisionAndInvalidatesPolicy() {
        val store = ClientTrustStore(context)
        store.approve(ClientRecord("plugin", "0102", "Plugin", approvedAtMs = 0))
        var changes = 0
        store.onPolicyChanged = { changes++ }
        store.revoke("plugin")
        assertEquals(1, changes)
        assertTrue(ClientTrustStore(context).isDenied("plugin", "0102"))
        assertNull(store.get("plugin"))
    }
    @Test fun resolvingOneRequestRetainsTheOtherAndLastDecisionClearsAlert() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        controller.onUnknownClient("plugin", "0102", null)
        controller.onUnknownClient("second", "0102", null)
        controller.deny("plugin")
        assertEquals("second", store.pending.value.single().packageName)
        assertEquals(1, manager.activeNotifications.size)
        controller.deny("second")
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun concurrentClientsAreAllRetainedAndDeniedClientCannotReappear() {
        val store = ClientTrustStore(context)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..100).map { i -> pool.submit { store.addPending(PendingClient("plugin$i", "0102", null)) } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(100, store.pending.value.size)
            store.deny("plugin1")
            val retries = (1..100).map { pool.submit { store.addPending(PendingClient("plugin1", "0102", null)) } }
            retries.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(99, ClientTrustStore(context).pending.value.size)
            assertTrue(store.isDenied("plugin1", "0102"))
        } finally { pool.shutdownNow() }
    }
}
