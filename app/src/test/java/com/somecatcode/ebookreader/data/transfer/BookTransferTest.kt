package com.somecatcode.ebookreader.data.transfer

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.FakeSettings
import com.somecatcode.ebookreader.data.RecordingScheduler
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.addAccount
import com.somecatcode.ebookreader.data.db.AccountKind
import com.somecatcode.ebookreader.data.db.AnnotationEntity
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.local.BookFile
import com.somecatcode.ebookreader.data.local.LocalLibrary
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.repo.AnnotationRepositoryImpl
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.EditRepositoryImpl
import com.somecatcode.ebookreader.data.repo.ProgressRepositoryImpl
import com.somecatcode.ebookreader.data.serverWith
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncOutcome
import com.somecatcode.ebookreader.data.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class BookTransferTest : DbTest() {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private val api = server.ebookApi()
    private val content = "PK-epub-bytes".toByteArray()
    private val local = FakeLocal()
    private val sync = IndexingSync()
    private lateinit var transfer: BookTransferImpl

    /** Local library in memory: imported files become book 500, deletions are recorded. */
    private inner class FakeLocal : LocalLibrary {
        var imported: ByteArray? = null
        var importedName: String? = null
        val deleted = mutableListOf<BookKey>()
        override suspend fun addFolder(treeUri: android.net.Uri): Account = error("unused")
        override suspend fun scan(accountId: String) = 0
        override suspend fun bookFile(key: BookKey): BookFile? = if (key.accountId != "loc") null else object : BookFile {
            override val name = "x.epub"
            override val length = content.size.toLong()
            override fun open(offset: Long, count: Long): InputStream = ByteArrayInputStream(content, offset.toInt(), count.toInt())
        }
        override fun coverFile(accountId: String, fileId: Long): File? = null
        override suspend fun forget(accountId: String) = Unit
        override suspend fun importFile(accountId: String, fileName: String, write: (OutputStream) -> Unit): BookKey {
            imported = ByteArrayOutputStream().also(write).toByteArray()
            importedName = fileName
            db.bookDao().upsertAll(listOf(book(500, accountId = accountId).copy(localUri = "content://x/500")))
            return BookKey(accountId, 500)
        }
        override suspend fun deleteBook(key: BookKey) {
            deleted += key
            db.bookDao().markDeleted(key.accountId, listOf(key.fileId))
            db.bookDao().purgeDeleted(key.accountId)
        }
    }

    /** The "server" indexes uploaded files: a sync of acc1 makes book 42 appear. */
    private inner class IndexingSync : SyncEngine {
        var indexed = false
        override val state: Flow<Map<String, SyncState>> = emptyFlow()
        override suspend fun syncNow(accountId: String): SyncOutcome {
            if (indexed) db.bookDao().upsertAll(listOf(book(42).copy(path = "/Books/x (2).epub")))
            return SyncOutcome.Success(1, 0, 0)
        }
        override fun requestSync(accountId: String?) = Unit
        override fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean) = Unit
    }

    private val noDownloads = object : DownloadRepository {
        override val items = emptyFlow<List<com.somecatcode.ebookreader.data.repo.OfflineItem>>()
        override val usedBytes = emptyFlow<Long>()
        override suspend fun makeAvailableOffline(target: com.somecatcode.ebookreader.data.repo.OfflineTarget) = Unit
        override suspend fun removeOffline(target: com.somecatcode.ebookreader.data.repo.OfflineTarget) = Unit
        override suspend fun retry(key: BookKey) = Unit
        override suspend fun cancel(key: BookKey) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun localFile(key: BookKey): File? = null
        override fun downloadEntity(key: BookKey): Flow<DownloadEntity?> = emptyFlow()
    }

    @Before
    fun setUp() = runBlocking {
        db.addAccount("loc", kind = AccountKind.LOCAL)
        val factory = StaticApiFactory(api)
        val scheduler = RecordingScheduler()
        val downloadManager = object : DownloadManager by noop() {}
        transfer = BookTransferImpl(
            db, factory, local, noDownloads, { downloadManager }, { sync },
            { ProgressRepositoryImpl(db, factory, FakeSettings(), scheduler, { "Pixel" }) },
            { AnnotationRepositoryImpl(db, factory, scheduler) },
            { EditRepositoryImpl(db, factory, scheduler) },
            clock = { 5_000 }, indexWaitMs = 1,
        )
        dispatcher.on("GET", "/settings") { ocs("""{"libraryFolders":["/Books"],"reader":{}}""") }
        // HEAD answers carry no body
        dispatcher.on("HEAD", "/remote.php/dav/") { MockResponse().setResponseCode(404) }
        dispatcher.on("HEAD", "/remote.php/dav/files/alice/Books/x.epub") { MockResponse().setResponseCode(200) }
        dispatcher.on("PUT", "/remote.php/dav/files/alice/Books/x%20(2).epub") {
            sync.indexed = true
            MockResponse().setResponseCode(201).addHeader("OC-FileId", "00000042ocabc123")
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Suppress("UNCHECKED_CAST")
    private fun noop(): DownloadManager = java.lang.reflect.Proxy.newProxyInstance(
        DownloadManager::class.java.classLoader, arrayOf(DownloadManager::class.java),
    ) { _, _, _ -> null } as DownloadManager

    private suspend fun localBookWithState() {
        db.bookDao().upsertAll(listOf(book(9, accountId = "loc", readStatus = "reading").copy(localUri = "content://x/9", path = "/x.epub", rating = 4)))
        db.progressDao().upsert(ProgressEntity("loc", 9, """{"href":"c3.xhtml"}""", 0.3, "Pixel", 100, 0, false))
        db.annotationDao().upsert(AnnotationEntity("loc", "uuid-1", 9, "highlight", """{"href":"c3.xhtml"}""", "Text", null, "yellow", 1, 0, 1, false, false))
    }

    @Test
    fun copyFromLocalToNextcloudUploadsUnderAFreeNameAndTakesTheStateAlong() = runBlocking {
        localBookWithState()
        val result = transfer.transfer(BookKey("loc", 9), "acc1", null, move = false)
        assertEquals(BookKey("acc1", 42), result.target)
        assertTrue(result.stateCopied)
        val put = dispatcher.requests.first { it.method == "PUT" }
        assertArrayEquals(content, put.body.readByteArray())
        val progress = db.progressDao().get("acc1", 42)!!
        assertEquals(0.3, progress.percentage, 0.0)
        assertEquals(5_000L, progress.clientUpdatedAt)
        val copied = db.annotationDao().allForBook("acc1", 42).single()
        assertTrue("a copy gets its own uuid", copied.uuid != "uuid-1")
        assertEquals(4, db.bookDao().get("acc1", 42)!!.rating)
        assertTrue("the original stays", local.deleted.isEmpty())
        assertTrue(db.bookDao().get("loc", 9) != null)
    }

    @Test
    fun moveFromLocalDeletesTheOriginalAfterTheCopy() = runBlocking {
        localBookWithState()
        transfer.transfer(BookKey("loc", 9), "acc1", "/Books", move = true)
        assertEquals(listOf(BookKey("loc", 9)), local.deleted)
    }

    @Test
    fun moveFromNextcloudToLocalDownloadsImportsAndDeletesOnTheServer() = runBlocking {
        db.bookDao().upsertAll(listOf(book(7)))
        db.progressDao().upsert(ProgressEntity("acc1", 7, """{"href":"c1.xhtml"}""", 0.6, "Web", 100, 101, false))
        dispatcher.on("GET", "/remote.php/dav/files/alice/Books/7.epub") { MockResponse().setBody(okio.Buffer().write(content)) }
        dispatcher.on("DELETE", "/remote.php/dav/files/alice/Books/7.epub") { MockResponse().setResponseCode(204) }
        val result = transfer.transfer(BookKey("acc1", 7), "loc", null, move = true)
        assertEquals(BookKey("loc", 500), result.target)
        assertArrayEquals(content, local.imported)
        assertEquals("7.epub", local.importedName)
        val progress = db.progressDao().get("loc", 500)!!
        assertEquals(0.6, progress.percentage, 0.0)
        assertTrue("local copies are never uploaded", !progress.dirty)
        assertTrue(dispatcher.requests.any { it.method == "DELETE" })
        assertNull(db.bookDao().get("acc1", 7))
    }

    @Test
    fun aBookSharedByAnotherUserCanBeCopiedButNotMoved() = runBlocking {
        db.bookDao().upsertAll(listOf(book(8).copy(shared = true, owner = "bob")))
        try {
            transfer.transfer(BookKey("acc1", 8), "loc", null, move = true)
            fail("moving a share must be refused")
        } catch (e: TransferException) {
            assertEquals(TransferException.Reason.NOT_ALLOWED, e.reason)
        }
        assertTrue(dispatcher.requests.none { it.method == "DELETE" })
    }

    @Test
    fun foldersAndFreeNames() = runBlocking {
        assertEquals(listOf("/Books"), transfer.folders("acc1"))
        assertEquals(emptyList<String>(), transfer.folders("loc"))
        assertEquals("/Books", BookTransferImpl.normalizeFolder("Books/"))
        assertEquals("/a.epub", BookTransferImpl.freePath("/", "a.epub") { false })
        val taken = setOf("/B/a.epub", "/B/a (2).epub")
        assertEquals("/B/a (3).epub", BookTransferImpl.freePath("/B", "a.epub") { it in taken })
        assertEquals(42L, com.somecatcode.ebookreader.data.api.parseOcFileId("00000042ocabc123"))
        assertNull(com.somecatcode.ebookreader.data.api.parseOcFileId("oc123"))
    }
}
