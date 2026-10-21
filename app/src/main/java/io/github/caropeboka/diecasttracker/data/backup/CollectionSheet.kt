package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.db.DiecastWithDetails
import java.io.OutputStream

/**
 * The collection as one Excel table. Columns and labels follow the web's export (`CollectionSpreadsheet.php`), so its
 * "Import Excel" reads this file too.
 */
object CollectionSheet {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    val HEADERS = listOf(
        "Nama", "Merek", "Pabrikan", "Seri", "Nomor Koleksi", "Tahun", "Skala", "Warna", "Kelangkaan", "Kemasan",
        "Kondisi", "Status", "Jumlah", "Harga Beli", "Harga Pasaran", "Target Harga", "Tanggal Beli", "Beli Di",
        "Lokasi Simpan", "Tag", "Catatan",
    )

    /** The web's sheet says "Carded" where the form says "Carded (blister)". */
    private fun Packaging.sheetLabel() = if (this == Packaging.Carded) "Carded" else label

    fun row(details: DiecastWithDetails): List<Any?> = with(details.diecast) {
        listOf(
            name, brand, make, series, collectionNumber, year, scale, color, rarity.label, packaging.sheetLabel(),
            condition?.label, status.label, quantity, purchasePrice, marketValue, targetPrice, purchaseDate?.toString(),
            purchasePlace, storageLocation, details.orderedTags.joinToString(", ") { it.name }.ifEmpty { null }, notes,
        )
    }

    fun write(out: OutputStream, diecasts: List<DiecastWithDetails>) {
        XlsxWriter.write(out, "Koleksi", HEADERS, diecasts.map(::row), widths = listOf(32.0) + List(HEADERS.size - 1) { 16.0 })
    }
}
