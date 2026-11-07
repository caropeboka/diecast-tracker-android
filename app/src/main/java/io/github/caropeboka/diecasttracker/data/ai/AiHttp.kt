package io.github.caropeboka.diecasttracker.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs the call without holding a thread, cancels it when the request is abandoned, and [read]s the response. */
internal suspend fun <T> Call.fetch(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use(read)
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(value)
            }
        },
    )
}

/** A JSON request, written in pieces so [onSent] can follow the upload (with photos it is a megabyte or more). */
internal class ProgressBody(private val bytes: ByteArray, private val onSent: (Long) -> Unit) : RequestBody() {
    override fun contentType() = JSON_TYPE

    override fun contentLength() = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(UPLOAD_CHUNK, bytes.size - offset)
            sink.write(bytes, offset, count)
            sink.flush()
            offset += count
            onSent(offset.toLong())
        }
    }

    private companion object {
        const val UPLOAD_CHUNK = 32 * 1024
        val JSON_TYPE = "application/json".toMediaType()
    }
}

internal fun OkHttpClient.withTimeout(seconds: Long) = newBuilder()
    .callTimeout(seconds, TimeUnit.SECONDS)
    .readTimeout(seconds, TimeUnit.SECONDS)
    .build()

/**
 * [jpeg] re-encoded until it is at most [maxBytes]: a little lower quality first, then a fifth smaller each time,
 * never under 640 px on its long side (cards get hard to read). As it was when it can't be decoded.
 */
internal fun shrinkJpeg(jpeg: ByteArray, maxBytes: Int): ByteArray {
    if (jpeg.size <= maxBytes) return jpeg
    var image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
    fun encode() = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    var out = encode()
    while (out.size > maxBytes && maxOf(image.width, image.height) * 4 / 5 >= 640) {
        image = Bitmap.createScaledBitmap(image, image.width * 4 / 5, image.height * 4 / 5, true)
        out = encode()
    }
    return out
}
