package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.data.db.DecorationEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The 3D garage's decorations in a backup made on the phone: `decor.json` and their cut-outs under `decor/`. Only
 * written when there are any; the web's restore ignores both.
 */
object DecorBackup {
    const val ENTRY = "decor.json"
    const val FORMAT = "diecast-tracker-decor"
    val PHOTO_ENTRY = Regex("decor/[A-Za-z0-9._-]+\\.png", RegexOption.IGNORE_CASE)

    /** A cut-out is a few hundred KB at most; anything far bigger isn't one of ours. */
    const val MAX_PHOTO_BYTES = 16L * 1024 * 1024

    private val JSON = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Manifest(val format: String = FORMAT, val version: Int = 1, val decorations: List<Item> = emptyList())

    @Serializable
    data class Item(
        val name: String,
        /** Its cut-out in the zip, `decor/<file>.png`. */
        val photo: String,
        @SerialName("height_mm") val heightMm: Int,
        val tier: Int,
        val position: Int,
        @SerialName("created_at") val createdAt: Long,
    )

    fun item(decoration: DecorationEntity, photo: String) =
        Item(decoration.name, photo, decoration.heightMm, decoration.tier, decoration.position, decoration.createdAt)

    fun encode(items: List<Item>): String = JSON.encodeToString(Manifest.serializer(), Manifest(decorations = items))

    /** Null when it isn't ours or can't be read. */
    fun decode(text: String): Manifest? =
        runCatching { JSON.decodeFromString(Manifest.serializer(), text) }.getOrNull()?.takeIf { it.format == FORMAT }
}
