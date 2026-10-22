package com.ljyh.mei.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHistorySourceTest {
    @Test
    fun validSongIdUsesTrackSource() {
        assertEquals(
            PlaybackHistorySource(123L, "track"),
            resolvePlaybackHistorySource(songId = 123L),
        )
    }

    @Test
    fun invalidSongIdDoesNotEmitAZeroSource() {
        assertNull(resolvePlaybackHistorySource(songId = 0L))
        assertNull(resolvePlaybackHistorySource(songId = -1L))
    }

    @Test
    fun localHistoryRetainsTheStoredFileIdentityWithoutChangingCatalogOrPodcastIds() {
        assertEquals("local_42", playbackHistoryStorageId("-42", isLocal = true))
        assertEquals("local_42", playbackHistoryStorageId("local_42", isLocal = true))
        assertEquals("42", playbackHistoryStorageId("42", isLocal = false))
        assertEquals("-42", playbackHistoryStorageId("-42", isLocal = false))
        assertEquals(Long.MIN_VALUE.toString(), playbackHistoryStorageId(Long.MIN_VALUE.toString(), isLocal = true))
    }
}
