package com.somecatcode.ebookreader.data.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.room.withTransaction
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.BookTagEntity
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.TAG_GENRE
import com.somecatcode.ebookreader.data.repo.encodeStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Local libraries: an account of kind `local` is one folder on the device, chosen with the system folder
 * picker (Storage Access Framework, no storage permission). The library is built by [scan] from the files
 * in the folder and its sub-folders; reading progress, highlights and edits stay on the device.
 */
interface LocalLibrary {
    /** Takes a persistable read/write permission for [treeUri] and adds it as a local library. */
    suspend fun addFolder(treeUri: Uri): Account

    /** Reads the folder again: new and changed files are added, missing ones removed. Returns the number of changed books. */
    suspend fun scan(accountId: String): Int

    /** The file of a book of a local library, or null (not local, gone, no permission). */
    suspend fun bookFile(key: BookKey): BookFile?

    /** Extracted cover of a book of a local library, null when there is none. */
    fun coverFile(accountId: String, fileId: Long): File?

    /** Before the account is removed: gives the folder permission back and deletes the extracted covers (never the books). */
    suspend fun forget(accountId: String)

    /**
     * Creates the file [fileName] in the top folder of the library (the provider picks a free name if it
     * exists), lets [write] fill it and adds it to the library. Returns the new book.
     */
    suspend fun importFile(accountId: String, fileName: String, write: (java.io.OutputStream) -> Unit): BookKey

    /** Deletes the file of a local book and the book with its progress and highlights. */
    suspend fun deleteBook(key: BookKey)

    /** Free bytes on the storage that holds the folder of the library; null when unknown. */
    suspend fun freeSpace(accountId: String): Long? = null
}

/** The folder of a local library cannot be read (deleted, SD card removed, permission revoked). */
class FolderUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

