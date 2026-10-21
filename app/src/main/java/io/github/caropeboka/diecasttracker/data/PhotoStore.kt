package io.github.caropeboka.diecasttracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class PhotoException(message: String) : Exception(message)

/**
 * Photos in app-private storage, normalized like the web app's `PhotoStorage` (EXIF rotation applied,
 * longest edge at most 1600 px, JPEG quality 85). A new photo waits in the temporary folder until its
 * diecast is saved ([promote]); unsaved ones are pruned after a day. Files are referred to by name only.
 */
class PhotoStore(private val context: Context, root: File = context.filesDir) {
    private val dir = File(root, "photos")
    private val tmpDir = File(root, "photos-tmp")
    private val cameraDir = File(context.cacheDir, "camera")

    /** Where a photo is right now (saved or still pending). */
    fun file(name: String): File = File(dir, name).takeIf { it.exists() } ?: File(tmpDir, name)

    /** True for an added-but-unsaved photo that still exists. */
    fun isPending(name: String?): Boolean =
        name != null && NAME.matches(name) && File(tmpDir, name).exists()

    /** Normalizes a picked photo (gallery, files) into a pending photo; returns its name. */
    fun addFromUri(uri: Uri): String =
        normalize(ImageDecoder.createSource(context.contentResolver, uri))

    /** A file the camera app writes the next photo into, plus the URI to hand it. */
    fun newCameraTarget(): Pair<File, Uri> {
        cameraDir.mkdirs()
        val file = File(cameraDir, "${UUID.randomUUID()}.jpg")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Normalizes a photo the camera wrote into [newCameraTarget] and removes the camera's original. */
    fun addFromCamera(file: File): String {
        try {
            return normalize(ImageDecoder.createSource(file))
        } finally {
            file.delete()
        }
    }

    /**
     * A screenshot or a photo of a printed list as JPEG bytes for the AI, sharper than a photo so small text stays
     * legible, and not kept (web: `SCREENSHOT_MAX_EDGE`).
     */
    fun screenshot(uri: Uri): ByteArray = jpeg(decode(ImageDecoder.createSource(context.contentResolver, uri), SCREENSHOT_MAX_EDGE))

    /** [screenshot] for a photo the camera wrote into [newCameraTarget]; the camera's original is removed. */
    fun screenshot(file: File): ByteArray {
        try {
            return jpeg(decode(ImageDecoder.createSource(file), SCREENSHOT_MAX_EDGE))
        } finally {
            file.delete()
        }
    }

    /** Moves a pending photo into permanent storage. */
    fun promote(name: String): String {
        val source = File(tmpDir, name)
        val target = File(dir.apply { mkdirs() }, name)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return name
    }

    /**
     * Stores a photo from a backup as a pending photo. JPEGs (what the web app's uploads become) are kept
     * as they are; other images (the web also holds .webp photos, e.g. taken over from the dex) are
     * normalized to JPEG. Returns null for anything that is not an image.
     */
    fun addFromBackup(bytes: ByteArray): String? {
        if (looksLikeJpeg(bytes)) {
            val name = newName()
            File(tmpDir.apply { mkdirs() }, name).writeBytes(bytes)
            return name
        }

        return try {
            normalize(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        } catch (_: PhotoException) {
            null
        }
    }

    /** Moves a pending photo out of the photo folders, e.g. into a dex's folder. */
    fun moveTo(name: String, target: File) {
        val source = file(name)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    fun delete(name: String?) {
        if (name == null || !NAME.matches(name)) return
        File(dir, name).delete()
        File(tmpDir, name).delete()
    }

    /**
     * Run at start-up with every file name the database refers to. Finishes promotions an interrupted save
     * left behind, and removes unsaved photos, camera leftovers and unreferenced files older than a day.
     */
    fun housekeeping(referenced: Set<String>, now: Long = System.currentTimeMillis()) {
        val cutoff = now - DAY_MILLIS

        tmpDir.listFiles()?.forEach { file ->
            when {
                file.name in referenced -> promote(file.name)
                file.lastModified() < cutoff -> file.delete()
            }
        }
        cameraDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
        dir.listFiles()?.filter { it.name !in referenced && it.lastModified() < cutoff }?.forEach(File::delete)
    }

    private fun normalize(source: ImageDecoder.Source): String {
        val name = newName()
        File(tmpDir.apply { mkdirs() }, name).outputStream().use {
            decode(source, MAX_EDGE).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        return name
    }

    private fun jpeg(image: Bitmap): ByteArray =
        ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    /** EXIF rotation applied, at most [maxEdge] px on the longest side, transparency on white. */
    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap {
        val decoded = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val scale = maxEdge.toFloat() / max(width, height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        max(1, (width * scale).roundToInt()),
                        max(1, (height * scale).roundToInt()),
                    )
                }
            }
        } catch (_: IOException) {
            throw PhotoException("Format foto tidak didukung. Gunakan JPG, PNG, atau WEBP.")
        }

        // JPEG has no transparency: put transparent PNGs on white instead of black.
        return if (decoded.hasAlpha()) {
            createBitmap(decoded.width, decoded.height).also {
                Canvas(it).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, 0f, 0f, null)
                }
            }
        } else {
            decoded
        }
    }

    private fun looksLikeJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun newName() = "${UUID.randomUUID()}.jpg"

    companion object {
        const val MAX_EDGE = 1600
        const val SCREENSHOT_MAX_EDGE = 2400
        private const val JPEG_QUALITY = 85
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private val NAME = Regex("[0-9a-f-]{36}\\.jpg")
    }
}
