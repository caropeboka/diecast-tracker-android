package io.github.caropeboka.diecasttracker.data.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.Subject
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.Texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * ML Kit's subject segmentation, run by Google Play services on the phone: the photo never leaves it. The model is
 * fetched once, on first use (or ahead of it, through the manifest's `com.google.mlkit.vision.DEPENDENCIES`).
 */
class MlKitCutter(context: Context) : SubjectCutter {
    private val texts = Texts(context)
    private val modules = ModuleInstall.getClient(context)
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build())
                .build(),
        )
    }

    override suspend fun cut(image: Bitmap, onStep: (StudioStep) -> Unit): Bitmap? {
        install(onStep)
        onStep(StudioStep.Cutting)
        val subjects = segmenter.process(InputImage.fromBitmap(image, 0)).await().subjects.filter { it.bitmap != null }
        return pick(subjects, image.width, image.height)
    }

    private suspend fun install(onStep: (StudioStep) -> Unit) {
        val available = try {
            modules.areModulesAvailable(segmenter).await().areModulesAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (available) return

        onStep(StudioStep.Downloading(null))
        val done = CompletableDeferred<Boolean>()
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> done.complete(true)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> done.complete(false)
                else -> update.progressInfo?.takeIf { it.totalBytesToDownload > 0 }?.let {
                    onStep(StudioStep.Downloading(it.bytesDownloaded.toFloat() / it.totalBytesToDownload))
                }
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
        val installed = try {
            modules.installModules(request).await().areModulesAlreadyInstalled() ||
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { done.await() } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            modules.unregisterListener(listener)
        }
        if (!installed) throw StudioException(texts(R.string.studio_model_missing))
    }

    /** The biggest subject, with whatever lies within it (the car inside a blister may come as a subject of its own). */
    private fun pick(subjects: List<Subject>, width: Int, height: Int): Bitmap? {
        val main = subjects.maxByOrNull { it.width.toLong() * it.height } ?: return null
        return createBitmap(width, height).also { out ->
            val canvas = Canvas(out)
            subjects.filter { it === main || inside(it, main) >= 0.8f }.forEach {
                canvas.drawBitmap(it.bitmap!!, it.startX.toFloat(), it.startY.toFloat(), null)
            }
        }
    }

    /** How much of [inner]'s box lies within [outer]'s. */
    private fun inside(inner: Subject, outer: Subject): Float {
        val width = min(inner.startX + inner.width, outer.startX + outer.width) - max(inner.startX, outer.startX)
        val height = min(inner.startY + inner.height, outer.startY + outer.height) - max(inner.startY, outer.startY)
        if (width <= 0 || height <= 0) return 0f
        return width.toFloat() * height / (inner.width.toFloat() * inner.height)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        /** Offline, Google Play services holds the download back; stop waiting after a while (it goes on by itself). */
        private const val DOWNLOAD_TIMEOUT_MS = 3 * 60_000L
    }
}
