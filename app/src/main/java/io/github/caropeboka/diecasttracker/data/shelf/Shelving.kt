package io.github.caropeboka.diecasttracker.data.shelf

import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity

/** The furniture in the 3D garage a diecast can stand on, stored by [key]. */
enum class Shelf(val key: String) {
    /** The two MDF boards: eight lanes the cards slide down, stacking from the bottom. */
    Boards("boards"),

    /** The black corner shelf: three tiers, boxes and loose cars anywhere along them. */
    Corner("corner"),

    /** The white case: compartments for loose cars and small boxes. */
    Case("case");

    companion object {
        fun fromKey(key: String?): Shelf? = entries.find { it.key == key }
    }
}

/** How the furniture is laid out, which diecast may stand where, and what a place is called. */
object ShelfLayout {
    const val BOARDS = 2
    const val LANES_PER_BOARD = 4
    const val LANES = BOARDS * LANES_PER_BOARD

    /** As many cards as stack in a lane below the top of its rails. */
    const val LANE_CARDS = 12
    const val TIERS = 3
    const val CASE_COLUMNS = 6
    const val CASE_ROWS = 10

    private val TIER_NAMES = listOf("bawah", "tengah", "atas")

    /** Lanes, tiers (lowest first) or compartments (row by row from the top left). */
    fun spots(shelf: Shelf): Int = when (shelf) {
        Shelf.Boards -> LANES
        Shelf.Corner -> TIERS
        Shelf.Case -> CASE_COLUMNS * CASE_ROWS
    }

    /** Cards hang on the boards; a compartment takes a loose car or a box up to 1:64, the black shelf any of them. */
    fun fits(shelf: Shelf, packaging: Packaging, scale: String?): Boolean = when (shelf) {
        Shelf.Boards -> packaging == Packaging.Carded
        Shelf.Case -> packaging != Packaging.Carded && scaleRatio(scale) >= 64
        Shelf.Corner -> packaging != Packaging.Carded
    }

    /** Its "Lokasi simpan": "Papan 2 · jalur 1", "Laci hitam · susun tengah", "Rak putih · baris 3 kolom 2". */
    fun label(shelf: Shelf, spot: Int): String = when (shelf) {
        Shelf.Boards -> "Papan ${spot / LANES_PER_BOARD + 1} · jalur ${spot % LANES_PER_BOARD + 1}"
        Shelf.Corner -> "Laci hitam · susun ${TIER_NAMES[spot]}"
        Shelf.Case -> "Rak putih · baris ${spot / CASE_COLUMNS + 1} kolom ${spot % CASE_COLUMNS + 1}"
    }

    fun label(slot: ShelfSlotEntity): String = label(slot.shelf, slot.spot)
}

/** The N of a "1:N" scale; 1:64 when there is none. */
fun scaleRatio(scale: String?): Float = scale?.substringAfter(':', "")?.toFloatOrNull()?.takeIf { it > 0 } ?: 64f

/** A diecast as [ShelfPlanner] sees it; [slot] null while it waits in the tray. */
data class Shelved(val id: Long, val packaging: Packaging, val scale: String?, val slot: ShelfSlotEntity?)

sealed interface ShelfMove {
    /** Where each diecast that moves ends up; null for back to the tray. */
    data class Done(val moves: Map<Long, ShelfSlotEntity?>) : ShelfMove

    /** The lane holds as many cards as it can. */
    data object Full : ShelfMove

    /** Not on that shelf: a card off the boards, a box or a loose car on them, anything above 1:64 in the case. */
    data object DoesNotFit : ShelfMove
}

/**
 * Where diecasts go when the collector puts them somewhere: anywhere they fit, no order to fill in. Cards slide down
 * their lane onto the ones already there; a compartment holds one, so whatever is in it swaps places.
 */
object ShelfPlanner {
    /**
     * [id] to [spot] of [shelf]: on top of a lane's cards, at [position] along a tier (its angle in tenths of a degree,
     * 0 along the back wall), into a compartment, swapping with what's in it (see [swap]).
     */
    fun place(all: List<Shelved>, id: Long, shelf: Shelf, spot: Int, position: Int = 0): ShelfMove {
        val mover = all.find { it.id == id } ?: return ShelfMove.DoesNotFit
        if (spot !in 0 until ShelfLayout.spots(shelf) || !ShelfLayout.fits(shelf, mover.packaging, mover.scale)) {
            return ShelfMove.DoesNotFit
        }
        val there = all.mapNotNull { other -> other.slot?.takeIf { other.id != id && it.shelf == shelf && it.spot == spot } }
        return when (shelf) {
            Shelf.Boards ->
                if (there.size >= ShelfLayout.LANE_CARDS) {
                    ShelfMove.Full
                } else {
                    ShelfMove.Done(mapOf(id to ShelfSlotEntity(id, shelf, spot, (there.maxOfOrNull { it.position } ?: -1) + 1)))
                }
            Shelf.Corner -> ShelfMove.Done(mapOf(id to ShelfSlotEntity(id, shelf, spot, position)))
            Shelf.Case -> there.firstOrNull()?.let { swap(all, id, it.diecastId) }
                ?: ShelfMove.Done(mapOf(id to ShelfSlotEntity(id, shelf, spot)))
        }
    }

    /** [id] takes [other]'s place; [other] takes [id]'s old one if it fits there, else it goes to the tray. */
    fun swap(all: List<Shelved>, id: Long, other: Long): ShelfMove {
        val mover = all.find { it.id == id }
        val target = all.find { it.id == other }
        val place = target?.slot
        if (mover == null || place == null || id == other || !ShelfLayout.fits(place.shelf, mover.packaging, mover.scale)) {
            return ShelfMove.DoesNotFit
        }
        val back = mover.slot?.takeIf { ShelfLayout.fits(it.shelf, target.packaging, target.scale) }
        return ShelfMove.Done(mapOf(id to place.copy(diecastId = id), other to back?.copy(diecastId = other)))
    }
}
