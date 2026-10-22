package com.ljyh.mei.ui.component.player.component

import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.ui.model.resolveMoreActionOrder
import org.junit.Assert.*
import org.junit.Test

class MoreActionsTest {
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
