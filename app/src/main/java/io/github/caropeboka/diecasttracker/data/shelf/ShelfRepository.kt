package io.github.caropeboka.diecasttracker.data.shelf

import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.ShelfDao
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity

/** Puts diecasts on the 3D garage's shelves and back in the tray, with the "Lokasi simpan" that goes with it. */
class ShelfRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.shelves()

    /** Carries out what [ShelfPlanner] worked out. */
    suspend fun apply(moves: Map<Long, ShelfSlotEntity?>) {
        db.withTransaction {
            val now = clock()
            moves.forEach { (id, slot) -> if (slot == null) dao.unshelve(id, now) else dao.shelve(slot, now) }
        }
    }

    /** Back to the tray. */
    suspend fun release(id: Long) = apply(mapOf(id to null))
}

/** On the shelves: its "Lokasi simpan" becomes the place's name, whatever it was. */
internal suspend fun ShelfDao.shelve(slot: ShelfSlotEntity, now: Long) {
    put(slot)
    val label = ShelfLayout.label(slot)
    if (storageLocation(slot.diecastId) != label) setStorageLocation(slot.diecastId, label, now)
}

/** Off the shelves: its "Lokasi simpan" goes too, unless it was changed by hand since. */
internal suspend fun ShelfDao.unshelve(id: Long, now: Long) {
    val slot = get(id) ?: return
    clear(id)
    if (storageLocation(id) == ShelfLayout.label(slot)) setStorageLocation(id, null, now)
}
