package com.ljyh.mei.parasite

import android.content.Context
import androidx.room.Room
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.sourceKey
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.playback.PlaybackPersistence
import com.ljyh.mei.playback.localHistoryStorageIdOrNull
import com.ljyh.mei.playback.toLocalHistorySong
import com.ljyh.mei.ui.screen.history.toHistoryQueueEntry
import com.ljyh.mei.ui.screen.history.toListeningHistoryEntries
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal object HostCloudHistoryProbe {
    /** Separate in-memory Room/session fixtures; never rebind the real graph or call any SDK. */
    fun closed(context: Context) = runBlocking {
        var identity = SessionIdentity(7, true, false)
        var reads = 0
        var switchAt = Int.MAX_VALUE
        val sessions = SessionStore().apply { bind {
            if (++reads == switchAt) identity = SessionIdentity(8, true, false)
            identity
        } }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = SongSourceIdentity(999, 88, 7, 17)
        val item = MediaMetadata(17, "Fixture", "", emptyList(), 123_871, MediaMetadata.Album(1, "Test"), source = source).toMediaItem()
        try {
            check(item.localHistoryStorageIdOrNull() == source.key)
            val repository = HistoryRepository(db.historyDao(), db.songDao(), sessions)
            val owner = sessions.snapshot()
            val song = item.toLocalHistorySong(source.key, null, 100)
            val catalog = Song("17", "Untouched", emptyList(), "", "", 123, path = "fixture://unchanged")
            db.songDao().insertSong(catalog)
            repository.addToHistory(song, 100, owner)
            val before = repository.getHistory().single()
            val replay = repository.getHistory().toListeningHistoryEntries().single()
            check(replay.song.source == source && replay.song.duration == 123_871L)
            check(replay.toHistoryQueueEntry().second!!.sourceKey == source.key)
            switchAt = reads + 3
            check(runCatching { repository.addToHistory(song.copy(title = "Rejected"), 200, owner) }
                .exceptionOrNull() is SessionChangedException)
            check(repository.getHistory().single() == before)
            check(db.songDao().getSong("17").first() == catalog)
            HostRuntimeProbe.report("cloud_history_closed_passed source_roundtrip=true exact_duration=true " +
                "account_guard=true transaction_rollback=true catalog_preserved=true synthetic_only=true official_mutations=0")
        } finally { db.close() }
    }

    /** Read-only inspection of a real, already played cloud source in the module namespace. */
    fun read(context: Context) = runBlocking {
        val sessions = AppGraph.component.sessions()
        val owner = sessions.snapshot()
        val snapshot = checkNotNull(PlaybackPersistence(context).load())
        val selected = checkNotNull(snapshot.items.getOrNull(snapshot.currentIndex))
        val source = SongSourceIdentity.fromKey(checkNotNull(selected.sourceKey))
        check(source.isCloud)
        source.requireAccount(owner.identity)
        val rows = AppDatabase.getDatabase(context).historyDao().getHistory().first()
        val row = checkNotNull(rows.firstOrNull { it.song.id == source.key })
        val replay = listOf(row).toListeningHistoryEntries().single()
        check(replay.song.source == source && replay.toHistoryQueueEntry().second!!.sourceKey == source.key)
        check(replay.song.duration > 0 && replay.song.duration == selected.durationMs)
        sessions.requireCurrent(owner)
        HostRuntimeProbe.report("cloud_history_read_passed source_persisted=true replay_source_owned=true " +
            "duration_ms=${replay.song.duration} queue_size=${snapshot.items.size} index=${snapshot.currentIndex} " +
            "account_unchanged=true no_mutation=true no_sdk_events=true")
    }
}
