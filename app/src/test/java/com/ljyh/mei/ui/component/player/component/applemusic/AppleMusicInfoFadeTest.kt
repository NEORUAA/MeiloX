package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppleMusicInfoFadeTest {
    @Test fun controlsFadeLeavesTheFullLyricViewportUnmaskedAfterDismissal() {
        for (height in listOf(400f, 684f, 1_000f)) {
            val trackTop = height * 0.61f
            val shown = appleMusicLyricsControlsFadeRange(height, 0f, 0f, trackTop)
            val hidden = appleMusicLyricsControlsFadeRange(height, 0.6f, 0f, trackTop)
            assertEquals(trackTop - height * 0.15f, shown.start, 0.0001f)
            assertEquals(trackTop, shown.endInclusive, 0.0001f)
            assertEquals(height, hidden.start, 0.0001f)
            assertTrue(hidden.endInclusive > height)
        }
    }

    @Test fun controlsFadeStaysScreenAlignedWhileLyricsMoveIntoPlace() {
        for (shift in listOf(0f, 0.3f, 0.6f)) {
            val settled = appleMusicLyricsControlsFadeRange(684f, shift, 0f, 450f)
            for (entryOffset in listOf(0f, 80f, 200f, 500f)) {
                val moving = appleMusicLyricsControlsFadeRange(684f, shift, entryOffset, 450f)
                assertEquals(settled.start, moving.start + entryOffset, 0.0001f)
                assertEquals(settled.endInclusive, moving.endInclusive + entryOffset, 0.0001f)
            }
        }
    }

    @Test fun controlsFadeEndsAtTheTrackWithoutReservingExtraSpace() {
        for (height in listOf(260f, 684f, 1_000f)) {
            for (trackTop in listOf(0f, height * 0.25f, height * 0.9f)) {
                for (entryOffset in listOf(0f, 80f, 500f)) {
                    val range = appleMusicLyricsControlsFadeRange(height, 0f, entryOffset, trackTop)
                    assertEquals(trackTop, range.endInclusive + entryOffset, 0.0001f)
                    assertEquals(height * 0.15f, range.endInclusive - range.start, 0.0001f)
                    val hidden = appleMusicLyricsControlsFadeRange(height, 0.6f, entryOffset, trackTop)
                    assertEquals(height, hidden.start + entryOffset, 0.0001f)
                }
            }
        }
    }

    @Test fun controlsFadeAppearsFasterThanItClearsAndCanReverseWithoutJumping() {
        val showing = TargetBasedAnimation(appleMusicControlsFadeSpring(true), Float.VectorConverter, 0.6f, 0f)
        val hiding = TargetBasedAnimation(appleMusicControlsFadeSpring(false), Float.VectorConverter, 0f, 0.6f)
        val time = 120_000_000L
        assertTrue(showing.getValueFromNanos(time) < 0.6f - hiding.getValueFromNanos(time))
        val value = hiding.getValueFromNanos(time)
        val velocity = hiding.getVelocityVectorFromNanos(time).value
        val reversed = TargetBasedAnimation(
            appleMusicControlsFadeSpring(true), Float.VectorConverter, value, 0f, velocity,
        )
        assertEquals(value, reversed.getValueFromNanos(0), 0.0001f)
        assertEquals(velocity, reversed.getVelocityVectorFromNanos(0).value, 0.0001f)
        assertEquals(0f, reversed.getValueFromNanos(reversed.durationNanos), 0f)
    }

    @Test fun lyricEntryWaitsForArtworkAndFinishesBeforeTheLastCoverFrame() {
        assertEquals(0f, appleMusicLyricsAlpha(0f), 0f)
        assertEquals(0f, appleMusicLyricsAlpha(0.08f), 0f)
        assertEquals(0.5f, appleMusicLyricsAlpha(0.49f), 0.0001f)
        assertEquals(1f, appleMusicLyricsAlpha(0.9f), 0.0001f)
        assertEquals(1f, appleMusicLyricsAlpha(1.1f), 0f)
        assertEquals(0f, appleMusicLyricsAlpha(-0.1f), 0f)
    }

    @Test fun lyricEntryTravelsFromTheMeasuredInformationBottomWithoutMovingCompactLayoutsUpward() {
        for ((sourceBottom, viewportTop) in listOf(520f to 136f, 890f to 156f, 300f to 400f)) {
            val distance = (sourceBottom - viewportTop).coerceAtLeast(0f)
            assertEquals(distance, appleMusicLyricsTranslationY(0f, sourceBottom, viewportTop), 0f)
            assertEquals(0f, appleMusicLyricsTranslationY(1f, sourceBottom, viewportTop), 0f)
            assertEquals(maxOf(sourceBottom, viewportTop),
                viewportTop + appleMusicLyricsTranslationY(0f, sourceBottom, viewportTop), 0f)
        }
    }

    @Test fun informationCrossfadesTogetherWithBoundedOpacity() {
        var previousAlbum = 1f
        var previousHeader = 0f
        for (step in -10..110) {
            val p = step / 100f
            val album = appleMusicAlbumInfoAlpha(p)
            val header = appleMusicHeaderInfoAlpha(p)
            assertTrue(album in 0f..1f && header in 0f..1f)
            assertTrue(album <= previousAlbum)
            assertTrue(header >= previousHeader)
            assertEquals(1f, album + header, 0.0001f)
            previousAlbum = album
            previousHeader = header
        }
        assertEquals(1f, appleMusicAlbumInfoAlpha(0f), 0f)
        assertEquals(0f, appleMusicHeaderInfoAlpha(0f), 0f)
        assertEquals(0f, appleMusicAlbumInfoAlpha(1f), 0f)
        assertEquals(1f, appleMusicHeaderInfoAlpha(1f), 0f)
    }

    @Test fun headerFinishesFadingOnTheSameSpringFrameAsTheArtwork() {
        val normal = Rect(48f, 150f, 352f, 454f)
        val target = Rect(24f, 76f, 70f, 122f)
        val animation = TargetBasedAnimation(appleMusicLyricSpring(), Float.VectorConverter, 0f, 1f)
        for (time in 0 until animation.durationNanos step 16_666_667L) {
            val p = animation.getValueFromNanos(time)
            val frame = appleMusicArtworkFrame(normal, target, 12f, 4f, 16f, 1f, p)
            if (frame.bounds.width > target.width) assertTrue(appleMusicHeaderInfoAlpha(p) < 1f)
        }
        val end = animation.getValueFromNanos(animation.durationNanos)
        assertEquals(target, appleMusicArtworkFrame(normal, target, 12f, 4f, 16f, 1f, end).bounds)
        assertEquals(1f, appleMusicHeaderInfoAlpha(end), 0f)
        assertEquals(0f, appleMusicAlbumInfoAlpha(end), 0f)
    }

    @Test fun interruptedSpringRetainsBothOpacitiesAndReversesToAlbumInformation() = runBlocking {
        val clock = BroadcastFrameClock()
        val progress = Animatable(0f)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                clock.sendFrame(time)
                time += 16_666_667L
                yield()
            }
        }
        val opening = launch(clock) { progress.animateTo(1f, appleMusicLyricSpring()) }
        frames(10)
        opening.cancelAndJoin()
        val album = appleMusicAlbumInfoAlpha(progress.value)
        val header = appleMusicHeaderInfoAlpha(progress.value)
        val lyrics = appleMusicLyricsAlpha(progress.value)
        val lyricY = appleMusicLyricsTranslationY(progress.value, 520f, 136f)
        assertTrue(header > 0f && header < 1f)
        frames(4)
        assertEquals(album, appleMusicAlbumInfoAlpha(progress.value), 0f)
        assertEquals(header, appleMusicHeaderInfoAlpha(progress.value), 0f)
        assertEquals(lyrics, appleMusicLyricsAlpha(progress.value), 0f)
        assertEquals(lyricY, appleMusicLyricsTranslationY(progress.value, 520f, 136f), 0f)
        val closing = launch(clock) { progress.animateTo(0f, appleMusicLyricSpring()) }
        try {
            frames(1)
            assertEquals(album, appleMusicAlbumInfoAlpha(progress.value), 0.0001f)
            assertEquals(header, appleMusicHeaderInfoAlpha(progress.value), 0.0001f)
            assertEquals(lyrics, appleMusicLyricsAlpha(progress.value), 0.0001f)
            assertEquals(lyricY, appleMusicLyricsTranslationY(progress.value, 520f, 136f), 0.0001f)
            frames(120)
            assertEquals(1f, appleMusicAlbumInfoAlpha(progress.value), 0f)
            assertEquals(0f, appleMusicHeaderInfoAlpha(progress.value), 0f)
            assertEquals(0f, appleMusicLyricsAlpha(progress.value), 0f)
            assertEquals(384f, appleMusicLyricsTranslationY(progress.value, 520f, 136f), 0f)
        } finally { closing.cancelAndJoin() }
    }

    @Test fun textAndActionsShareTheSameVisualOriginsThroughoutBothDirections() {
        for ((album, header) in listOf(
            Offset(26f, 490f) to Offset(104.5f, 76f),
            Offset(294f, 497f) to Offset(291.5f, 79f),
            Offset(26f, 800f) to Offset(104.5f, 76f),
        )) {
            for (p in listOf(0f, 0.12f, 0.7f, 0.35f, 1f, 0.8f, 0f)) {
                val fromAlbum = album + appleMusicInfoOffset(album, header, p, true)
                val fromHeader = header + appleMusicInfoOffset(album, header, p, false)
                assertEquals(fromAlbum.x, fromHeader.x, 0.0001f)
                assertEquals(fromAlbum.y, fromHeader.y, 0.0001f)
                assertTrue(fromAlbum.x in minOf(album.x, header.x)..maxOf(album.x, header.x))
                assertTrue(fromAlbum.y in minOf(album.y, header.y)..maxOf(album.y, header.y))
            }
            assertEquals(Offset.Zero, appleMusicInfoOffset(album, header, 0f, true))
            assertEquals(Offset.Zero, appleMusicInfoOffset(album, header, 1f, false))
        }
    }

    @Test fun measuredInformationStaysInsideTheViewportAndAboveTheControls() {
        for (height in listOf(680f, 820f, 1200f)) {
            for (informationHeight in listOf(48f, 72f, 96f)) {
                val controlsHeight = 268f
                val top = appleMusicAlbumInfoTop(height, controlsHeight, informationHeight, 24f, 36f)
                assertTrue(top >= 36f)
                assertEquals(height - controlsHeight - 24f, top + informationHeight, 0f)
                for (p in 0..100) {
                    val origin = Offset(26f, top) + appleMusicInfoOffset(
                        Offset(26f, top), Offset(104.5f, 80f), p / 100f, true,
                    )
                    assertTrue(origin.y >= 36f)
                    assertTrue(origin.y + informationHeight <= height)
                }
            }
        }
    }
}
