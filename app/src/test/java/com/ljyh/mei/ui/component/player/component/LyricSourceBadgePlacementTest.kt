package com.ljyh.mei.ui.component.player.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSourceBadgePlacementTest {
    @Test
    fun extendingLyricsBehindTheFooterDoesNotMoveTheSourceBadge() {
        val fullOrigin = Offset(1_010f, 1_420f)
        for (isLtr in listOf(true, false)) {
            for (footerHeight in listOf(300, 220, 160, 80, 16)) {
                val progress = (footerHeight - 16) / 284f
                val oldOrigin = fullOrigin.copy(y = fullOrigin.y - footerHeight)
                val anchor = Offset(958f, 1_100f)
                val oldOffset = lyricSourceBadgeOffset(-52, oldOrigin, anchor, progress, isLtr, 958f)
                val newOffset = lyricSourceBadgeOffset(
                    -52, fullOrigin, anchor, progress, isLtr, 958f, defaultY = -footerHeight,
                )
                assertEquals(oldOffset.x, newOffset.x)
                assertEquals(oldOrigin.y + oldOffset.y, fullOrigin.y + newOffset.y, 1f)
            }
        }
        assertEquals(
            IntOffset(-18, -16),
            lyricSourceBadgeOffset(-18, fullOrigin, null, 0f, true, defaultY = -16),
        )
    }

    @Test
    fun headerAlignmentIsIndependentOfFooterVisibilityAndTheOldAlbumAnchor() {
        for (isLtr in listOf(true, false)) {
            val badgeOrigin = Offset(if (isLtr) 1010f else 0f, 1200f)
            val headerX = if (isLtr) 958f else 310f
            val albumAnchor = Offset(if (isLtr) 977f else 55f, 1160f)
            val direction = if (isLtr) 1 else -1
            for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val offset = lyricSourceBadgeOffset(
                    -52, badgeOrigin, albumAnchor, progress, isLtr, headerX,
                )
                assertEquals(headerX, badgeOrigin.x + offset.x * direction, 0.001f)
                assertEquals((albumAnchor.y - badgeOrigin.y) * progress, offset.y.toFloat(), 0.001f)
            }
        }
    }

    @Test
    fun aMeasuredHeaderDoesNotNeedAnAlbumAnchorInCompactLayouts() {
        assertEquals(
            IntOffset(-18, 0),
            lyricSourceBadgeOffset(-18, Offset(300f, 400f), null, 1f, true, 282f),
        )
    }

    @Test
    fun classicKeepsItsExistingTwoAxisTravelAndMissingAnchorFallback() {
        assertEquals(
            IntOffset(11, -30),
            lyricSourceBadgeOffset(6, Offset(300f, 400f), Offset(326f, 280f), 0.25f, true),
        )
        assertEquals(
            IntOffset(6, 0),
            lyricSourceBadgeOffset(6, Offset(300f, 400f), null, 0.25f, true),
        )
        assertEquals(
            IntOffset(-16, -30),
            lyricSourceBadgeOffset(6, Offset.Zero, Offset(82f, -120f), 0.25f, false),
        )
    }
}
