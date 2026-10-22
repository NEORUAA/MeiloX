package com.ljyh.mei.ui.screen.local

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.model.toMediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryTest {
    @Test
    fun localMediaIdsDoNotCollideWithDownloadedCatalogSongsAndDurationStaysInMilliseconds() {
        val local = song(id = "local_42").toMediaMetadata()
        val downloaded = song(id = "42", source = SourceType.DOWNLOAD).toMediaMetadata()

        assertEquals(-42L, local.id)
        assertNotEquals(downloaded.id, local.id)
        assertEquals(180_000L, local.duration)
        assertEquals(180_000L, downloaded.duration)
        assertTrue(local.isLocal)
    }

    @Test
    fun rescanKeepsMatchedMetadataAndDownloadIdentityWhileRefreshingFileFacts() {
        val downloaded = song(id = "999", source = SourceType.DOWNLOAD).copy(
            title = "Selected catalog title", cover = "https://cover", addedAt = 10L,
        )
        val scanned = song(id = "local_42").copy(
            title = "File tag title", duration = 181_000, fileSize = 4_000, updatedAt = 20L,
            bitrate = 320_000, sampleRate = 48_000,
        )
        val merged = mergeScannedSong(downloaded, scanned)

        assertEquals("999", merged.id)
        assertEquals(SourceType.DOWNLOAD, merged.sourceType)
        assertEquals("Selected catalog title", merged.title)
        assertEquals("https://cover", merged.cover)
        assertEquals(10L, merged.addedAt)
        assertEquals(181_000L, merged.duration)
        assertEquals(4_000L, merged.fileSize)
        assertEquals(320_000, merged.bitrate)
        assertEquals(48_000, merged.sampleRate)
        assertEquals(20L, merged.updatedAt)
        assertEquals(scanned, mergeScannedSong(null, scanned))
    }

    @Test
    fun confirmedMatchUpdatesDisplayMetadataWithoutReplacingTheLocalFile() {
        val original = song().copy(fileHash = "hash", bitrate = 320_000, folderPath = "/Music")
        val candidate = track(id = 900, title = "Matched", artist = "Singer", duration = 190_000)
        val matched = original.withMatchedMetadata(candidate)

        assertEquals("Matched", matched.title)
        assertEquals(listOf("Singer"), matched.artist)
        assertEquals(original.id, matched.id)
        assertEquals(original.path, matched.path)
        assertEquals(original.duration, matched.duration)
        assertEquals(original.sourceType, matched.sourceType)
        assertEquals(original.fileHash, matched.fileHash)
        assertEquals(original.bitrate, matched.bitrate)
        assertEquals(original.folderPath, matched.folderPath)
    }

    @Test
    fun matchingRanksTitleArtistAndMeasuredDurationAndDeduplicatesCatalogIds() {
        val local = song().copy(title = "Song！", artist = listOf("Artist"))
        val wrongArtist = track(1, "Song", "Other", 180_000)
        val wrongVersion = track(2, "Song", "Artist", 250_000)
        val best = track(3, "Ｓｏｎｇ", "Artist", 180_500)

        assertEquals(
            listOf(3L, 2L, 1L),
            rankLocalMatches(local, listOf(wrongArtist, wrongVersion, best, best)).map { it.id },
        )
    }

    private fun song(id: String = "local_42", source: SourceType = SourceType.LOCAL) = Song(
        id = id, title = "Song", artist = listOf("Artist"), album = "Album", cover = "",
        duration = 180_000, path = "content://media/external/audio/media/42", sourceType = source,
    )

    private fun track(id: Long, title: String, artist: String, duration: Long) = MediaMetadata(
        id = id, title = title, artists = listOf(MediaMetadata.Artist(1, artist)),
        album = MediaMetadata.Album(1, "Album"), coverUrl = "https://cover", duration = duration,
    )
}
