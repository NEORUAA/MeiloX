package com.ljyh.mei.source

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.playback.PlaybackArtistSnapshot
import com.ljyh.mei.playback.PlaybackItemSnapshot
import com.ljyh.mei.playback.PlaybackPersistence
import com.ljyh.mei.playback.PlaybackSnapshot
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated on-device fixtures never open the user's database or alter its queue. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SourceCompatibilityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun v18MigrationKeepsRelationshipsFilesAndLegacyIdentities(): Unit = runBlocking {
        val name = "source-migration-${UUID.randomUUID()}"
        val offline = File.createTempFile("source-migration-", ".audio", context.cacheDir)
        val bytes = byteArrayOf(1, 3, 5, 7)
        offline.writeBytes(bytes)
        var migrated: AppDatabase? = null
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val schema = instrumentation.context.assets.open("source-compatibility/v18.sql")
                    .bufferedReader().use { it.readText() }
                schema.lineSequence().filter { it.isNotBlank() && !it.startsWith("--") }
                    .forEach { old.execSQL(it) }
                old.setForeignKeyConstraintsEnabled(true)
                for ((id, type) in listOf("42" to "DOWNLOAD", "local_42" to "LOCAL", "-77" to "LOCAL", "-99" to "STREAM")) {
                    old.execSQL("""INSERT INTO song(id,title,artist,album,cover,duration,path,sourceType,fileSize,addedAt,updatedAt)
                        VALUES(?, 'Title', '["Artist"]', 'Album', '', 180000, ?, ?, 4, 1, 2)""",
                        arrayOf(id, offline.absolutePath, type))
                }
                old.execSQL("""INSERT INTO playlist(id,title,cover,author,authorName,authorAvatar,count,type,createdAt,updatedAt,playCount,lastPlayTime,localPlayCount)
                    VALUES('playlist_1','Playlist','','1','Author','',1,'USER',1,2,3,4,5)""")
                old.execSQL("INSERT INTO playlist_song_cross_ref VALUES('playlist_1','42',7,8)")
                old.execSQL("INSERT INTO playback_history VALUES(11,'42',123)")
                old.execSQL("INSERT INTO playback_count VALUES('42',9,456)")
                old.execSQL("INSERT INTO `like` VALUES('42')")
                old.execSQL("INSERT INTO qqSong VALUES('42','qq-lyric-id','Title','Artist','Album',180000)")
                old.execSQL("INSERT INTO cached_lyric VALUES('42','[00:00]Lyric',NULL,0,0,'Netease','LRC',99)")
                old.execSQL("""INSERT INTO download_task VALUES('42','https://audio.example/old','old.flac','flac','COMPLETED',100,'Title','Artist','Album','','lossless','request-1',1,2)""")
                old.execSQL("INSERT INTO albums VALUES(100,'Album','',1,1)")
                old.execSQL("INSERT INTO artists VALUES(200,'Artist',NULL)")
                old.execSQL("INSERT INTO album_artist_cross_ref VALUES(100,200)")
                old.execSQL("INSERT INTO color VALUES('cover',123)")
                old.version = 18
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_18_19).build().also { migrated = it }
            val sqlite = db.openHelper.writableDatabase
            assertEquals(19, sqlite.version)
            val downloaded = checkNotNull(db.songDao().getById("42"))
            assertEquals("netease", downloaded.providerId)
            assertEquals("42", downloaded.providerTrackId)
            assertEquals(offline.absolutePath, downloaded.path)
            assertEquals(TrackRef("local", "local_42"), db.songDao().getById("local_42")!!.toMediaMetadata().trackRef)
            assertEquals(TrackRef("local", "local_77"), db.songDao().getById("-77")!!.toMediaMetadata().trackRef)
            assertEquals(TrackRef("netease", "-99"), db.songDao().getById("-99")!!.toMediaMetadata().trackRef)
            assertEquals(listOf("42"), db.playlistSongCrossRefDao().getSongIdsByPlaylist("playlist_1").first())
            assertEquals(5, db.playlistDao().getPlaylist("playlist_1")!!.localPlayCount)
            assertEquals("local", db.playlistDao().getPlaylist("playlist_1")!!.providerId)
            assertEquals("playlist_1", db.playlistDao().getPlaylist("playlist_1")!!.providerPlaylistId)
            assertEquals(9, db.downloadDao().playbackCount("42"))
            assertNotNull(db.likeDao().getLike("42"))
            assertEquals("qq-lyric-id", db.qqSongDao().getSong("42").first()!!.qid)
            assertEquals("[00:00]Lyric", db.cachedLyricDao().get("42").first()!!.content)
            val download = checkNotNull(db.downloadDao().getBySongId("42"))
            assertEquals("request-1", download.requestId)
            assertEquals("netease", download.actualAudioProviderId)
            assertNull(download.actualQuality)
            assertNull(download.resolvedTrackRef)
            assertEquals("lossless", download.quality)
            assertEquals(1, db.AlbumsDao().getAlbumWithArtists(100).artists.size)
            assertEquals(123, db.colorDao().getColor("cover")!!.color)
            assertEquals(11L, db.historyDao().getHistory().first().single().historyId)
            db.historyDao().addSongToHistory(downloaded.copy(title = "Refreshed"), playedAt = 789)
            db.songDao().insertSong(downloaded.copy(title = "Updated"))
            db.songDao().insertSongs(listOf(downloaded.copy(title = "Batch update")))
            assertEquals(2, db.historyDao().getHistory().first().size)
            assertEquals(offline.absolutePath, db.songDao().getById("42")!!.path)
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            sqlite.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            assertArrayEquals(bytes, offline.readBytes())
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
            offline.delete()
        }
    }

    @Test fun oldAndNewSnapshotsRestoreThroughRealMedia3Items() {
        val gson = Gson()
        val persistence = PlaybackPersistence(context)
        val legacy = gson.fromJson("""{"schemaVersion":1,"items":[
            {"mediaId":"42","title":"Netease"},
            {"mediaId":"local_42","title":"Local","isLocal":true},
            {"mediaId":"-99","title":"Podcast","isPodcast":true}] }""", PlaybackSnapshot::class.java)
        val oldItems = persistence.restoreItems(legacy)
        assertEquals(listOf(TrackRef("netease", "42"), TrackRef("local", "local_42"), TrackRef("netease", "-99")),
            oldItems.map { it.metadata!!.trackRef })
        assertEquals(listOf("42", "local_42", "-99"), oldItems.map { it.mediaId })

        val ref = TrackRef("demo", "42:歌曲/音轨")
        val snapshot = PlaybackSnapshot(items = listOf(PlaybackItemSnapshot(
            mediaId = TrackRefCodec.mediaId(ref), title = "External", trackRef = TrackRefCodec.encode(ref),
            artists = listOf(PlaybackArtistSnapshot(name = "Artist", ref = ArtistRef("demo", "artist:string"))),
            albumTitle = "Album", albumRef = AlbumRef("demo", "album/string"),
        )))
        val restored = persistence.restoreItems(gson.fromJson(gson.toJson(snapshot), PlaybackSnapshot::class.java)).single()
        assertEquals(ref, restored.metadata!!.trackRef)
        assertEquals(0L, restored.metadata!!.id)
        assertEquals(0L, restored.metadata!!.artists.single().id)
        assertEquals(snapshot.items.single().albumRef, restored.metadata!!.album.ref)
        assertEquals(ref, TrackRefCodec.decode(restored.mediaId))
        assertEquals(TrackRefCodec.storageKey(ref), restored.localConfiguration!!.customCacheKey)
        var captured: PlaybackSnapshot? = null
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItem(restored.metadata!!.toMediaItem())
                captured = persistence.capture(player, "Source compatibility", false)
            } finally { player.release() }
        }
        assertEquals(TrackRefCodec.encode(ref), captured!!.items.single().trackRef)
        assertEquals(snapshot.items.single().albumRef, captured!!.items.single().albumRef)
        assertEquals(snapshot.items.single().artists.single().ref, captured!!.items.single().artists.single().ref)
    }
}
