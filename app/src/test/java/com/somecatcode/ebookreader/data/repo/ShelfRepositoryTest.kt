package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.SmartQueryDto
import com.somecatcode.ebookreader.data.bookJson
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ShelfRepositoryTest : DbTest() {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private lateinit var repo: ShelfRepositoryImpl

    private fun shelfJson(id: Long, name: String, type: String = "manual", count: Int = 0, sortOrder: Int = 0, query: String = "null") =
        """{"id":$id,"name":"$name","type":"$type","query":$query,"count":$count,"coverFileIds":[],"sortOrder":$sortOrder,"createdAt":1,"updatedAt":2}"""

    @Before
    fun setUp() {
        repo = ShelfRepositoryImpl(db, StaticApiFactory(server.ebookApi()))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun createSmartShelfSendsQueryWithoutShelfTerms() = runBlocking {
        dispatcher.on("POST", "/shelves") { ocs(shelfJson(5, "SF", "smart", query = """{"include":["genre:Sci-Fi"]}""")) }
        val key = repo.create("acc1", " SF ", SmartQueryDto(include = listOf("genre:Sci-Fi", "shelf:3"), match = "any"))

        assertEquals(ShelfKey("acc1", 5), key)
        val body = dispatcher.requests.single().body.readUtf8()
        assertTrue(body, body.contains("\"name\":\"SF\"") && body.contains("\"type\":\"smart\""))
        assertTrue(body, body.contains("genre:Sci-Fi") && !body.contains("shelf:3") && body.contains("\"match\":\"any\""))
        assertEquals("SF", db.shelfDao().get("acc1", 5)!!.name)
    }

    @Test
    fun addAndRemoveBooksReloadMembership() = runBlocking {
        db.shelfDao().upsertShelves(listOf(shelfEntity(7, "Fav")))
        var members = "[]"
        dispatcher.on("POST", "/shelves/7/books") { members = "[${bookJson(1)}]"; ocs("""{"added":1,"skipped":0}""") }
        dispatcher.on("DELETE", "/shelves/7/books") { members = "[]"; ocs("""{"removed":1}""") }
        dispatcher.on("GET", "/shelves") { ocs("""{"shelves":[${shelfJson(7, "Fav", count = if (members == "[]") 0 else 1)}]}""") }
        dispatcher.on("GET", "/books") { ocs("""{"books":$members,"total":${if (members == "[]") 0 else 1}}""") }

        repo.addBooks(ShelfKey("acc1", 7), listOf(1))
        assertEquals(listOf(1L), db.shelfDao().fileIds("acc1", 7))
        assertEquals(1, db.shelfDao().get("acc1", 7)!!.count)
        val add = dispatcher.requests.first { it.method == "POST" }
        assertEquals("""{"fileIds":[1]}""", add.body.readUtf8())

        repo.removeBooks(ShelfKey("acc1", 7), listOf(1))
        assertEquals(emptyList<Long>(), db.shelfDao().fileIds("acc1", 7))
        assertEquals("""{"fileIds":[1]}""", dispatcher.requests.first { it.method == "DELETE" }.body.readUtf8())
    }

    @Test
    fun renameMoveAndDelete() = runBlocking {
        db.shelfDao().upsertShelves(listOf(shelfEntity(1, "A", 0), shelfEntity(2, "B", 1)))
        dispatcher.on("PATCH", "/shelves/1") { req ->
            val body = req.body.readUtf8()
            if (body.contains("name")) ocs(shelfJson(1, "A2")) else ocs(shelfJson(1, "A", sortOrder = 1))
        }
        dispatcher.on("PATCH", "/shelves/2") { ocs(shelfJson(2, "B", sortOrder = 0)) }
        dispatcher.on("DELETE", "/shelves/2") { ocs("""{"deleted":2}""") }

        repo.rename(ShelfKey("acc1", 1), "A2")
        assertEquals("A2", db.shelfDao().get("acc1", 1)!!.name)

        repo.move(ShelfKey("acc1", 2), -1)
        assertEquals(0, db.shelfDao().get("acc1", 2)!!.sortOrder)
        assertEquals(1, db.shelfDao().get("acc1", 1)!!.sortOrder)

        repo.delete(ShelfKey("acc1", 2))
        assertNull(db.shelfDao().get("acc1", 2))
    }

    @Test
    fun duplicateNameSurfacesAsBadRequest() = runBlocking {
        dispatcher.on("POST", "/shelves") { ocsError(400, "duplicate") }
        try {
            repo.create("acc1", "Fav")
            fail("expected BadRequest")
        } catch (e: ApiException.BadRequest) {
            // expected
        }
    }

    private fun shelfEntity(id: Long, name: String, sortOrder: Int = 0) =
        com.somecatcode.ebookreader.data.db.ShelfEntity("acc1", id, name, "manual", null, 0, "[]", sortOrder, 0, 0)
}
