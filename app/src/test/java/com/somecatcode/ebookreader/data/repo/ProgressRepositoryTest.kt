package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.FakeSettings
import com.somecatcode.ebookreader.data.RecordingScheduler
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.Locations
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.progressJson
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProgressRepositoryTest : DbTest() {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private val scheduler = RecordingScheduler()
    private var now = 1_000L
    private lateinit var repo: ProgressRepositoryImpl
    private val key = BookKey("acc1", 5)

    @Before
    fun setUp() {
        repo = ProgressRepositoryImpl(
            db, StaticApiFactory(server.ebookApi()), FakeSettings(), scheduler,
            defaultDeviceName = { "Pixel" }, clock = { now },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun loc(href: String, p: Double = 0.1) = Locator(href, locations = Locations(progression = p, totalProgression = p))

    @Test
    fun saveLocalIsDirtyImmediatelyAndSchedulesUpload() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.2), 0.2)
        val row = db.progressDao().get("acc1", 5)!!
        assertTrue(row.dirty)
        assertEquals(1_000L, row.clientUpdatedAt)
        assertEquals("Pixel", row.device)
        assertEquals(listOf("acc1"), scheduler.scheduled)
        assertEquals("c1.xhtml", repo.progress(key).first()!!.locator.href)
    }

    @Test
    fun timestampNeverGoesBackwardsForTheSameBook() = runBlocking {
        repo.saveLocal(key, loc("a"), 0.1)
        now = 500 // clock jumped back
        repo.saveLocal(key, loc("b"), 0.2)
        assertEquals(1_001L, db.progressDao().get("acc1", 5)!!.clientUpdatedAt)
    }

    @Test
    fun pushDirtyUploadsBatchAndClearsFlag() = runBlocking {
        dispatcher.on("POST", "/progress/batch") {
            ocs("""{"results":[{"fileId":5,"status":"ok","progress":${progressJson(5, clientUpdatedAt = 1000, updatedAt = 1234)}}]}""")
        }
        repo.saveLocal(key, loc("c1.xhtml", 0.2), 0.2)
        repo.pushDirty("acc1")
        val row = db.progressDao().get("acc1", 5)!!
        assertFalse(row.dirty)
        assertEquals(1234L, row.updatedAt)
        val items = ApiJson.parseToJsonElement(dispatcher.requests.single().body.readUtf8()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
    }

    @Test
    fun pushDirtyConflictTakesTheNewerServerPosition() = runBlocking {
        dispatcher.on("POST", "/progress/batch") {
            ocs("""{"results":[{"fileId":5,"status":"conflict","progress":${progressJson(5, "c9.xhtml", 0.9, clientUpdatedAt = 5000, updatedAt = 5001)}}]}""")
        }
        repo.saveLocal(key, loc("c1.xhtml", 0.2), 0.2)
        repo.pushDirty("acc1")
        val row = db.progressDao().get("acc1", 5)!!
        assertFalse(row.dirty)
        assertEquals(0.9, row.percentage, 0.0)
        assertEquals(5000L, row.clientUpdatedAt)
    }

    @Test
    fun pushDirtyKeepsRowsThatChangedWhileUploading() = runBlocking {
        dispatcher.on("POST", "/progress/batch") {
            // the user turns a page while the request is in flight
            kotlinx.coroutines.runBlocking {
                now = 2_000
                repo.saveLocal(key, loc("c2.xhtml", 0.5), 0.5)
            }
            ocs("""{"results":[{"fileId":5,"status":"ok","progress":${progressJson(5, clientUpdatedAt = 1000, updatedAt = 1234)}}]}""")
        }
        repo.saveLocal(key, loc("c1.xhtml", 0.2), 0.2)
        repo.pushDirty("acc1")
        val row = db.progressDao().get("acc1", 5)!!
        assertTrue("newer local change stays dirty", row.dirty)
        assertEquals(0.5, row.percentage, 0.0)
    }

    @Test
    fun pushDirtyWhileOfflineKeepsEverythingDirty() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml"), 0.1)
        server.shutdown()
        repo.pushDirty("acc1")
        assertTrue(db.progressDao().get("acc1", 5)!!.dirty)
    }

    @Test
    fun checkRemoteReturnsConflictWhenServerIsNewerAndDiffers() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.10), 0.10) // clientUpdatedAt 1000
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c7.xhtml", 0.70, clientUpdatedAt = 9000, updatedAt = 9001, device = "Kobo")) }
        val conflict = repo.checkRemote(key)!!
        assertEquals("Kobo", conflict.remoteDevice)
        assertEquals(0.70, conflict.remotePercentage, 0.0)
        assertEquals("c1.xhtml", conflict.localLocator.href)
    }

    @Test
    fun checkRemoteIgnoresOlderOrSimilarRemote() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.10), 0.10)
        // remote older than local: local wins
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c7.xhtml", 0.70, clientUpdatedAt = 10, updatedAt = 11)) }
        assertNull(repo.checkRemote(key))
        assertEquals("c1.xhtml", repo.progress(key).first()!!.locator.href)
    }

    @Test
    fun checkRemoteSimilarNewerPositionIsAdoptedSilentlyWhenNotDirty() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.100), 0.100)
        db.progressDao().upsert(db.progressDao().get("acc1", 5)!!.copy(dirty = false))
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c1.xhtml", 0.105, clientUpdatedAt = 9000, updatedAt = 9001)) }
        assertNull(repo.checkRemote(key))
        assertEquals(9000L, db.progressDao().get("acc1", 5)!!.clientUpdatedAt)
    }

    @Test
    fun checkRemoteWithoutLocalAdoptsServerPosition() = runBlocking {
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c3.xhtml", 0.3)) }
        assertNull(repo.checkRemote(key))
        val row = db.progressDao().get("acc1", 5)!!
        assertEquals(0.3, row.percentage, 0.0)
        assertFalse(row.dirty)
    }

    @Test
    fun checkRemoteOfflineOrNeverOpenedReturnsNull() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml"), 0.1)
        dispatcher.on("GET", "/progress/5") { ocs("null") }
        assertNull(repo.checkRemote(key))
        server.shutdown()
        assertNull(repo.checkRemote(key))
    }

    @Test
    fun acceptRemoteStoresServerPositionClean() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.1), 0.1)
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c7.xhtml", 0.7, clientUpdatedAt = 9000, updatedAt = 9001, device = "Kobo")) }
        val conflict = repo.checkRemote(key)!!
        repo.acceptRemote(conflict)
        val row = db.progressDao().get("acc1", 5)!!
        assertFalse(row.dirty)
        assertEquals(0.7, row.percentage, 0.0)
        assertEquals("Kobo", row.device)
        // after accepting, the same server state is no longer a conflict
        assertNull(repo.checkRemote(key))
    }

    @Test
    fun keepLocalUploadsWithFreshTimestamp() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.1), 0.1)
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c7.xhtml", 0.7, clientUpdatedAt = 9000, updatedAt = 9001)) }
        val conflict = repo.checkRemote(key)!!
        dispatcher.on("PUT", "/progress/5") { req ->
            val body = ApiJson.parseToJsonElement(req.body.readUtf8()).jsonObject
            val at = body["clientUpdatedAt"].toString().toLong()
            ocs(progressJson(5, "c1.xhtml", 0.1, clientUpdatedAt = at, updatedAt = at + 1))
        }
        now = 20_000
        repo.keepLocal(conflict)
        val row = db.progressDao().get("acc1", 5)!!
        assertFalse(row.dirty)
        assertTrue("timestamp beats the server's", row.clientUpdatedAt > 9001)
        assertNotNull(dispatcher.requests.last { it.method == "PUT" })
    }

    @Test
    fun keepLocalFailureLeavesRowDirty() = runBlocking {
        repo.saveLocal(key, loc("c1.xhtml", 0.1), 0.1)
        dispatcher.on("GET", "/progress/5") { ocs(progressJson(5, "c7.xhtml", 0.7, clientUpdatedAt = 9000, updatedAt = 9001)) }
        val conflict = repo.checkRemote(key)!!
        dispatcher.on("PUT", "/progress/5") { ocsError(503, "later") }
        repo.keepLocal(conflict)
        assertTrue(db.progressDao().get("acc1", 5)!!.dirty)
    }
}
