package com.ljyh.mei.ui.screen.search

import org.junit.Assert.*
import org.junit.Test

class SearchHistoryTest {
    @Test
    fun repeatedQueriesMoveToTheFrontWithoutChangingOtherEntries() {
        assertEquals(listOf("Mei", "Song"), updatedSearchHistory(listOf("Song", "mei"), " Mei "))
        assertEquals(listOf("Song"), updatedSearchHistory(listOf("Song"), " "))
        val history = (1..20).map { "Song $it" }
        assertEquals(listOf("New") + history.take(19), updatedSearchHistory(history, "New"))
    }

    @Test
    fun storedHistoryRecoversFromMalformedDataAndIgnoresInvalidEntries() {
        assertTrue(decodeSearchHistory("{broken").isEmpty())
        assertTrue(decodeSearchHistory(null).isEmpty())
        assertEquals(listOf("Mei", "Song"), decodeSearchHistory("""[" Mei ",null,42,"mei","",{},"Song"]"""))
    }
}
