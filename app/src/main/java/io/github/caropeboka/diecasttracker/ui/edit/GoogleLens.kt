package io.github.caropeboka.diecasttracker.ui.edit

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.caropeboka.diecasttracker.data.backup.BackupExporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands a photo to Google Lens, which looks for it on the web and often shows shops with their prices: the Google app
 * takes a shared picture as a Lens search. Without the Google app the share sheet opens, so another app can take it.
 */
internal object GoogleLens {
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Copies [photo] under the shared cache folder (cleared with the exports) and sends it; false when nothing took it. */
    suspend fun search(context: Context, photo: File, chooserTitle: String): Boolean {
        val copy = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, BackupExporter.SHARE_DIR).apply { mkdirs() }
                photo.copyTo(File(dir, "lens.${photo.extension.ifEmpty { "jpg" }}"), overwrite = true)
            }.getOrNull()
        } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (copy.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Lens itself first: the Google app also takes pictures to save them, and its package alone would ask which.
        val tries = listOf(Intent(send).setClassName(GOOGLE_APP, LENS_SHARE), Intent(send).setPackage(GOOGLE_APP))
        for (intent in tries) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return runCatching { context.startActivity(Intent.createChooser(send, chooserTitle)) }.isSuccess
    }

    /** The Google app's "Search image" share target (seen on the phone's Google app, 2026-11). */
    private const val LENS_SHARE = "com.google.android.apps.search.lens.LensShareEntryPointActivity"
}
