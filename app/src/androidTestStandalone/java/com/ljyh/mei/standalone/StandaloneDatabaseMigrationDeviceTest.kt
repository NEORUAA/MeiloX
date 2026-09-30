package com.ljyh.mei.standalone

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.PlaybackHistory
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic upgrades only; never opens the app database or schedules downloads. */
@RunWith(AndroidJUnit4::class)
class StandaloneDatabaseMigrationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacy = LegacyStandaloneDatabaseFixture

    @Test fun populatedV17RetainsEveryLegacyColumnAndRemainsReadableAfterReopen() = runBlocking {
        withFixture(populated = true) { fixture ->
            repeat(2) {
                withMigratedDatabase(fixture) { db ->
                    assertMigration(fixture, db)
                    assertLegacyReads(fixture, db)
                }
            }
        }
    }

    @Test fun emptyV17UpgradesWithoutInventingAccountOrDownloadOwnership() = runBlocking {
        withFixture(populated = false) { fixture ->
            withMigratedDatabase(fixture) { db ->
                assertMigration(fixture, db)
                assertTrue(db.downloadDao().getAll().first().isEmpty())
                assertTrue(db.playlistDao().getAccountPlaylists("17").first().isEmpty())
            }
        }
    }

    @Test fun failedUpgradeRollsBackAllStepsAndCanRetryWithoutDataLoss() = runBlocking {
        withFixture(populated = true) { fixture ->
            val failure = object : Migration(19, 20) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    AppDatabase.MIGRATION_19_20.migrate(db)
                    throw InjectedMigrationFailure()
                }
            }
            val broken = database(fixture, failure)
            try {
                assertThrows(InjectedMigrationFailure::class.java) { broken.openHelper.writableDatabase }
            } finally {
                broken.close()
            }
            SQLiteDatabase.openDatabase(fixture.path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(17, db.version)
                assertEquals(fixture.before, snapshot { db.rawQuery(it, null) })
                db.rawQuery("PRAGMA table_info(download_task)", null).use { columns ->
                    val names = buildList { while (columns.moveToNext()) add(columns.getString(1)) }
                    assertFalse(names.contains("requestId"))
                }
                db.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('account_playlist', 'download_artifact')", null).use {
                    assertEquals(0, it.count)
                }
            }
            withMigratedDatabase(fixture) { db ->
                assertMigration(fixture, db)
                assertLegacyReads(fixture, db)
                db.historyDao().insertHistory(PlaybackHistory(songId = legacy.completedId, playedAt = 1700000003000L))
                assertEquals(43L, db.historyDao().getHistory().first().first().historyId)
            }
        }
    }

    private fun database(fixture: Fixture, finalMigration: Migration = AppDatabase.MIGRATION_19_20) =
        Room.databaseBuilder(context, AppDatabase::class.java, fixture.name)
            .addMigrations(AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19, finalMigration)
            .build()

    private suspend fun withMigratedDatabase(fixture: Fixture, block: suspend (AppDatabase) -> Unit) {
        val db = database(fixture)
        try {
            db.openHelper.writableDatabase // Room validates the complete v20 schema here.
            block(db)
        } finally {
            db.close()
        }
    }

    private fun assertMigration(fixture: Fixture, db: AppDatabase) {
        val sqlite = db.openHelper.writableDatabase
        assertEquals(20, sqlite.version)
        assertEquals(fixture.before, snapshot(fixture.before.mapValues { it.value.columns }) { sqlite.query(it) })
        for (table in listOf("account_playlist", "download_artifact")) {
            sqlite.query("SELECT COUNT(*) FROM `$table`").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
        sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        sqlite.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        assertArrayEquals(fixture.bytes, fixture.mediaFile.readBytes())
    }

    private suspend fun assertLegacyReads(fixture: Fixture, db: AppDatabase) {
        val song = requireNotNull(db.songDao().getSong(legacy.completedId).first())
        assertEquals(listOf("Artist A", "Artist B"), song.artist)
        assertEquals(SourceType.DOWNLOAD, song.sourceType)
        assertEquals(fixture.mediaFile.path, song.path)
        assertEquals(fixture.bytes.size.toLong(), song.fileSize)
        assertEquals("fixture-hash", song.fileHash)
        assertEquals(SourceType.LOCAL, db.songDao().getSong(legacy.localId).first()?.sourceType)
        val stream = requireNotNull(db.songDao().getSong(legacy.streamId).first())
        assertEquals(SourceType.STREAM, stream.sourceType)
        assertNull(stream.path)
        assertNull(stream.bitrate)
        assertEquals(legacy.contentUri, db.songDao().getSong(legacy.contentId).first()?.path)
        assertEquals(setOf(legacy.completedId, legacy.contentId), db.downloadDao().getPlayableSongs().first().map { it.id }.toSet())
        val tasks = db.downloadDao().getAll().first()
        assertEquals(6, tasks.size)
        assertEquals(legacy.states.toSet(), tasks.map { it.status.name }.toSet())
        assertEquals(2, db.downloadDao().activeCount().first())
        for (task in tasks) {
            assertEquals("", task.requestId)
            assertEquals(0L, task.ownerId)
            assertEquals("", task.playlistName)
            assertEquals("Music/Mei", task.downloadPath)
            assertNull(db.downloadDao().getOwned(task.songId, "new-request", 17))
        }
        assertEquals(9, db.downloadDao().playbackCount(legacy.completedId))
        assertEquals(legacy.completedId, db.likeDao().getLike(legacy.completedId)?.id)
        assertEquals("fixture-mid", db.qqSongDao().getSong(legacy.completedId).first()?.qid)
        assertEquals(-13732729, db.colorDao().getColor("https://invalid.test/cover")?.color)
        val lyrics = requireNotNull(db.cachedLyricDao().get(legacy.completedId).first())
        assertEquals("[00:01.00]Fixture", lyrics.content)
        assertNull(lyrics.translation)
        assertFalse(lyrics.isVerbatim)
        assertTrue(requireNotNull(db.cachedLyricDao().get(legacy.streamId).first()).isVerbatim)
        val history = db.historyDao().getHistory().first()
        assertEquals(listOf(42L, 41L), history.map { it.historyId })
        assertEquals(listOf(legacy.streamId, legacy.completedId), history.map { it.song.id })
        val album = db.AlbumsDao().getAlbumWithArtists(170)
        assertEquals("Fixture album", album.album.name)
        assertEquals(setOf(171L, 172L), album.artists.map { it.artistId }.toSet())
        for (id in listOf("local-list", "remote-list")) {
            val playlist = requireNotNull(db.playlistDao().getPlaylist(id))
            assertEquals(123L, playlist.playCount)
            assertEquals(7, playlist.localPlayCount)
            assertEquals(1700000002000L, playlist.lastPlayTime)
            assertEquals(listOf(legacy.localId, legacy.completedId), db.playlistSongCrossRefDao().getSongIdsByPlaylist(id).first())
        }
        // Legacy remote rows survive, but no account membership can be inferred from them.
        for (accountId in listOf("17", "18")) {
            assertEquals(listOf("local-list"), db.playlistDao().getAccountPlaylists(accountId).first().map { it.playlist.id })
        }
    }

    private suspend fun withFixture(populated: Boolean, block: suspend (Fixture) -> Unit) = withTimeout(30_000) {
        assertEquals("com.neoruaa.meilox.standalone.debug", context.packageName)
        val name = "standalone-v17-fixture-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name)
        val directory = File(context.filesDir, name)
        val media = File(directory, "Preserved fixture.flac")
        val bytes = "Synthetic file bytes, not playable audio".toByteArray()
        try {
            check(directory.mkdir())
            media.writeBytes(bytes)
            path.parentFile?.mkdirs()
            val before = SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                legacy.create(db)
                if (populated) legacy.populate(db, media)
                assertEquals(17, db.version)
                snapshot { db.rawQuery(it, null) }
            }
            if (populated) {
                before.forEach { (table, data) -> assertTrue("Unseeded legacy table: $table", data.rows.isNotEmpty()) }
            }
            block(Fixture(name, path, media, bytes, before))
        } finally {
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
        assertFalse(path.exists())
        assertFalse(directory.exists())
    }

    private fun snapshot(
        originalColumns: Map<String, List<String>> = emptyMap(),
        query: (String) -> Cursor,
    ): Map<String, TableSnapshot> = legacy.tables.associateWith { table ->
        val columns = originalColumns[table]
        val projection = columns?.joinToString(",") { "`$it`" } ?: "*"
        query("SELECT $projection FROM `$table` ORDER BY rowid").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index ->
                        val type = cursor.getType(index)
                        Cell(type, when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            else -> error("Unexpected blob in frozen v17 fixture")
                        })
                    })
                }
            }
            TableSnapshot(cursor.columnNames.toList(), rows)
        }
    }

    private data class Cell(val type: Int, val value: Any?)
    private data class TableSnapshot(val columns: List<String>, val rows: List<List<Cell>>)
    private data class Fixture(
        val name: String, val path: File, val mediaFile: File, val bytes: ByteArray,
        val before: Map<String, TableSnapshot>,
    )
    private class InjectedMigrationFailure : RuntimeException("Synthetic migration failure")
}
