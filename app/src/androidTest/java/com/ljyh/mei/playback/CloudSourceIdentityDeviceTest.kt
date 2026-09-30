package com.ljyh.mei.playback

import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SONG_SOURCE_EXTRA
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.sourceKey
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.room.DownloadStatus
import com.ljyh.mei.data.model.room.DownloadTask
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.repository.DownloadRepository
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.ui.screen.main.library.component.toMediaMetadataOrNull
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private Room and metadata fixtures only; no account, media publication or server mutations. */
@RunWith(AndroidJUnit4::class)
class CloudSourceIdentityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = SongSourceIdentity(999, 88, 7, 17)
    private fun item() = MediaMetadata(17, "Synthetic cloud", "", listOf(MediaMetadata.Artist(1, "Test")),
        1000, MediaMetadata.Album(1, "Test"), source = source).toMediaItem()

    @Test fun loaderKeyAndPlatformExtrasKeepTheEntryAndFileOwnerSeparate() {
        val item = item()
        assertEquals("17", item.mediaId)
        assertEquals("17", item.localConfiguration?.uri.toString())
        assertEquals(source.key, item.sourceKey)
        assertEquals(source.key, item.mediaMetadata.extras?.getString(SONG_SOURCE_EXTRA))
        val remote = MediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).build()
        assertNull(remote.localConfiguration)
        assertEquals(source.key, remote.sourceKey)
        assertThrows(IllegalArgumentException::class.java) { item.metadata!!.copy(id = 18).toMediaItem() }
        assertThrows(IllegalArgumentException::class.java) { item.buildUpon().setCustomCacheKey("17").build().sourceKey }
    }

    @Test fun capturedSnapshotsAndSerializedColdRestoresKeepCloudSourceAffinity() {
        val item = item()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getMediaItemCount" -> 1
                "getMediaItemAt" -> item
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> 321L
                "getRepeatMode" -> Player.REPEAT_MODE_ALL
                "getShuffleModeEnabled", "getPlayWhenReady" -> false
                "getCurrentTimeline" -> Timeline.EMPTY
                else -> error("Unexpected player method")
            }
        } as Player
        val persistence = PlaybackPersistence(context)
        val snapshot = persistence.capture(player, "cloud", false)
        assertEquals(3, snapshot.schemaVersion)
        assertEquals(source.key, snapshot.items.single().sourceKey)
        val encoded = Gson().toJson(snapshot)
        val decoded = Gson().fromJson(encoded, PlaybackSnapshot::class.java)
        val restored = persistence.restoreItems(decoded).single()
        assertEquals(source, restored.metadata?.source)
        assertEquals(source.key, restored.sourceKey)
        assertEquals(321L, decoded.positionMs)
        assertEquals("17", restored.mediaId)
    }

    @Test fun olderCatalogSnapshotsRemainUsableAndInvalidCloudSnapshotsFailClosed() {
        val persistence = PlaybackPersistence(context)
        for (version in 1..2) {
            val old = Gson().fromJson("""{"schemaVersion":$version,"items":[{"mediaId":"17","title":"Old","artists":[],"albumTitle":"","artworkUri":""}]}""", PlaybackSnapshot::class.java)
            val restored = persistence.restoreItems(old).single()
            assertNull(restored.metadata?.source)
            assertEquals("17", restored.sourceKey)
        }
        for (key in listOf("meilox-cloud-v1:18:999:88:7", "meilox-cloud-v1:17:999:0:7", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) {
                persistence.restoreItems(PlaybackSnapshot(items = listOf(PlaybackItemSnapshot("17", sourceKey = key))))
            }
        }
    }

    @Test fun downloadedCloudBytesNeedTheMatchingCompletedSourceNotJustAnEntryId() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("cloud-source-test-", ".wav", context.cacheDir)
        var account = SessionIdentity(7, true, false)
        val sessions = SessionStore().apply { bind { account }; setRecoveryRequired(true) }
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, _, _ ->
            error("Closed local fixture must not reach the network")
        } as ApiService
        val provider = MediaUriProvider(PlaybackUrlResolver(api, sessions), SongRepository(db.songDao()), sessions,
            DownloadRepository(db.downloadDao(), db.songDao()))
        val task = DownloadTask("17", requestId = UUID.randomUUID().toString(), ownerId = 7,
            quality = "standard", status = DownloadStatus.COMPLETED, sourceKey = source.key)
        try {
            db.songDao().insertSong(Song("17", "Synthetic", emptyList(), "", "", 1, path = file.path))
            assertTrue(runCatching { provider.resolveMediaSource(source.key, "standard", sessions.snapshot()) }
                .exceptionOrNull() is SessionChangedException)
            db.downloadDao().insert(task.copy(sourceKey = source.copy(cloudOwnerId = 89).key))
            assertTrue(runCatching { provider.resolveMediaSource(source.key, "standard", sessions.snapshot()) }
                .exceptionOrNull() is SessionChangedException)
            db.downloadDao().insert(task)
            assertEquals(Uri.fromFile(file), provider.resolveMediaSource(source.key, "standard", sessions.snapshot()).uri)
            account = SessionIdentity(8, true, false)
            assertTrue(runCatching { provider.resolveMediaSource(source.key, "standard", sessions.snapshot()) }
                .exceptionOrNull() is SessionChangedException)
        } finally { db.close(); file.delete() }
    }

    @Test fun durableCloudTasksCannotResumeWithForgedAffinityOrAnUnrelatedLogicalEntry() {
        val id = UUID.randomUUID()
        val task = DownloadTask("17", requestId = id.toString(), ownerId = 7, quality = "standard", sourceKey = source.key)
        task.requireExecutable(id, 7)
        for (bad in listOf(task.copy(sourceKey = source.copy(accountId = 8).key),
            task.copy(sourceKey = source.copy(entryId = 18).key), task.copy(sourceKey = "meilox-cloud-v1:broken"))) {
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { bad.requireExecutable(id, 7) }
        }
    }

    @Test fun automaticCloudCachingRejectsOldAccountMetadataBeforeRecordingPlayback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val sessions = SessionStore().apply { bind { SessionIdentity(8, true, false) } }
        try {
            val controller = AutomaticCacheController(context, db, sessions)
            assertTrue(runCatching { controller.recordPlayback(item(), sessions.snapshot()) }
                .exceptionOrNull() is SessionChangedException)
            assertNull(db.downloadDao().playbackCount("17"))
            assertNull(db.downloadDao().playbackCount(source.key))
        } finally { db.close() }
    }

    @Test fun downloadedLibraryRowsKeepTheirPersistedSourceAcrossQueueActions() {
        val task = DownloadTask("17", ownerId = 7, sourceKey = source.key)
        val metadata = requireNotNull(task.toMediaMetadataOrNull(null))
        assertEquals(source, metadata.source)
        assertEquals(source.key, metadata.toMediaItem().sourceKey)
        assertNull(task.copy(ownerId = 8).toMediaMetadataOrNull(null))
        assertNull(task.copy(sourceKey = source.copy(entryId = 18).key).toMediaMetadataOrNull(null))
        assertNull(task.copy(sourceKey = "meilox-cloud-v1:invalid").toMediaMetadataOrNull(null))
        assertNull(requireNotNull(task.copy(sourceKey = "").toMediaMetadataOrNull(null)).source)
    }

    @Test fun v20UpgradePreservesAllTasksWithoutInventingACloudSourceOwner() = migrateV20(false)

    @Test fun failedV20SourceUpgradeRollsBackAndCanBeRetried() = migrateV20(true)

    private fun migrateV20(injectFailure: Boolean) = runBlocking {
        val name = "cloud-source-migration-${UUID.randomUUID()}"
        val path = context.getDatabasePath(name)
        val tasks = DownloadStatus.entries.mapIndexed { index, status -> DownloadTask(
            (index + 1).toString(), status = status, requestId = UUID.randomUUID().toString(), ownerId = 7,
            songTitle = "Synthetic", quality = "standard", playlistName = "Original", downloadPath = "Music/Original",
        ) }
        try {
            val seed = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try { seed.downloadDao().insertAll(tasks) } finally { seed.close() }
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("ALTER TABLE download_task DROP COLUMN sourceKey")
                it.version = 20
            }
            if (injectFailure) {
                val broken = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(object : Migration(20, 21) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        AppDatabase.MIGRATION_20_21.migrate(db)
                        throw IllegalStateException("Synthetic migration failure")
                    }
                }).build()
                try { assertThrows(IllegalStateException::class.java) { broken.openHelper.writableDatabase } }
                finally { broken.close() }
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                    assertEquals(20, sqlite.version)
                    sqlite.rawQuery("PRAGMA table_info(download_task)", null).use { columns ->
                        assertFalse(buildList { while (columns.moveToNext()) add(columns.getString(1)) }.contains("sourceKey"))
                    }
                }
            }
            repeat(2) {
                val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(AppDatabase.MIGRATION_20_21).build()
                try {
                    assertEquals(21, migrated.openHelper.writableDatabase.version)
                    tasks.forEach { task -> assertEquals(task, migrated.downloadDao().getBySongId(task.songId)) }
                } finally { migrated.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