class LocalLibraryImpl(
    private val context: Context,
    private val db: AppDatabase,
    private val accountStore: () -> AccountStore,
    private val coversRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalLibrary {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun addFolder(treeUri: Uri): Account = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: SecurityException) {
            // read-only providers grant no write permission
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val name = query(rootDoc, arrayOf(Document.COLUMN_DISPLAY_NAME)) { it.getString(0) }
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "Books"
        accountStore().addLocal(treeUri.toString(), name)
    }

    override suspend fun scan(accountId: String): Int = withContext(Dispatchers.IO) {
        val account = db.accountDao().get(accountId) ?: return@withContext 0
        val tree = Uri.parse(account.serverUrl)
        val found = ArrayList<FoundFile>()
        try {
            walk(tree, DocumentsContract.getTreeDocumentId(tree), "", found, 0)
        } catch (e: FolderUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw FolderUnavailableException("Cannot read ${account.displayName}", e)
        }

        val existing = db.bookDao().getAllActive(accountId).associateBy { it.fileId }
        var changed = 0
        val seen = HashSet<Long>()
        for (f in found) {
            val fileId = localFileId(f.documentId)
            if (!seen.add(fileId)) continue
            if (index(accountId, tree, f, existing[fileId])) changed++
        }

        val vanished = existing.keys.filter { it !in seen }
        if (vanished.isNotEmpty()) removeBooks(accountId, vanished)
        db.accountDao().updateSync(accountId, null, clock())
        changed + vanished.size
    }

    /** Adds or updates one file; false when it is unchanged since the last scan. */
    private suspend fun index(accountId: String, tree: Uri, f: FoundFile, old: BookEntity?): Boolean {
        val fileId = localFileId(f.documentId)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, f.documentId).toString()
        val etag = "${f.modified}-${f.size}"
        if (old != null && old.fileEtag == etag && old.localUri == uri) {
            if (old.path != f.path) db.bookDao().upsertAll(listOf(old.copy(path = f.path)))
            return false
        }
        run {
            val meta = BookMetadataReader.read(f.format, f.name) {
                resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot open $uri")
            }
            val hasCover = meta.cover?.let { saveCover(accountId, fileId, it) } ?: run {
                coverFile(accountId, fileId)?.delete()
                false
            }
            val now = clock()
            val entity = BookEntity(
                accountId = accountId,
                fileId = fileId,
                format = f.format,
                path = f.path,
                size = f.size,
                title = meta.title,
                authors = encodeStrings(meta.authors),
                series = meta.series,
                seriesIndex = meta.seriesIndex,
                description = meta.description,
                language = meta.language,
                publisher = meta.publisher,
                isbn = meta.isbn,
                publishedAt = meta.publishedAt,
                rating = old?.rating,
                readStatus = old?.readStatus ?: "unread",
                hasCover = hasCover,
                coverEtag = etag,
                fileEtag = etag,
                mtime = f.modified / 1000,
                addedAt = old?.addedAt ?: now,
                updatedAt = now,
                editable = true,
                downloadable = true,
                overrides = encodeStrings(emptyList()),
                hasSidecar = false,
                localUri = uri,
            )
            db.withTransaction {
                db.bookDao().upsertAll(listOf(entity))
                db.bookTagDao().replaceForBook(accountId, fileId, meta.genres.map { BookTagEntity(accountId, fileId, TAG_GENRE, it) })
            }
        }
        return true
    }

    private suspend fun removeBooks(accountId: String, fileIds: List<Long>) {
        db.withTransaction {
            db.bookDao().markDeleted(accountId, fileIds)
            db.bookTagDao().deleteForBooks(accountId, fileIds)
            db.progressDao().deleteForBooks(accountId, fileIds)
            db.pendingEditDao().deleteForBooks(accountId, fileIds)
            db.annotationDao().deleteForBooks(accountId, fileIds)
        }
        db.bookDao().purgeDeleted(accountId)
        fileIds.forEach { coverFile(accountId, it)?.delete() }
    }

    override suspend fun importFile(accountId: String, fileName: String, write: (java.io.OutputStream) -> Unit): BookKey = withContext(Dispatchers.IO) {
        val account = db.accountDao().get(accountId) ?: throw FolderUnavailableException("No such library")
        val tree = Uri.parse(account.serverUrl)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // octet-stream: providers keep the name as given (no extension added for unknown types)
        val created = try {
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", fileName)
        } catch (e: Exception) {
            throw FolderUnavailableException("Cannot create $fileName", e)
        } ?: throw FolderUnavailableException("Cannot create $fileName")
        try {
            (resolver.openOutputStream(created, "w") ?: throw IOException("Cannot write $fileName")).use(write)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
        val docId = DocumentsContract.getDocumentId(created)
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val found = query(created, projection) { c ->
            val name = c.getString(0) ?: fileName
            FoundFile(
                docId, name, "/$name", BookMetadataReader.formatOf(name) ?: BookMetadataReader.formatOf(fileName) ?: "epub",
                if (c.isNull(1)) 0L else c.getLong(1), if (c.isNull(2)) clock() else c.getLong(2),
            )
        } ?: throw IOException("Cannot read $fileName")
        index(accountId, tree, found, null)
        BookKey(accountId, localFileId(docId))
    }

    override suspend fun freeSpace(accountId: String): Long? = withContext(Dispatchers.IO) {
        val account = db.accountDao().get(accountId) ?: return@withContext null
        val tree = Uri.parse(account.serverUrl)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@withContext null
        // 1) the provider's roots know the free space (some providers allow the query only to the system)
        val fromRoots = runCatching {
            resolver.query(
                DocumentsContract.buildRootsUri(tree.authority ?: return@runCatching null),
                arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null,
            )?.use { c ->
                var best: Pair<Int, Long>? = null
                while (c.moveToNext()) {
                    val rootDoc = c.getString(0) ?: continue
                    if (c.isNull(1) || !docId.startsWith(rootDoc)) continue
                    if (best == null || rootDoc.length > best.first) best = rootDoc.length to c.getLong(1)
                }
                best?.second
            }
        }.getOrNull()
        fromRoots ?: storagePathOf(tree.authority, docId)?.let { path -> runCatching { StatFs(path).availableBytes }.getOrNull() }
    }

    override suspend fun deleteBook(key: BookKey) = withContext(Dispatchers.IO) {
        val book = db.bookDao().get(key.accountId, key.fileId) ?: return@withContext
        val uri = Uri.parse(book.localUri ?: return@withContext)
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: java.io.FileNotFoundException) {
            true // already gone
        } catch (e: Exception) {
            throw FolderUnavailableException("Cannot delete ${book.path}", e)
        }
        if (!deleted) throw FolderUnavailableException("Cannot delete ${book.path}")
        removeBooks(key.accountId, listOf(key.fileId))
    }

    override suspend fun bookFile(key: BookKey): BookFile? = withContext(Dispatchers.IO) {
        val book = db.bookDao().get(key.accountId, key.fileId) ?: return@withContext null
        val uri = Uri.parse(book.localUri ?: return@withContext null)
        val length = query(uri, arrayOf(Document.COLUMN_SIZE)) { c -> if (c.isNull(0)) null else c.getLong(0) }
            ?: return@withContext null
        ContentBookFile(resolver, uri, book.path.substringAfterLast('/'), length)
    }

    override fun coverFile(accountId: String, fileId: Long): File? =
        File(File(coversRoot, safe(accountId)), fileId.toString()).takeIf { it.isFile }

    override suspend fun forget(accountId: String) = withContext(Dispatchers.IO) {
        db.accountDao().get(accountId)?.let { account ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(account.serverUrl),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        File(coversRoot, safe(accountId)).deleteRecursively()
        Unit
    }

    private fun saveCover(accountId: String, fileId: Long, bytes: ByteArray): Boolean = runCatching {
        val dir = File(coversRoot, safe(accountId)).apply { mkdirs() }
        val tmp = File(dir, "$fileId.tmp")
        tmp.writeBytes(bytes)
        val target = File(dir, fileId.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        target.isFile
    }.getOrDefault(false)

    private data class FoundFile(val documentId: String, val name: String, val path: String, val format: String, val size: Long, val modified: Long)

    private fun walk(tree: Uri, parentId: String, prefix: String, out: MutableList<FoundFile>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val dirs = ArrayList<Pair<String, String>>()
        val cursor = resolver.query(children, projection, null, null, null)
            ?: throw FolderUnavailableException("Folder query returned nothing")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2)
                if (mime == Document.MIME_TYPE_DIR) {
                    dirs += id to name
                    continue
                }
                val format = BookMetadataReader.formatOf(name) ?: continue
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                out += FoundFile(id, name, "$prefix/$name", format, size, modified)
            }
        }
        for ((id, name) in dirs) walk(tree, id, "$prefix/$name", out, depth + 1)
    }

    private fun <T> query(uri: Uri, projection: Array<String>, read: (android.database.Cursor) -> T?): T? = runCatching {
        resolver.query(uri, projection, null, null, null)?.use { c -> if (c.moveToFirst()) read(c) else null }
    }.getOrNull()

    private companion object {
        const val MAX_DEPTH = 16
        fun safe(accountId: String) = accountId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}

