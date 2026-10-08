package com.somecatcode.ebookreader.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Schema migrations. The old database is built from the exported schema (`app/schemas/.../1.json`);
 * opening it with Room runs the migration and validates the result against the current entities
 * (tables, columns, indices, foreign keys), which fails on any mismatch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.somecatcode.ebookreader.data.db.AppDatabase/$version.json"
        return listOf(File(relative), File("app/$relative")).first { it.exists() }
    }

    /** Creates the database of [version] exactly as Room did, from the exported schema JSON. */
    private fun createFromSchema(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
            fill(db)
        }
    }

    @Test
    fun migrate2To3AddsSharingColumnsKeepsBooksAndResetsTheSyncCursor() {
        createFromSchema(2) { db ->
            db.execSQL(
                "INSERT INTO account (id, serverUrl, loginName, userId, displayName, serverVersion, appVersion, lastSyncCursor, lastSyncAt, createdAt) " +
                    "VALUES ('acc1', 'https://cloud.example.org', 'alice', 'alice', 'Alice', '31', '0.9.0', 'b:5|p:3|a:9', 1000, 1)",
            )
            db.execSQL(
                "INSERT INTO book (accountId, fileId, format, path, size, title, authors, readStatus, hasCover, mtime, addedAt, updatedAt, " +
                    "editable, downloadable, overrides, hasSidecar, deleted) " +
                    "VALUES ('acc1', 7, 'epub', '/Books/a.epub', 10, 'A', '[]', 'unread', 0, 1, 2, 3, 1, 1, '[]', 0, 0)",
            )
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val account = room.accountDao().get("acc1")!! // opening runs + validates the migration against the entities
                assertNull("the next sync must be a full one so the new columns get filled", account.lastSyncCursor)
                val book = room.bookDao().get("acc1", 7)!!
                assertEquals("/Books/a.epub", book.path)
                assertEquals(false, book.shared)
                assertEquals(false, book.sharedOut)
                assertNull(book.owner)
                room.bookDao().upsertAll(listOf(book.copy(shared = true, owner = "bob", sharedOut = false)))
                assertEquals("bob", room.bookDao().get("acc1", 7)!!.owner)
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migrate1To2AddsAnnotationsAndResetsTheSyncCursor() {
        createFromSchema(1) { db ->
            db.execSQL(
                "INSERT INTO account (id, serverUrl, loginName, userId, displayName, serverVersion, appVersion, lastSyncCursor, lastSyncAt, createdAt) " +
                    "VALUES ('acc1', 'https://cloud.example.org', 'alice', 'alice', 'Alice', '31', NULL, 'b:5|p:3|a:9', 1000, 1)",
            )
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val account = room.accountDao().get("acc1")!! // opening runs + validates the migration
                assertNull("annotations already synced by the server must be delivered again", account.lastSyncCursor)
                assertEquals(1000L, account.lastSyncAt)
                room.annotationDao().upsert(
                    AnnotationEntity("acc1", "u", 1, "highlight", "{\"href\":\"a\"}", "t", null, "yellow", 1, 0, 1, dirty = true),
                )
                assertEquals(1, room.annotationDao().dirty("acc1").size)
                room.accountDao().delete("acc1")
                assertNull("account removal cascades", room.annotationDao().get("acc1", "u"))
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
