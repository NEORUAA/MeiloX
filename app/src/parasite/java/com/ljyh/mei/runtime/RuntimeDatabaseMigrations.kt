package com.ljyh.mei.runtime

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object RuntimeDatabaseMigrations {
    val downloadOwnership = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE download_task ADD COLUMN requestId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE download_task ADD COLUMN ownerId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE download_task ADD COLUMN playlistName TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE download_task ADD COLUMN downloadPath TEXT NOT NULL DEFAULT 'Music/Mei'")
            // Unowned legacy work must not inherit whichever account next opens the host.
            db.execSQL("UPDATE download_task SET url = '', status = CASE WHEN status IN ('PENDING', 'DOWNLOADING') THEN 'FAILED' ELSE status END")
        }
    }
}
