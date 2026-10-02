package com.somecatcode.ebookreader.data.download

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.FakeSettings
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadManagerTest : DbTest() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(1000) { (it % 251).toByte() }
    private val etag = "\"etag-1\""
    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private lateinit var storage: DownloadStorage
    private lateinit var manager: DownloadManagerImpl
    private val key = BookKey("acc1", 1)

    @Before
    fun setUp() {
        storage = DownloadStorage { tmp.root }
        manager = DownloadManagerImpl(
            db, StaticApiFactory(server.ebookApi()), FakeSettings(), storage,
            workManager = { WorkManager.getInstance(ApplicationProvider.getApplicationContext()) },
        )
        runBlocking { db.bookDao().upsertAll(listOf(book(1, size = content.size.toLong()))) }
        // davFileUrl uses the account's user id; the test account is "alice"
        dispatcher.on("GET", "/remote.php/dav/files/alice/Books/1.epub") { req ->
            val range = req.getHeader("Range")
            val ifRange = req.getHeader("If-Range")
            if (range != null && (ifRange == null || ifRange == currentEtag)) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (from >= content.size) {
                    MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */${content.size}")
                } else {
                    MockResponse().setResponseCode(206)
                        .addHeader("ETag", currentEtag)
                        .addHeader("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
                        .setBody(Buffer().write(content, from, content.size - from))
                }
            } else {
                MockResponse().setResponseCode(200).addHeader("ETag", currentEtag).setBody(Buffer().write(content))
            }
        }
    }

    private var currentEtag = etag

    @After
    fun tearDown() = server.shutdown()

    private fun queuedRow(etag: String? = null, bytes: Long = 0) =
        DownloadEntity("acc1", 1, "QUEUED", bytes, content.size.toLong(), null, etag, "BOOK", null, null, 0)

    private val target get() = storage.target("acc1", 1, "epub")

    @Test
    fun downloadsToPartFileThenRenamesAtomically() = runBlocking {
        db.downloadDao().upsert(queuedRow())

        val result = manager.runDownload(key, attempt = 0)

        assertEquals(DownloadRun.Done, result)
        assertArrayEquals(content, target.readBytes())
        assertFalse(storage.part(target).exists())
        val row = db.downloadDao().get("acc1", 1)!!
        assertEquals("DONE", row.state)
        assertEquals(1000L, row.bytes)
        assertEquals(etag, row.fileEtag)
        assertEquals(target.absolutePath, row.localPath)
        assertEquals(etag, db.bookDao().get("acc1", 1)!!.fileEtag)
        assertEquals("tmp-relative layout <root>/<account>/<file>.<format>", File(tmp.root, "acc1/1.epub"), target)
        assertNotNull(dispatcher.requests.single().getHeader("Authorization"))
        assertNull("no range on a fresh download", dispatcher.requests.single().getHeader("Range"))
    }

    @Test
    fun resumesWithRangeAndIfRange() = runBlocking {
        target.parentFile!!.mkdirs()
        storage.part(target).writeBytes(content.copyOfRange(0, 400))
        db.downloadDao().upsert(queuedRow(etag = etag, bytes = 400))

        assertEquals(DownloadRun.Done, manager.runDownload(key, 0))

        val req = dispatcher.requests.single()
        assertEquals("bytes=400-", req.getHeader("Range"))
        assertEquals(etag, req.getHeader("If-Range"))
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun changedFileOnServerRestartsFromZero() = runBlocking {
        target.parentFile!!.mkdirs()
        storage.part(target).writeBytes(ByteArray(400) { 7 }) // stale garbage from the old version
        db.downloadDao().upsert(queuedRow(etag = "\"old-etag\"", bytes = 400))

        assertEquals(DownloadRun.Done, manager.runDownload(key, 0))

        val req = dispatcher.requests.single()
        assertEquals("\"old-etag\"", req.getHeader("If-Range")) // server ignores the range -> 200
        assertArrayEquals(content, target.readBytes())
        assertEquals(etag, db.downloadDao().get("acc1", 1)!!.fileEtag)
    }

    @Test
    fun partialFileWithoutEtagIsNotResumed() = runBlocking {
        target.parentFile!!.mkdirs()
        storage.part(target).writeBytes(ByteArray(400) { 7 })
        db.downloadDao().upsert(queuedRow(etag = null, bytes = 400))

        assertEquals(DownloadRun.Done, manager.runDownload(key, 0))

        assertNull(dispatcher.requests.single().getHeader("Range"))
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun completePartFileAnswered416IsFinished() = runBlocking {
        target.parentFile!!.mkdirs()
        storage.part(target).writeBytes(content)
        db.downloadDao().upsert(queuedRow(etag = etag, bytes = 1000))

        assertEquals(DownloadRun.Done, manager.runDownload(key, 0))
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun notFoundFailsPermanently() = runBlocking {
        val d = RoutingDispatcher()
        val s = serverWith(d)
        val m = DownloadManagerImpl(db, StaticApiFactory(s.ebookApi()), FakeSettings(), storage, { error("unused") })
        db.downloadDao().upsert(queuedRow())

        val result = m.runDownload(key, 0)

        assertEquals(DownloadRun.Failed("not found"), result)
        assertEquals("FAILED", db.downloadDao().get("acc1", 1)!!.state)
        s.shutdown()
    }

    @Test
    fun serverErrorRetriesThenFailsAfterMaxAttempts() = runBlocking {
        val d = RoutingDispatcher()
        d.on("GET", "/remote.php") { MockResponse().setResponseCode(503) }
        val s = serverWith(d)
        val m = DownloadManagerImpl(db, StaticApiFactory(s.ebookApi()), FakeSettings(), storage, { error("unused") })
        db.downloadDao().upsert(queuedRow())

        assertTrue(m.runDownload(key, attempt = 1) is DownloadRun.Retry)
        assertEquals("QUEUED", db.downloadDao().get("acc1", 1)!!.state)
        assertTrue(m.runDownload(key, attempt = DownloadManagerImpl.MAX_ATTEMPTS) is DownloadRun.Failed)
        assertEquals("FAILED", db.downloadDao().get("acc1", 1)!!.state)
        s.shutdown()
    }

    @Test
    fun truncatedBodyKeepsPartForResume() = runBlocking {
        val d = RoutingDispatcher()
        d.on("GET", "/remote.php") {
            MockResponse().setResponseCode(200).addHeader("ETag", etag)
                .setBody(Buffer().write(content)).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        val s = serverWith(d)
        val m = DownloadManagerImpl(db, StaticApiFactory(s.ebookApi()), FakeSettings(), storage, { error("unused") })
        db.downloadDao().upsert(queuedRow())

        assertTrue(m.runDownload(key, 0) is DownloadRun.Retry)
        assertFalse("not renamed while incomplete", target.exists())
        s.shutdown()
    }

    @Test
    fun localFileOnlyForFinishedDownloadsAndResetsVanishedFiles() = runBlocking {
        assertNull(manager.localFile(key))
        db.downloadDao().upsert(queuedRow())
        manager.runDownload(key, 0)
        assertEquals(target, manager.localFile(key))

        target.delete()
        assertNull(manager.localFile(key))
        assertNull("row reset when the file vanished", db.downloadDao().get("acc1", 1))
    }

    @Test
    fun deleteRemovesFilesAndRow() = runBlocking {
        db.downloadDao().upsert(queuedRow())
        manager.runDownload(key, 0)
        storage.part(target).writeText("junk")
        manager.delete(key)
        assertFalse(target.exists())
        assertFalse(storage.part(target).exists())
        assertNull(db.downloadDao().get("acc1", 1))
    }

    @Test
    fun enqueueCreatesQueuedRowAndMergesPins() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        manager.enqueue(key, PinnedBy.SERIES, "Saga")
        var row = db.downloadDao().get("acc1", 1)!!
        assertEquals(DownloadState.QUEUED.name, row.state)
        assertEquals("SERIES", row.pinnedBy)
        assertEquals("Saga", row.pinRef)

        manager.enqueue(key, PinnedBy.SHELF, "7") // existing pin stays
        assertEquals("SERIES", db.downloadDao().get("acc1", 1)!!.pinnedBy)

        manager.enqueue(key, PinnedBy.BOOK) // a single-book pin outranks the others
        row = db.downloadDao().get("acc1", 1)!!
        assertEquals("BOOK", row.pinnedBy)
    }

    @Test
    fun enqueueSkipsUndownloadableAndDeletedBooks() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        db.bookDao().upsertAll(listOf(book(2, downloadable = false), book(3, deleted = true)))
        manager.enqueue(BookKey("acc1", 2), PinnedBy.BOOK)
        manager.enqueue(BookKey("acc1", 3), PinnedBy.BOOK)
        assertNull(db.downloadDao().get("acc1", 2))
        assertNull(db.downloadDao().get("acc1", 3))
    }

    @Test
    fun finishedUpToDateDownloadIsNotQueuedAgain() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        db.downloadDao().upsert(queuedRow())
        manager.runDownload(key, 0)
        manager.enqueue(key, PinnedBy.BOOK)
        assertEquals("DONE", db.downloadDao().get("acc1", 1)!!.state)
    }
}
