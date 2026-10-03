package com.ljyh.mei.ui.component.player.component

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricScrollPolicyTest {
    private val lines = listOf(
        SyncedLine("First", null, 10_000, 15_000),
        SyncedLine("Second", null, 15_000, 20_000),
        SyncedLine("After the interlude", null, 30_000, 35_000),
    )

    @Test
    fun openingDuringPlaybackStartsAtTheCurrentLine() {
        assertEquals(2, lyricFocusLineIndex(lines, 32_000))
    }

    @Test
    fun openingBeforeTheFirstLineStartsAtTheIntro() {
        assertEquals(0, lyricFocusLineIndex(lines, 5_000))
    }

    @Test
    fun openingAtALineBoundaryUsesTheNewLine() {
        assertEquals(1, lyricFocusLineIndex(lines, 15_000))
    }

    @Test
    fun openingDuringAnInterludeUsesTheUpcomingLine() {
        assertEquals(2, lyricFocusLineIndex(lines, 20_000))
        assertEquals(2, lyricFocusLineIndex(lines, 25_000))
    }

    @Test
    fun openingAfterTheLyricsEndStaysOnTheLastLine() {
        assertEquals(2, lyricFocusLineIndex(lines, 40_000))
    }

    @Test
    fun overlappingVocalsKeepTheFirstActiveLine() {
        val duet = listOf(
            SyncedLine("First singer", null, 10_000, 20_000),
            SyncedLine("Second singer", null, 15_000, 25_000),
        )
        assertEquals(0, lyricFocusLineIndex(duet, 18_000))
    }

    @Test
    fun accompanimentExtendsTheMainLinesFocus() {
        val accompaniment = KaraokeLine.AccompanimentKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 15_000,
            end = 25_000,
        )
        val main = KaraokeLine.MainKaraokeLine(
            syllables = emptyList(),
            translation = null,
            alignment = KaraokeAlignment.Start,
            start = 10_000,
            end = 20_000,
            accompanimentLines = listOf(accompaniment),
        )
        assertEquals(0, lyricFocusLineIndex(listOf(main, accompaniment, lines.last()), 23_000))
        assertEquals(
            false,
            shouldSnapLyricScroll(listOf(main, accompaniment, lines.last()), 0, 2, 2),
        )
    }

    @Test
    fun jumpsWithinOneScreenKeepTheirAnimation() {
        assertEquals(false, shouldSnapLyricScroll(lines, 0, 1, 2))
        assertEquals(false, shouldSnapLyricScroll(lines, 0, 2, 3))
    }

    @Test
    fun theFirstLineOutsideTheScreenAlreadyRequiresASnap() {
        assertEquals(true, shouldSnapLyricScroll(lines, 0, 2, 2))
        assertEquals(true, shouldSnapLyricScroll(lines, 2, 0, 2))
    }

    @Test
    fun jumpsBeyondOneScreenSnapInBothDirections() {
        assertEquals(true, shouldSnapLyricScroll(lines, 0, 2, 1))
        assertEquals(true, shouldSnapLyricScroll(lines, 2, 0, 1))
    }

    @Test
    fun scrollingDoesNotSnapUntilTheViewportHasBeenMeasured() {
        assertEquals(false, shouldSnapLyricScroll(lines, 0, 2, 0))
    }

    @Test
    fun emptyLyricsHaveASafeInitialIndex() {
        assertEquals(0, lyricFocusLineIndex(emptyList(), 32_000))
    }
}
