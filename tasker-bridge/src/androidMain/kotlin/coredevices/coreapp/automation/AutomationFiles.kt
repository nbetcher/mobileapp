package coredevices.coreapp.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.uuid.Uuid

class AutomationFileProvider : FileProvider()

/**
 * Files produced for automation clients (screenshots, log dumps). Each file is readable only by the
 * package it was shared with, and is deleted — and its grant revoked — after [RETENTION_MS].
 */
class AutomationFiles(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val authority get() = "${context.packageName}.automation.files"
    private val root get() = File(context.cacheDir, "automation")

    fun newFile(kind: String, extension: String): File {
        root.listFiles()?.forEach(::prune)
        val dir = File(root, kind).apply { mkdirs() }
        return File(dir, "${Uuid.random()}.$extension")
    }

    /** @return the content URI, readable by [pkg] only. */
    fun share(file: File, pkg: String): String {
        val uri = uriFor(file)
        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scope.launch {
            delay(RETENTION_MS)
            expire(file)
        }
        return uri.toString()
    }

    /** Catches files whose scheduled expiry was lost with a previous process. */
    private fun prune(dir: File) {
        val cutoff = nowMs() - RETENTION_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(::expire)
    }

    private fun expire(file: File) {
        runCatching { context.revokeUriPermission(uriFor(file), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        file.delete()
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    companion object {
        const val RETENTION_MS = 60 * 60 * 1000L
    }
}
