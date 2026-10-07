package com.somecatcode.ebookreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single Room database (all accounts share it, `accountId` is part of every key and removing
 * an account cascades). Schema JSON is exported to `app/schemas` and checked in; any schema change
 * needs a version bump plus a migration in `Migrations.kt` (no destructive fallback once released).
 */
@Database(
    entities = [
        AccountEntity::class,
        BookEntity::class,
        BookTagEntity::class,
        ProgressEntity::class,
        ShelfEntity::class,
        ShelfBookEntity::class,
        DownloadEntity::class,
        PendingEditEntity::class,
        AnnotationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun bookDao(): BookDao
    abstract fun bookTagDao(): BookTagDao
    abstract fun progressDao(): ProgressDao
    abstract fun shelfDao(): ShelfDao
    abstract fun downloadDao(): DownloadDao
    abstract fun pendingEditDao(): PendingEditDao
    abstract fun annotationDao(): AnnotationDao

    companion object {
        const val FILE_NAME = "ebookreader.db"
    }
}
