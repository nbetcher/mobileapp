package coredevices.coreapp.automation.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coredevices.coreapp.automation.command.CommandTier
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ExtremeTierConsentTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Inspector : PackageInspector {
        var cert = byteArrayOf(1, 2)
        override fun packagesForUid(uid: Int) = listOf("plugin")
        override fun hasSigningCert(pkg: String, sha256: ByteArray) = cert.contentEquals(sha256)
        override fun signingCertSha256(pkg: String) = cert
        override fun installSource(pkg: String): String? = null
    }

    @Before fun reset() {
        context.getSharedPreferences("automation_trust", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun extremeTierCannotBeGrantedWithoutTheDisclaimer() {
        val store = ClientTrustStore(context)
        val controller = ConsentController(context, store, Inspector())
        assertThrows(IllegalArgumentException::class.java) {
            controller.approve("plugin", "Plugin", "0102", setOf("system"), CommandTier.GRANT_EXTREMELY_DANGEROUS)
        }
        assertNull(store.get("plugin"))
    }

    @Test fun acceptanceIsAskedOncePerClientIdentity() {
        val store = ClientTrustStore(context)
        val inspector = Inspector()
        val controller = ConsentController(context, store, inspector)
        assertTrue(controller.approve("plugin", "Plugin", "0102", setOf("system"), CommandTier.GRANT_EXTREMELY_DANGEROUS, true))
        val accepted = store.get("plugin")!!.extremeDisclaimerAcceptedAtMs
        assertNotNull(accepted)
        assertEquals(CommandTier.EXTREMELY_DANGEROUS, CommandTier.forClient(store.get("plugin")!!))

        // Lowering and raising the tier again keeps the original acceptance.
        assertTrue(controller.approve("plugin", "Plugin", "0102", setOf("system"), "normal"))
        assertTrue(controller.approve("plugin", "Plugin", "0102", setOf("system"), CommandTier.GRANT_EXTREMELY_DANGEROUS))
        assertEquals(accepted, store.get("plugin")!!.extremeDisclaimerAcceptedAtMs)
        assertEquals(accepted, ClientTrustStore(context).get("plugin")!!.extremeDisclaimerAcceptedAtMs)

        // A revoked client, or a new signing identity, has to accept again.
        controller.revoke("plugin")
        controller.reconsider("plugin")
        assertThrows(IllegalArgumentException::class.java) {
            controller.approve("plugin", "Plugin", "0102", setOf("system"), CommandTier.GRANT_EXTREMELY_DANGEROUS)
        }
    }
}
