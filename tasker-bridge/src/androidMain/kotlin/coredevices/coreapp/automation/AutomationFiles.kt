package coredevices.coreapp.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlin.uuid.Uuid

class AutomationFileProvider : FileProvider()

/**
 * Files produced for automation clients (screenshots, log dumps). Each file is readable only by the
 * package it was shared with, and is deleted — and its grant revoked — after [RETENTION_MS].
 */
class AutomationFiles(private val context: Context, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val authority get() = "${context.packageName}.automation.files"
    private val root get() = File(context.cacheDir, "automation")

    fun newFile(kind: String, extension: String): File {
        val dir = File(root, kind).apply { mkdirs() }
        prune(dir)
        return File(dir, "${Uuid.random()}.$extension")
    }

    /** @return the content URI, readable by [pkg] only. */
    fun share(file: File, pkg: String): String {
        val uri = uriFor(file)
        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri.toString()
    }

    private fun prune(dir: File) {
        val cutoff = nowMs() - RETENTION_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { file ->
            runCatching { context.revokeUriPermission(uriFor(file), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            file.delete()
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    companion object {
        const val RETENTION_MS = 60 * 60 * 1000L
    }
}
