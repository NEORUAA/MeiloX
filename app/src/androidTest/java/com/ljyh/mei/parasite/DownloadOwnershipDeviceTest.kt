package com.ljyh.mei.parasite

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.di.AppDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses private test databases only, never the host's queue, account, or media store. */
@RunWith(AndroidJUnit4::class)
class DownloadOwnershipDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lateProgressCannotOverwriteAPausedReplacedOrDeletedRequest() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.downloadDao()
            val old = DownloadTask("1", requestId = UUID.randomUUID().toString(), ownerId = 17, quality = "lossless")
            dao.insert(old)
            assertEquals(1, dao.updateOwnedProgress("1", old.requestId, 17, DownloadStatus.PAUSED, 12, 1))
            assertEquals(0, dao.updateOwnedProgress("1", old.requestId, 17, DownloadStatus.FAILED, 0, 2))
            assertEquals(0, dao.updateOwnedFileInfo("1", old.requestId, 17, "old", "mp3"))
            val replacement = old.copy(requestId = UUID.randomUUID().toString(), ownerId = 18)
            dao.insert(replacement)
            assertEquals(0, dao.updateOwnedProgress("1", old.requestId, 17, DownloadStatus.COMPLETED, 100, 3))
            assertEquals(0, dao.deleteOwned("1", old.requestId, 17))
            assertNull(dao.getOwned("1", replacement.requestId, 17))
            assertEquals(replacement, dao.getBySongId("1"))
            assertEquals(1, dao.deleteOwned("1", replacement.requestId, 18))
            assertEquals(0, dao.updateOwnedProgress("1", replacement.requestId, 18, DownloadStatus.COMPLETED, 100, 4))
        } finally { db.close() }
    }

    @Test fun migrationClearsOldGrantsWithoutAssigningAnAccountOrDeletingCompletedRows() = runBlocking {
        val name = "meilox-download-migration-test-${UUID.randomUUID()}"
        val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        initial.openHelper.writableDatabase
        initial.close()
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.execSQL("DROP TABLE download_task")
                sqlite.execSQL("""CREATE TABLE download_task (
                    songId TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, fileName TEXT NOT NULL,
                    fileType TEXT NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL,
                    songTitle TEXT NOT NULL, songArtist TEXT NOT NULL, songAlbum TEXT NOT NULL,
                    songCover TEXT NOT NULL, quality TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL
                )""")
                for ((song, state) in listOf("1" to "DOWNLOADING", "2" to "COMPLETED")) {
                    sqlite.execSQL("INSERT INTO download_task VALUES (?, 'https://invalid.test/expired', '', '', ?, 0, '', '', '', '', 'lossless', 1, 1)", arrayOf(song, state))
                }
                sqlite.version = 18
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_18_19).build()
            try {
                val old = checkNotNull(migrated.downloadDao().getBySongId("1"))
                assertEquals(DownloadStatus.FAILED, old.status)
                assertEquals("", old.url)
                assertEquals("", old.requestId)
                assertEquals(0L, old.ownerId)
                assertEquals("Music/Mei", old.downloadPath)
                assertEquals(DownloadStatus.COMPLETED, migrated.downloadDao().getBySongId("2")?.status)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