/**
 * Stable book id of a document in a local library: 53 bits of SHA-256 of the document id (exact as a
 * JavaScript number, which the reader page uses), never 0.
 */
internal fun localFileId(documentId: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(documentId.toByteArray(Charsets.UTF_8))
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xff)
    return (value and 0x1F_FFFF_FFFF_FFFFL).coerceAtLeast(1)
}

/** A `content://` document; seeks through the file descriptor when the provider allows it. */
private class ContentBookFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val length: Long,
) : BookFile {
    override fun open(offset: Long, count: Long): InputStream {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        try {
            (input as FileInputStream).channel.position(offset)
        } catch (_: IOException) {
            // not seekable (pipe): skip forward
            var left = offset
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) break
                left -= n
            }
        }
        return Limited(input, count)
    }

    private class Limited(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return input.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, input.available().toLong()).toInt()

        override fun close() = input.close()
    }
}

/**
 * File system path of a folder of the system storage provider: `primary:Books` lies on the internal
 * storage, `1234-ABCD:Books` on that SD card. Null for other providers.
 */
internal fun storagePathOf(authority: String?, docId: String): String? {
    if (authority != "com.android.externalstorage.documents") return null
    val volume = docId.substringBefore(':', "")
    return when {
        volume.isEmpty() -> null
        volume == "primary" -> Environment.getExternalStorageDirectory().path
        else -> "/storage/$volume"
    }
}
