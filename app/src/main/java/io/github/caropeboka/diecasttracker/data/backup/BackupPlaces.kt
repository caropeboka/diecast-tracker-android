package io.github.caropeboka.diecasttracker.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.OutputStream

/** Where a chosen place is: only a copy off the phone helps when the phone is lost. */
enum class PlaceKind { Phone, Drive, Other }

/**
 * The automatic backup's places through the Storage Access Framework: a folder tree the collector picked once and a
 * document they created once, both kept with a persistable permission.
 */
object BackupPlaces {
    private const val ZIP = "application/zip"
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun folder(resolver: ContentResolver, uri: String): BackupFolder = SafFolder(resolver, uri.toUri())

    fun copy(resolver: ContentResolver, uri: String): BackupCopy = BackupCopy {
        val document = uri.toUri()
        // "wt" empties the file first; a provider without it (some clouds) gets "w".
        runCatching { resolver.openOutputStream(document, "wt") }.getOrNull()
            ?: resolver.openOutputStream(document, "w")
            ?: throw BackupPlaceGoneException()
    }

    /** Keeps the picker's permission past restarts. False when the place won't allow that (then it can't be used). */
    fun keep(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, FLAGS)
    }.isSuccess

    fun release(context: Context, uri: String?) {
        uri ?: return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), FLAGS) }
    }

    /** A folder's name, e.g. "Diecast Tracker"; null when it can't be read. */
    fun folderName(context: Context, uri: String): String? = runCatching {
        val tree = uri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        displayName(context.contentResolver, document, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    }.getOrNull()

    /** A document's name, e.g. "diecast-tracker-backup.zip"; null when it can't be read. */
    fun fileName(context: Context, uri: String): String? =
        runCatching { displayName(context.contentResolver, uri.toUri(), OpenableColumns.DISPLAY_NAME) }.getOrNull()

    /**
     * Where a place keeps its file, from its provider: the phone's own storage, Google Drive or another app. Not from
     * the app's name, which the card once showed as "Drive" for a file saved on the phone.
     */
    fun kind(uri: String): PlaceKind = when (uri.substringAfter("content://").substringBefore('/')) {
        in PHONE -> PlaceKind.Phone
        in DRIVE -> PlaceKind.Drive
        else -> PlaceKind.Other
    }

    private val PHONE = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
    )
    private val DRIVE = setOf("com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy")

    /** The app that holds a place of [PlaceKind.Other], e.g. "Dropbox"; null when unknown. */
    fun appName(context: Context, uri: String): String? = runCatching {
        val authority = uri.toUri().authority ?: return null
        val pm = context.packageManager
        pm.resolveContentProvider(authority, 0)?.applicationInfo?.loadLabel(pm)?.toString()
    }.getOrNull()

    private fun displayName(resolver: ContentResolver, uri: Uri, column: String): String? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    private class SafFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {
        private val root: String get() = DocumentsContract.getTreeDocumentId(tree)

        /** Document id → name of every file in the folder. */
        private fun children(): List<Pair<String, String>> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val cursor = resolver.query(uri, columns, null, null, null) ?: throw BackupPlaceGoneException()
            return cursor.use { buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) } }
        }

        private fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        override fun names(): List<String> = children().map { it.second }

        override fun write(name: String): OutputStream {
            val existing = children().firstOrNull { it.second == name }?.first?.let(::document)
            val target = existing
                ?: DocumentsContract.createDocument(resolver, document(root), ZIP, name)
                ?: throw FileNotFoundException(name)
            return resolver.openOutputStream(target, "wt") ?: throw FileNotFoundException(name)
        }

        override fun delete(name: String) {
            children().filter { it.second == name }.forEach { (id, _) -> DocumentsContract.deleteDocument(resolver, document(id)) }
        }
    }
}
