package com.ljyh.mei.source

import com.google.gson.Gson
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.playback.PlaybackItemSnapshot
import com.ljyh.mei.playback.PlaybackSnapshot
import com.ljyh.mei.source.api.*
import org.junit.Assert.*
import org.junit.Test

class SourceIdentityTest {
    @Test fun transportIdentityIsReversibleAndDistinctAcrossProviders() {
        val ids = listOf("42", "a:b/c", "空 格 🎵", "AbC", "abc", "00042", "9223372036854775808", "\u0000\n")
        val refs = listOf("netease", "third:party/来源").flatMap { provider -> ids.map { TrackRef(provider, it) } }
        assertEquals(refs.size, refs.map(TrackRefCodec::encode).distinct().size)
        refs.forEach { assertEquals(it, TrackRefCodec.decode(TrackRefCodec.encode(it))) }
        assertNotEquals(TrackRefCodec.storageKey(TrackRef("netease", "42")), TrackRefCodec.storageKey(TrackRef("demo", "42")))
        assertEquals("42", TrackRefCodec.mediaId(TrackRef("netease", "42")))
        assertEquals(TrackRef("netease", "9223372036854775808"),
            TrackRefCodec.decode(TrackRefCodec.mediaId(TrackRef("netease", "9223372036854775808"))))
    }

    @Test fun legacyIdentityDistinguishesLocalFilesFromPodcastIds() {
        assertEquals(TrackRef("netease", "42"), TrackRefCodec.decode("42"))
        assertEquals(TrackRef("local", "local_42"), TrackRefCodec.decode("local_42"))
        assertEquals(TrackRef("local", "local_42"), TrackRefCodec.decode("-42"))
        assertEquals(TrackRef("netease", "-42"), TrackRefCodec.decode("-42", isPodcast = true))
        assertEquals(TrackRef("local", "local_42"), TrackRefCodec.decode("42", isLocal = true))
        assertEquals("local_42", TrackRefCodec.storageKey(TrackRef("local", "local_42")))
        assertEquals("-42", TrackRefCodec.mediaId(TrackRef("local", "local_42")))
        val podcast = TrackRef("netease", "-42")
        assertNotEquals("-42", TrackRefCodec.mediaId(podcast))
        assertEquals(podcast, TrackRefCodec.decode(TrackRefCodec.mediaId(podcast)))
        assertEquals("-42", TrackRefCodec.storageKey(podcast))
        assertNull(TrackRefCodec.decode(Long.MIN_VALUE.toString()))
    }

    @Test fun malformedOrUnknownTransportVersionsAreRejected() {
        listOf("", "other:42", "mei:track:v2:QQ:NDI", "mei:track:v1:QQ:NDI:extra", "mei:track:v1:QQ:NDI=", "mei:track:v1:_w:NDI").forEach {
            assertNull(it, TrackRefCodec.decode(it))
        }
    }

    @Test fun externalMetadataNeverUsesNumericHashesAsContentIdentity() {
        val original = Track(
            TrackRef("demo", "曲目:42/音轨"), "Title",
            listOf(SourceArtist("Artist", ArtistRef("demo", "artist:string"))),
            SourceAlbum("Album", AlbumRef("demo", "album/string")), 180_000, isrc = "TEST00000001", version = "Live", explicit = true,
        )
        val metadata = original.toMediaMetadata()
        assertEquals(0L, metadata.id)
        assertEquals(0L, metadata.album.id)
        assertEquals(0L, metadata.artists.single().id)
        assertEquals(original, metadata.toSourceTrack())
        val stored = Song(TrackRefCodec.storageKey(original.ref), "Title", listOf("Artist"), "Album", "", 180_000)
        assertEquals(original.ref, stored.toMediaMetadata().trackRef)
        assertEquals(0L, stored.toMediaMetadata().id)
        val local = Song("local_42", "Local", listOf("Artist"), "Album", "", 1, sourceType = SourceType.LOCAL)
        assertEquals(-42L, local.toMediaMetadata().id)
        assertEquals(TrackRef("local", "local_42"), local.toMediaMetadata().trackRef)
        val podcast = MediaMetadata(-42, "Podcast", "", emptyList(), 1, MediaMetadata.Album(1, "Radio"), isPodcast = true)
        assertEquals(TrackRef("netease", "-42"), podcast.trackRef)
    }

    @Test fun versionThreeSnapshotPreservesRefsAndLegacySnapshotsRemainReadable() {
        val ref = TrackRef("demo", "same:42/歌曲")
        val gson = Gson()
        val snapshot = PlaybackSnapshot(items = listOf(PlaybackItemSnapshot(
            TrackRefCodec.mediaId(ref), trackRef = TrackRefCodec.encode(ref), albumRef = AlbumRef("demo", "album:one"),
        )))
        assertEquals(3, snapshot.schemaVersion)
        assertEquals(snapshot, gson.fromJson(gson.toJson(snapshot), PlaybackSnapshot::class.java))
        listOf(1, 2).forEach { version ->
            val old = gson.fromJson("""{"schemaVersion":$version,"items":[{"mediaId":"42"}]}""", PlaybackSnapshot::class.java)
            assertEquals("42", old.items.single().mediaId)
            assertNull(old.items.single().trackRef)
        }
    }
}
