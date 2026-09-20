package coredevices.coreapp.automation.trust

import coredevices.coreapp.automation.ClientRecord
import kotlinx.coroutines.flow.StateFlow

/** Minimal read view of the trust store, so [CallerVerifier] is unit-testable (HLDD-001 Â§11). */
interface TrustLookup {
    val masterEnabled: StateFlow<Boolean>
    fun get(pkg: String): ClientRecord?
    fun isDenied(pkg: String, cert: String): Boolean = false
}

/**
 * Authenticates a Binder caller against the user-approved allowlist (HLDD-001 Â§6, ADR-003).
 * No custom permissions: every transaction resolves uid â†’ package â†’ signing-cert digest and
 * checks it against the pinned [ClientRecord]. Pure logic over [PackageInspector] + [TrustLookup]
 * so it is unit-testable.
 */
class CallerVerifier(
    private val inspector: PackageInspector,
    private val trustStore: TrustLookup,
) {
    sealed interface Result {
        data class Verified(val record: ClientRecord) : Result
        /** Single, unambiguous, not-yet-approved package â€” eligible for a consent prompt. */
        data class Unknown(val packageName: String, val certSha256Hex: String, val installSource: String?) : Result
        /** Known package whose current signing cert no longer matches the pinned one. */
        data object CertMismatch : Result
        data object NotAuthorized : Result
        data object Denied : Result
    }

    fun verify(uid: Int): Result {
        val packages = inspector.packagesForUid(uid)

        for (pkg in packages) {
            val record = trustStore.get(pkg) ?: continue
            val pinned = record.certSha256.hexToBytesOrNull() ?: return Result.CertMismatch
            if (!inspector.hasSigningCert(pkg, pinned)) return Result.CertMismatch
            if (trustStore.isDenied(pkg, record.certSha256)) return Result.Denied
            return if (trustStore.masterEnabled.value) Result.Verified(record) else Result.NotAuthorized
        }

        // Not yet approved: only offer consent for an unambiguous single-package uid (a shared uid
        // with no approved member is rejected outright).
        val pkg = packages.singleOrNull() ?: return Result.NotAuthorized
        val certHex = inspector.signingCertSha256(pkg)?.toHex() ?: return Result.NotAuthorized
        if (trustStore.isDenied(pkg, certHex)) return Result.Denied
        return Result.Unknown(pkg, certHex, inspector.installSource(pkg))
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return try {
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (e: NumberFormatException) {
        null
    }
}
