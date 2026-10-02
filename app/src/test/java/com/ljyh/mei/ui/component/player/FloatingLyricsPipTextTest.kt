package com.ljyh.mei.ui.component.player

import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingLyricsPipTextTest {
    private val lines = listOf(SyncedLine("First", "Translation", 100, 200), SyncedLine("Next", null, 200, 300))

    @Test fun emptyLyricsUseMetadata() {
        assertEquals(Triple("Track", null, null), floatingLyricsPipText(emptyList(), 0, "Track", true, true))
    }

    @Test fun preludeRetainsTheOriginalFirstLineBehavior() {
        assertEquals(Triple("First", "Translation", "Next"), floatingLyricsPipText(lines, 0, "Track", true, true))
    }

    @Test fun nextTimestampAdvancesBothSharedRenderPaths() {
        assertEquals(Triple("Next", null, null), floatingLyricsPipText(lines, 200, "Track", true, true))
    }

    @Test fun preferencesHideOnlyOptionalLines() {
        assertEquals(Triple("First", null, null), floatingLyricsPipText(lines, 150, "Track", false, false))
    }

    @Test fun blankPrimaryAndOptionalTextUseTheOriginalFallback() {
        val blank = listOf(SyncedLine(" ", " ", 0, 100), SyncedLine(" ", null, 100, 200))
        assertEquals(Triple("Track", null, null), floatingLyricsPipText(blank, 50, "Track", true, true))
    }
}
