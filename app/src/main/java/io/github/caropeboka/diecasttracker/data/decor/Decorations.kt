package io.github.caropeboka.diecasttracker.data.decor

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DecorationEntity
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.studio.StudioComposer
import io.github.caropeboka.diecasttracker.data.studio.StudioException
import io.github.caropeboka.diecasttracker.data.studio.StudioStep
import io.github.caropeboka.diecasttracker.data.studio.SubjectCutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

// Decorations for the 3D garage: figures and ornaments on the black shelf, from a photo with the background cut away
// on the phone (and the dust taken off, if wanted). Not diecasts: they stay out of the collection and its numbers.

/** The decorations' cut-outs: PNGs (they keep their transparency) in `files/decor`, referred to by name. */
class DecorStore(root: File) {
    private val dir = File(root, "decor")

    fun file(name: String) = File(dir, name)

    /** Stores [image] and returns its name. */
    fun save(image: Bitmap): String {
        val name = "${UUID.randomUUID()}.png"
        File(dir.apply { mkdirs() }, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    /** Stores a cut-out from a backup as it is; returns its new name. */
    fun import(bytes: ByteArray): String {
        val name = "${UUID.randomUUID()}.png"
        File(dir.apply { mkdirs() }, name).writeBytes(bytes)
        return name
    }

    /** From its bytes: Robolectric can't decode a file. */
    fun bitmap(name: String, maxEdge: Int = MAX_EDGE): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(file(name).readBytes()))) { decoder, info, _ ->
            val edge = max(info.size.width, info.size.height)
            if (edge > maxEdge) decoder.setTargetSize(info.size.width * maxEdge / edge, info.size.height * maxEdge / edge)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    fun delete(name: String) {
        file(name).delete()
    }

    /** Removes cut-outs no decoration uses any more (an interrupted add or restore). */
    fun housekeeping(used: Set<String>) {
        dir.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    companion object {
        /** Big enough for a figure held up close in the garage. */
        const val MAX_EDGE = 1024
    }
}

class DecorRepository(
    private val db: AppDatabase,
    private val store: DecorStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.decorations()

    fun observe(): Flow<List<DecorationEntity>> = dao.observe()

    suspend fun add(name: String, image: Bitmap, heightMm: Int, tier: Int, position: Int): Long {
        val photo = withContext(Dispatchers.IO) { store.save(image) }
        return dao.insert(DecorationEntity(name = name, photo = photo, heightMm = heightMm, tier = tier, position = position, createdAt = clock()))
    }

    suspend fun move(id: Long, tier: Int, position: Int) = dao.move(id, tier, position)

    suspend fun rename(id: Long, name: String, heightMm: Int) {
        val decoration = dao.get(id) ?: return
        dao.update(decoration.copy(name = name, heightMm = heightMm))
    }

    suspend fun delete(id: Long) {
        val decoration = dao.get(id) ?: return
        dao.delete(id)
        withContext(Dispatchers.IO) { store.delete(decoration.photo) }
    }

    suspend fun housekeeping() {
        val used = dao.all().map { it.photo }.toSet()
        withContext(Dispatchers.IO) { store.housekeeping(used) }
    }
}

/** A photo made into a decoration: the subject alone, as it was and with the dust taken off. */
class DecorCutout(val original: Bitmap, val cleaned: Bitmap)

/** Cuts the subject out of a photo with [cutter] and cleans it (see [Dusting]). */
class DecorMaker(private val texts: Texts, private val cutter: SubjectCutter) {
    suspend fun make(photo: Bitmap, onStep: (StudioStep) -> Unit = {}): DecorCutout {
        val cutout = cutter.cut(photo, onStep) ?: throw StudioException(texts(NOT_FOUND))
        onStep(StudioStep.Composing)
        return withContext(Dispatchers.Default) {
            val subject = StudioComposer.isolate(cutout) ?: throw StudioException(texts(NOT_FOUND))
            val fitted = fit(subject, DecorStore.MAX_EDGE)
            DecorCutout(fitted, Dusting.clean(fitted))
        }
    }

    private fun fit(image: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(image.width, image.height)
        if (edge <= maxEdge) return image
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(image, (image.width * scale).roundToInt(), (image.height * scale).roundToInt(), true)
    }

    companion object {
        @StringRes val NOT_FOUND = R.string.decor_not_found
    }
}
