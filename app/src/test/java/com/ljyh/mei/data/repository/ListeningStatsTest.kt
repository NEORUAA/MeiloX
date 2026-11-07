package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.melox.ListeningStatsSection
import com.ljyh.mei.data.model.melox.ListeningStatsSectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningStatsTest {
    @Test
    fun durationChangesRoundToTheNearestMinuteWithoutRoundingOtherSectionTypes() {
        val seconds = listOf(29L, 30L, 59L, 60L, 8630L, Long.MAX_VALUE)
        val minutes = listOf(0L, 1L, 1L, 1L, 144L, Long.MAX_VALUE / 60)
        listOf(ListeningStatsSectionType.DurationIncrease, ListeningStatsSectionType.DurationDecrease).forEach { type ->
            assertEquals(minutes, seconds.map { ListeningStatsSection(type, it).durationChangeMinutes })
        }
        assertEquals(null, ListeningStatsSection(ListeningStatsSectionType.EarlySleep, 1790635203000L).durationChangeMinutes)
        assertEquals(null, ListeningStatsSection(ListeningStatsSectionType.LikedSongsPercent, 80L).durationChangeMinutes)
    }

}
