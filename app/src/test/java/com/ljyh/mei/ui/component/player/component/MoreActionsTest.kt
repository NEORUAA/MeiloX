package com.ljyh.mei.ui.component.player.component

import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.ui.model.resolveMoreActionOrder
import com.ljyh.mei.ui.model.availableFor
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.supportsNeteaseSongActions
import com.ljyh.mei.source.api.TrackRef
import org.junit.Assert.*
import org.junit.Test

class MoreActionsTest {
    @Test
    fun externalOriginsKeepHostActionsAndDoNotOfferNativeAccountOrNavigationActions() {
        val native = MediaMetadata(42, "Song", "", emptyList(), 180000, MediaMetadata.Album(1, "Album"))
        val external = native.copy(id = 0L, trackRef = TrackRef("demo", "42"))
        assertTrue(native.supportsNeteaseSongActions)
        assertFalse(external.supportsNeteaseSongActions)
        for (action in listOf(MoreAction.SHARE, MoreAction.ADD_TO_PLAYLIST, MoreAction.COMMENT, MoreAction.SONG_WIKI)) {
            assertTrue(action.availableFor(native))
            assertFalse(action.availableFor(external))
        }
        for (action in listOf(MoreAction.DOWNLOAD, MoreAction.SONG_INFO, MoreAction.VIEW_PLAYLIST, MoreAction.SLEEP_TIMER)) {
            assertTrue(action.availableFor(external))
        }
    }
    @Test
    fun customOrderSurvivesRemovedDuplicateAndNewActionsWithoutLosingMeiloXFeatures() {
        val actions = resolveMoreActionOrder(SortOrder.CUSTOM, listOf("pip", "removed", "share", "pip"))
        assertEquals(listOf(MoreAction.PICTURE_IN_PICTURE, MoreAction.SHARE), actions.take(2))
        assertEquals(MoreAction.entries.toSet(), actions.toSet())
        assertEquals(MoreAction.entries.size, actions.size)
        assertTrue(actions.contains(MoreAction.SONG_WIKI))
    }

    @Test
    fun builtInOrdersDoNotDestroyTheSavedCustomOrder() {
        val ids = MoreAction.entries.reversed().map { it.id }
        assertEquals(MoreAction.entries.sortedByDescending { it.frequency }, resolveMoreActionOrder(SortOrder.FREQUENCY, ids))
        assertEquals(MoreAction.entries.reversed(), resolveMoreActionOrder(SortOrder.CUSTOM, ids))
    }
}
