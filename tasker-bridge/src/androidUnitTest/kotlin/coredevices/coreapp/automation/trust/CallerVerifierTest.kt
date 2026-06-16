package coredevices.coreapp.automation.trust

import coredevices.coreapp.automation.ClientRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Negative + positive caller-verification tests (Phase 1 acceptance: unauthorized caller rejected). */
class CallerVerifierTest {

    private class FakeInspector(
        val pkgs: Map<Int, List<String>> = emptyMap(),
        val certs: Map<String, ByteArray> = emptyMap(),
        val sources: Map<String, String?> = emptyMap(),
    ) : PackageInspector {
        override fun packagesForUid(uid: Int) = pkgs[uid] ?: emptyList()
        override fun hasSigningCert(pkg: String, sha256: ByteArray) = certs[pkg]?.contentEquals(sha256) == true
        override fun signingCertSha256(pkg: String) = certs[pkg]
        override fun installSource(pkg: String) = sources[pkg]
    }

    private class FakeTrust(master: Boolean, val records: Map<String, ClientRecord> = emptyMap()) : TrustLookup {
        override val masterEnabled: StateFlow<Boolean> = MutableStateFlow(master)
        override fun get(pkg: String) = records[pkg]
    }

    private val certA = byteArrayOf(1, 2, 3, 4)
    private val certAHex = "01020304"

    @Test
    fun masterDisabled_rejectsEverything() {
        val v = CallerVerifier(FakeInspector(pkgs = mapOf(1000 to listOf("com.x"))), FakeTrust(master = false))
        assertEquals(CallerVerifier.Result.NotAuthorized, v.verify(1000))
    }

    @Test
    fun unapprovedSinglePackage_isUnknownForConsent() {
        val v = CallerVerifier(
            FakeInspector(
                pkgs = mapOf(1000 to listOf("com.x")),
                certs = mapOf("com.x" to certA),
                sources = mapOf("com.x" to "com.android.vending"),
            ),
            FakeTrust(master = true),
        )
        val r = v.verify(1000)
        assertTrue(r is CallerVerifier.Result.Unknown)
        r as CallerVerifier.Result.Unknown
        assertEquals("com.x", r.packageName)
        assertEquals(certAHex, r.certSha256Hex)
        assertEquals("com.android.vending", r.installSource)
    }

    @Test
    fun approvedMatchingCert_isVerified() {
        val rec = ClientRecord("com.x", certAHex, "X", approvedAtMs = 0)
        val v = CallerVerifier(
            FakeInspector(pkgs = mapOf(1000 to listOf("com.x")), certs = mapOf("com.x" to certA)),
            FakeTrust(master = true, records = mapOf("com.x" to rec)),
        )
        val r = v.verify(1000)
        assertTrue(r is CallerVerifier.Result.Verified)
        assertEquals(rec, (r as CallerVerifier.Result.Verified).record)
    }

    @Test
    fun approvedButCertChanged_isCertMismatch() {
        val rec = ClientRecord("com.x", "deadbeef", "X", approvedAtMs = 0) // pinned != actual
        val v = CallerVerifier(
            FakeInspector(pkgs = mapOf(1000 to listOf("com.x")), certs = mapOf("com.x" to certA)),
            FakeTrust(master = true, records = mapOf("com.x" to rec)),
        )
        assertEquals(CallerVerifier.Result.CertMismatch, v.verify(1000))
    }

    @Test
    fun sharedUidWithNoApprovedMember_isNotAuthorized() {
        val v = CallerVerifier(
            FakeInspector(
                pkgs = mapOf(1000 to listOf("com.x", "com.y")),
                certs = mapOf("com.x" to certA, "com.y" to certA),
            ),
            FakeTrust(master = true),
        )
        assertEquals(CallerVerifier.Result.NotAuthorized, v.verify(1000))
    }
}
