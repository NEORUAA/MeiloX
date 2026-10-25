package com.ljyh.mei.playback

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlymeLyricTimelineTest {
    private val song = Song(
        id = "1",
        lyrics = listOf(
            RichLyricLine(begin = 1_000, end = 2_000, text = "First line"),
            RichLyricLine(begin = 3_000, end = 5_000, text = "Next line"),
        ),
    )

    @Test fun selectsCurrentLineAndStopsAtEndOfEachLine() {
        assertNull(song.flymeLyricAt(999, true))
        assertEquals("First line", song.flymeLyricAt(1_000, true))
        assertEquals("First line", song.flymeLyricAt(1_999, true))
        assertNull(song.flymeLyricAt(2_000, true))
        assertNull(song.flymeLyricAt(2_999, true))
        assertEquals("Next line", song.flymeLyricAt(3_000, true))
        assertNull(song.flymeLyricAt(5_000, true))
    }

    @Test fun aBackwardSeekSelectsTheEarlierLine() {
        assertEquals("Next line", song.flymeLyricAt(4_000, true))
        assertEquals("First line", song.flymeLyricAt(1_200, true))
    }

    @Test fun pausedOrDisabledPlaybackDoesNotRetainOldLyrics() {
        assertNull(song.flymeLyricAt(1_200, false))
        assertNull(song.copy(lyrics = emptyList()).flymeLyricAt(1_200, true))
    }
}
