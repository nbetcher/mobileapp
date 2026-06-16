package coredevices.coreapp.automation.trust

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Testable seam over [PackageManager] so [CallerVerifier] can be unit-tested without Android
 * (HLDD-001 §6, §11).
 */
interface PackageInspector {
    fun packagesForUid(uid: Int): List<String>
    /** True iff [pkg]'s current signing cert SHA-256 equals [sha256]. */
    fun hasSigningCert(pkg: String, sha256: ByteArray): Boolean
    /** SHA-256 of [pkg]'s current signing certificate, or null. */
    fun signingCertSha256(pkg: String): ByteArray?
    fun installSource(pkg: String): String?
}

class AndroidPackageInspector(private val pm: PackageManager) : PackageInspector {

    override fun packagesForUid(uid: Int): List<String> =
        pm.getPackagesForUid(uid)?.toList() ?: emptyList()

    override fun hasSigningCert(pkg: String, sha256: ByteArray): Boolean =
        signingCertSha256(pkg)?.contentEquals(sha256) == true

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    override fun signingCertSha256(pkg: String): ByteArray? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256)
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()?.let(::sha256)
        }
    } catch (e: Exception) {
        null
    }

    override fun installSource(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
