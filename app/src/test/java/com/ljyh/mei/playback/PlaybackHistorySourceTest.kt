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
}
