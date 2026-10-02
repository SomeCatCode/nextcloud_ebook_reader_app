package com.somecatcode.ebookreader.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Base for Robolectric tests with an in-memory Room database and one account `acc1`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
abstract class DbTest {

    lateinit var db: AppDatabase

    @Before
    fun openDatabase() {
        db = newDatabase(ApplicationProvider.getApplicationContext())
        kotlinx.coroutines.runBlocking { db.addAccount() }
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    fun book(
        fileId: Long,
        accountId: String = "acc1",
        title: String? = "Book $fileId",
        series: String? = null,
        seriesIndex: Double? = null,
        readStatus: String = "unread",
        mtime: Long = 1,
        size: Long = 1000,
        downloadable: Boolean = true,
        deleted: Boolean = false,
    ) = BookEntity(
        accountId = accountId, fileId = fileId, format = "epub", path = "/Books/$fileId.epub", size = size, title = title,
        authors = "[\"Author\"]", series = series, seriesIndex = seriesIndex, description = null, language = "de",
        publisher = null, isbn = null, publishedAt = null, rating = null, readStatus = readStatus, hasCover = false,
        coverEtag = null, fileEtag = null, mtime = mtime, addedAt = fileId, updatedAt = 1, editable = true,
        downloadable = downloadable, overrides = "[]", hasSidecar = false, deleted = deleted,
    )
}
