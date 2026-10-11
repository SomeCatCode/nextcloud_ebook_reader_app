package com.somecatcode.ebookreader.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2: table `annotation` (highlights, notes, bookmarks).
 *
 * The stored `/sync` cursors are reset: servers with annotations already advanced the annotation
 * part of the cursor while version 1 ignored the `annotations` list, so a delta sync would never
 * deliver the existing annotations. The next sync is a full one (books are upserted, nothing is lost).
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `annotation` (
                `accountId` TEXT NOT NULL, `uuid` TEXT NOT NULL, `fileId` INTEGER NOT NULL, `type` TEXT NOT NULL,
                `locator` TEXT NOT NULL, `text` TEXT, `note` TEXT, `color` TEXT, `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL, `clientUpdatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL,
                `dirty` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `uuid`),
                FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotation_accountId_fileId` ON `annotation` (`accountId`, `fileId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotation_accountId_dirty` ON `annotation` (`accountId`, `dirty`)")
        db.execSQL("UPDATE account SET lastSyncCursor = NULL")
    }
}

/**
 * Version 3: sharing fields of a book (`shared`, `owner`, `sharedOut`, server 0.9.0/0.10.0) for the
 * "Shared" view and the share badges. The stored `/sync` cursors are reset so the next sync is a full
 * one and fills the new columns for the existing books (books are upserted, nothing is lost).
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `book` ADD COLUMN `shared` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `book` ADD COLUMN `owner` TEXT")
        db.execSQL("ALTER TABLE `book` ADD COLUMN `sharedOut` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE account SET lastSyncCursor = NULL")
    }
}

/** All migrations, registered by the database builder. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
