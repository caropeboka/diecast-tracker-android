package io.github.caropeboka.diecasttracker.data.studio

import android.graphics.Bitmap
import io.github.caropeboka.diecasttracker.data.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StudioException(message: String) : Exception(message)

/** What [StudioPhoto.make] is doing, for the progress steps. */
sealed interface StudioStep {
    /** The background remover is fetched once; [fraction] when Google Play services reports it. */
    data class Downloading(val fraction: Float?) : StudioStep

    data object Cutting : StudioStep

    data object Composing : StudioStep
}

/** Finds the diecast in a photo. */
fun interface SubjectCutter {
    /** [image] with everything but the diecast transparent, same size; null when there is none. */
    suspend fun cut(image: Bitmap, onStep: (StudioStep) -> Unit): Bitmap?
}

/** "Foto studio": a photo of the diecast cut out, straightened and put on white, on the phone itself. */
class StudioPhoto(private val photos: PhotoStore, private val cutter: SubjectCutter) {
    /** Makes a studio version of the photo [name] as a new pending photo and returns its name; [name] stays as it is. */
    suspend fun make(name: String, onStep: (StudioStep) -> Unit = {}): String {
        val source = withContext(Dispatchers.IO) { photos.bitmap(name) }
        val cutout = cutter.cut(source, onStep) ?: throw StudioException(NOT_FOUND)
        onStep(StudioStep.Composing)
        val studio = withContext(Dispatchers.Default) { StudioComposer.compose(cutout) } ?: throw StudioException(NOT_FOUND)
        return withContext(Dispatchers.IO) { photos.addBitmap(studio) }
    }

    // A card or box by its corners: the photo is shown, the corners guessed and moved by hand, then squared up and the
    // background still showing erased (the hang hole by itself, the rest by tapping).

    /** The photo [name], to place a card's corners on. */
    suspend fun load(name: String): Bitmap = withContext(Dispatchers.IO) { photos.bitmap(name) }

    /** Where the card or box in [image] probably is (see [StudioComposer.cardCorners]); null when it can't tell. */
    suspend fun guessCorners(image: Bitmap): FloatArray? = try {
        cutter.cut(image) {}?.let { withContext(Dispatchers.Default) { StudioComposer.cardCorners(it) } }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The card or box within [corners] on [image], squared up (see [CardCutout.from]). */
    suspend fun cutCard(image: Bitmap, corners: FloatArray, rounded: Boolean): CardCutout =
        withContext(Dispatchers.Default) { CardCutout.from(image, corners, rounded) } ?: throw StudioException(NO_CARD)

    /** [card] on white as a new pending photo; returns its name. */
    suspend fun makeCard(card: CardCutout): String {
        val studio = withContext(Dispatchers.Default) { card.compose() }
        return withContext(Dispatchers.IO) { photos.addBitmap(studio) }
    }

    companion object {
        const val NOT_FOUND = "Diecast-nya tidak terdeteksi di foto ini. Coba foto dengan latar polos dan cahaya terang."
        const val NO_CARD = "Keempat titik harus membentuk segi empat di sudut-sudut kartu."
    }
}
