package com.ljyh.mei.playback

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.sourceKey
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.screen.history.ListeningHistoryEntry
import com.ljyh.mei.ui.screen.history.ownedLocalHistoryEntries
import com.ljyh.mei.ui.screen.history.toHistoryQueueEntry
import com.ljyh.mei.ui.screen.history.toListeningHistoryEntries
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private Room/Media3 fixtures only; no real graph, resource transfer or account writes. */
@RunWith(AndroidJUnit4::class)
class CloudHistoryOwnershipDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = SongSourceIdentity(999, 88, 7, 17)
    private fun item(source: SongSourceIdentity = this.source) = MediaMetadata(
        source.entryId, "Fixture", "", listOf(MediaMetadata.Artist(1, "Test")), 123_871,
        MediaMetadata.Album(1, "Test"), source = source,
    ).toMediaItem()
    private fun song(source: SongSourceIdentity = this.source) = item(source).toLocalHistorySong(source.key, null, 100)

    @Test fun storageAndRoomMappingKeepExactCloudSourceAndMillisecondDuration() = runBlocking {
        val f = Fixture()
        try {
            val item = item()
            assertEquals("17", item.mediaId)
            assertEquals(source.key, item.localHistoryStorageIdOrNull())
            f.repository.addToHistory(song(), 100, f.sessions.snapshot())
            val entry = f.repository.getHistory().toListeningHistoryEntries().single()
            assertEquals(17L, entry.song.id)
            assertEquals(source, entry.song.source)
            assertEquals(123_871L, entry.song.duration)
            assertEquals(source.key, entry.toHistoryQueueEntry().second!!.sourceKey)
        } finally { f.close() }
    }

    @Test fun differentPrivateFilesNeverOverwriteAnOrdinaryOrDownloadedSongWithTheSameEntry() = runBlocking {
        val f = Fixture()
        val download = Song("17", "Published fixture", emptyList(), "", "", 123, path = "fixture://untouched")
        try {
            f.db.songDao().insertSong(download)
            val sources = listOf(source, source.copy(cloudOwnerId = 89), source.copy(songId = 1000))
            sources.forEachIndexed { index, source -> f.repository.addToHistory(song(source), index.toLong(), f.sessions.snapshot()) }
            assertEquals(download, f.db.songDao().getSong("17").first())
            assertEquals(sources.toSet(), f.repository.getHistory().toListeningHistoryEntries().map { it.song.source }.toSet())
            assertEquals(3, f.repository.getHistory().size)
        } finally { f.close() }
    }

    @Test fun foreignUncapturedAndStaleOwnersCannotWriteCloudHistory() = runBlocking {
        val f = Fixture()
        try {
            val owner = f.sessions.snapshot()
            assertTrue(runCatching { f.repository.addToHistory(song(source.copy(accountId = 8)), 100, owner) }
                .exceptionOrNull() is SessionChangedException)
            assertTrue(runCatching { f.repository.addToHistory(song(), 100) }.exceptionOrNull() is IllegalArgumentException)
            f.sessions.invalidate()
            assertTrue(runCatching { f.repository.addToHistory(song(), 100, owner) }.exceptionOrNull() is SessionChangedException)
            assertTrue(f.repository.getHistory().isEmpty())
            assertNull(f.db.songDao().getSong(source.key).first())
        } finally { f.close() }
    }

    @Test fun accountChangeAfterInsertRollsBackBothMetadataAndCascadedHistoryReplacement() = runBlocking {
        val f = Fixture()
        try {
            val owner = f.sessions.snapshot()
            f.repository.addToHistory(song(), 100, owner)
            val before = f.repository.getHistory().single()
            val switchAt = f.reads + 3
            f.onRead = { if (f.reads == switchAt) f.identity = SessionIdentity(8, true, false) }
            assertTrue(runCatching { f.repository.addToHistory(song().copy(title = "Rejected replacement"), 200, owner) }
                .exceptionOrNull() is SessionChangedException)
            assertEquals(before, f.repository.getHistory().single())
            assertEquals(before.song, f.db.songDao().getSong(source.key).first())
        } finally { f.close() }
    }

    @Test fun cancellationAfterInsertionRollsBackThePrivateTransaction() = runBlocking {
        val f = Fixture()
        try {
            f.repository.addToHistory(song(), 100, f.sessions.snapshot())
            val before = f.repository.getHistory().single()
            var guards = 0
            assertTrue(runCatching {
                f.db.historyDao().addSongToHistory(song().copy(title = "Cancelled"), 200) {
                    if (++guards == 2) throw CancellationException("Closed fixture")
                }
            }.exceptionOrNull() is CancellationException)
            assertEquals(2, guards)
            assertEquals(before, f.repository.getHistory().single())
        } finally { f.close() }
    }

    @Test fun ownedLocalHistoryRemainsAvailableDuringRecoveryWithoutAnyNetworkFallback() = runBlocking {
        val f = Fixture()
        try {
            f.sessions.setRecoveryRequired(true)
            f.repository.addToHistory(song(), 100, f.sessions.snapshot())
            val entries = f.repository.getHistory().toListeningHistoryEntries()
            assertEquals(entries, ownedLocalHistoryEntries(entries, f.identity))
            assertEquals(source.key, entries.single().toHistoryQueueEntry().second!!.sourceKey)
        } finally { f.close() }
    }

    @Test fun legacyCatalogAndLocalRoomMappingKeepTheirOriginalUnitsAndIds() {
        val catalog = Song("42", "Fixture", emptyList(), "", "", 180).toMediaMetadata()
        val local = Song("local_42", "Fixture", emptyList(), "", "", 180, sourceType = SourceType.LOCAL).toMediaMetadata()
        assertEquals(42L, catalog.id)
        assertEquals(180_000L, catalog.duration)
        assertNull(catalog.source)
        assertEquals(42L, local.id)
        assertEquals(180_000L, local.duration)
        assertTrue(local.isLocal)
        assertNull(local.source)
    }

    @Test fun malformedCloudRowsAreNotHashedOrDeletedAndCanNeverReplayAsCatalogSongs() = runBlocking<Unit> {
        val f = Fixture()
        try {
            for (key in listOf("meilox-cloud-v1:17:999:88:0", "meilox-cloud-v1:17:0999:88:7", " ${source.key}", "meilox-cloud-v2:17:999:88:7")) {
                f.db.historyDao().addSongToHistory(Song(key, "Malformed", emptyList(), "", "", 1), 100)
            }
            assertEquals(4, f.repository.getHistory().size)
            assertTrue(f.repository.getHistory().toListeningHistoryEntries().isEmpty())
            assertEquals(4, f.repository.getHistory().size)
            assertThrows(IllegalArgumentException::class.java) { song().copy(sourceType = SourceType.LOCAL).toMediaMetadata() }
        } finally { f.close() }
    }

    @Test fun knownCloudHistoryMetadataAvoidsCatalogHydrationAndKeepsDistinctQueueSources() = runBlocking {
        val first = ListeningHistoryEntry("first", item().metadata!!, 200)
        val second = ListeningHistoryEntry("second", item(source.copy(cloudOwnerId = 89)).metadata!!, 100)
        val status = ListQueue("history", "Fixture", listOf(first, second).map(ListeningHistoryEntry::toHistoryQueueEntry)).getInitialStatus()
        assertEquals(listOf("17", "17"), status.ids.map { it.first })
        assertTrue(status.ids.all { isPlayableMediaItem(it.second!!) })
        assertEquals(listOf(source.key, source.copy(cloudOwnerId = 89).key), status.ids.map { it.second!!.sourceKey })
    }

    @Test fun ordinaryAndLocalHistoryStillUseBaselineDetailHydration() {
        val catalog = item().metadata!!.copy(source = null)
        for (metadata in listOf(catalog, catalog.copy(isLocal = true))) {
            val entry = ListeningHistoryEntry("legacy", metadata, 100).toHistoryQueueEntry()
            assertEquals("17", entry.first)
            assertNull(entry.second)
        }
    }

    @Test fun cloudRowsAndAffinitySurviveDatabaseReopenWithoutChangingSchemaOrForeignData() = runBlocking {
        val name = "cloud-history-fixture-${UUID.randomUUID()}"
        val sessions = SessionStore().apply { bind { SessionIdentity(7, true, false) } }
        try {
            var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try { HistoryRepository(db.historyDao(), db.songDao(), sessions).addToHistory(song(), 100, sessions.snapshot()) }
            finally { db.close() }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals(21, db.openHelper.writableDatabase.version)
                val repository = HistoryRepository(db.historyDao(), db.songDao(), sessions)
                val entries = repository.getHistory().toListeningHistoryEntries()
                assertEquals(source, entries.single().song.source)
                assertEquals(123_871L, entries.single().song.duration)
                assertTrue(ownedLocalHistoryEntries(entries, SessionIdentity(8, true, false)).isEmpty())
                assertEquals(entries, ownedLocalHistoryEntries(entries, SessionIdentity(7, true, false)))
                assertEquals(1, repository.getHistory().size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun malformedOrPodcastMediaItemsCannotBecomeAnUnownedCloudHistoryRecord() {
        val valid = item()
        assertThrows(IllegalArgumentException::class.java) { valid.buildUpon().setCustomCacheKey("17").build().localHistoryStorageIdOrNull() }
        assertNull(valid.metadata!!.copy(isPodcast = true).toMediaItem().localHistoryStorageIdOrNull())
        assertThrows(IllegalArgumentException::class.java) { valid.metadata!!.copy(isLocal = true).toMediaItem().localHistoryStorageIdOrNull() }
    }

    private inner class Fixture : AutoCloseable {
        var identity = SessionIdentity(7, true, false)
        var reads = 0
        var onRead: () -> Unit = {}
        val sessions = SessionStore().apply { bind { reads++; onRead(); identity } }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = HistoryRepository(db.historyDao(), db.songDao(), sessions)
        override fun close() { db.close() }
    }
}
