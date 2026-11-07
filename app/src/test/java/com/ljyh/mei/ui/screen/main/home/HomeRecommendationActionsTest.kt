package com.ljyh.mei.ui.screen.main.home

import com.ljyh.mei.utils.playlistCoverWithoutOverlays
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecommendationActionsTest {
    @Test
    fun finalRecommendationSnapsToTheTrailingInsetOnDifferentViewportWidths() {
        for ((viewport, lastOffset) in listOf(360 to 103, 393 to 136, 443 to 186)) {
            assertEquals(lastOffset, homeRowSnapPosition(viewport, 225, 16, 16, 4, 5, 12))
            assertEquals(viewport - 32, lastOffset + 225)
            assertEquals(0, homeRowSnapPosition(viewport, 225, 16, 16, 0, 5, 12))
            assertEquals(0, homeRowSnapPosition(viewport, 225, 16, 16, 2, 5, 12))
        }
    }

    @Test
    fun wideRowsIncludeTheNaturalEndForEveryCardPastTheLastStartAnchor() {
        assertEquals(216, homeRowSnapPosition(1000, 240, 16, 16, 3, 6, 16))
        assertEquals(472, homeRowSnapPosition(1000, 240, 16, 16, 4, 6, 16))
        assertEquals(728, homeRowSnapPosition(1000, 240, 16, 16, 5, 6, 16))
        assertEquals(0, homeRowSnapPosition(1000, 240, 16, 16, 2, 6, 16))
        // Both visible tail candidates resolve to the same end position, never backward.
        assertEquals(-10, 462 - homeRowSnapPosition(1000, 240, 16, 16, 4, 6, 16))
        assertEquals(-10, 718 - homeRowSnapPosition(1000, 240, 16, 16, 5, 6, 16))
    }

    @Test
    fun shortRecommendationRowsKeepTheirNaturalUnscrollablePositions() {
        assertEquals(0, homeRowSnapPosition(1000, 240, 16, 16, 0, 2, 16))
        assertEquals(256, homeRowSnapPosition(1000, 240, 16, 16, 1, 2, 16))
    }

    @Test
    fun recommendationCoversRemoveCaptionAndBrandScrimButKeepArtworkOperations() {
        val artwork = "https://p1.music.126.net/artwork.jpg?imageView=1&thumbnail=800y800&enlarge=1"
        val caption = "imageView=1&watermark&type=1&image=Y2FwdGlvbg==&dx=0&dy=0"
        val logo = "watermark&type=1&image=bG9nbw==&dx=0&dy=0"
        val expected = "$artwork%7CimageView=1"
        assertEquals(expected, playlistCoverWithoutOverlays("$artwork%7C$caption%7C$logo%7CimageView=1"))
        assertEquals(expected, playlistCoverWithoutOverlays("$artwork|$caption|$logo|imageView=1"))
        assertEquals(artwork, playlistCoverWithoutOverlays(artwork))
    }

}
