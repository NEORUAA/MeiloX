package com.ljyh.mei.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import java.lang.reflect.Proxy
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOrderTest {
    @Test
    fun nextAutomaticEntryFollowsShuffleAndRepeatModes() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))
        assertEquals(3, playerFor(timeline, shuffle = true, currentIndex = 0).nextPlaybackMediaItemIndex())
        assertEquals(1, playerFor(timeline, shuffle = false, currentIndex = 0).nextPlaybackMediaItemIndex())
        assertEquals(C.INDEX_UNSET, playerFor(timeline, true, currentIndex = 1).nextPlaybackMediaItemIndex())
        assertEquals(2, playerFor(timeline, true, 1, Player.REPEAT_MODE_ALL).nextPlaybackMediaItemIndex())
        assertEquals(1, playerFor(timeline, true, 1, Player.REPEAT_MODE_ONE).nextPlaybackMediaItemIndex())
        assertEquals(C.INDEX_UNSET, playerFor(OrderedTimeline(emptyList()), true).nextPlaybackMediaItemIndex())
    }

    @Test
    fun visibleListSelectionCorrectsAStaleIndexAndKeepsItsPlaylistSource() {
        val source = PlaylistQueueSource(42L, "visible-list")
        val queue = ListQueue(
            id = "visible", title = "Visible playlist", items = listOf("10" to null, "20" to null),
            position = 5_000, playlistSource = source,
        )
        val selected = requireNotNull(queue.startingAt("20"))
        assertEquals("visible", selected.id)
        assertEquals("Visible playlist", selected.title)
        assertEquals(1, selected.startIndex)
        assertEquals(0, selected.position)
        assertEquals(2, selected.totalCount)
        assertEquals(source, selected.playlistSource)
    }

    @Test
    fun visibleListSelectionKeepsTheClickedDuplicateAndItsRequestedPosition() {
        val queue = ListQueue(
            id = "duplicates", items = listOf("10" to null, "20" to null, "10" to null),
            startIndex = 2, position = 9_000,
        )
        assertSame(queue, queue.startingAt("10"))
        assertNull(queue.startingAt("missing"))
        assertNull(ListQueue("empty", items = emptyList()).startingAt("10"))
    }

    @Test
    fun timelineTraversalUsesShuffleOrderAndIgnoresRepeatMode() {
        val timeline = OrderedTimeline(listOf(2, 0, 3, 1))
        val player = playerFor(timeline, shuffle = true)
        assertEquals(listOf(2, 0, 3, 1), player.playbackOrderIndices())
        assertEquals(listOf(0, 1, 2, 3), player.playbackOrderIndices(false))
        assertTrue(timeline.repeatModes.all { it == Player.REPEAT_MODE_OFF })
    }

    @Test
    fun storedShuffleOrderCanBeReadWhileShuffleIsDisabled() {
        val player = playerFor(OrderedTimeline(listOf(1, 2, 0)), shuffle = false)
        assertEquals(listOf(0, 1, 2), player.playbackOrderIndices())
        assertEquals(listOf(1, 2, 0), player.playbackOrderIndices(true))
    }

    @Test
    fun emptyTimelineDoesNotReadAnyWindowOrCurrentItem() {
        assertEquals(emptyList<Int>(), playerFor(OrderedTimeline(emptyList()), true).playbackOrderIndices())
    }

    @Test
    fun emptyAndSingletonPermutationsAreValid() {
        assertTrue(emptyList<Int>().isPlaybackPermutation(0))
        assertTrue(listOf(0).isPlaybackPermutation(1))
    }

    @Test
    fun missingExtraDuplicateAndOutOfRangeIndicesAreRejected() {
        listOf(listOf(0, 1), listOf(0, 1, 2, 3), listOf(0, 0, 2),
            listOf(-1, 1, 2), listOf(0, 1, 3)).forEach {
            assertFalse("Invalid permutation: $it", it.isPlaybackPermutation(3))
        }
    }

    @Test
    fun nullIndicesFromCorruptJsonAreRejected() {
        assertFalse(listOf(0, null, 2).isPlaybackPermutation(3))
    }

    @Test
    fun arbitraryPermutationsRetainExactMedia3ForwardAndReverseOrder() {
        repeat(200) { seed ->
            val size = seed % 50
            val order = (0 until size).shuffled(Random(seed))
            assertTrue(order.isPlaybackPermutation(size))
            val shuffle = ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), seed.toLong())
            assertEquals(order, shuffle.forward())
            val reverse = mutableListOf<Int>()
            var index = shuffle.lastIndex
            while (index != C.INDEX_UNSET) {
                reverse.add(index)
                index = shuffle.getPreviousIndex(index)
            }
            assertEquals(order.reversed(), reverse)
        }
    }

    @Test
    fun duplicateSongIdsStillHaveDistinctQueuePositions() {
        val songIds = listOf("10", "20", "10", "30")
        val order = listOf(2, 1, 0, 3)
        assertTrue(order.isPlaybackPermutation(songIds.size))
        assertEquals(listOf("10", "20", "10", "30"), order.map(songIds::get))
        assertEquals(order, ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), 1L).forward())
    }

    @Test
    fun removingAnEntryPreservesSurvivingPlaybackOrder() {
        val original = listOf(3, 1, 4, 0, 2)
        val shuffle = ShuffleOrder.DefaultShuffleOrder(original.toIntArray(), 7L)
        val remaining = shuffle.cloneAndRemove(1, 2).forward()
        assertEquals(listOf(2, 3, 0, 1), remaining)
        assertTrue(remaining.isPlaybackPermutation(4))
    }

    private fun ShuffleOrder.forward(): List<Int> {
        val result = mutableListOf<Int>()
        var index = firstIndex
        while (index != C.INDEX_UNSET) {
            check(result.size < length) { "Shuffle order contains a cycle" }
            result.add(index)
            index = getNextIndex(index)
        }
        return result
    }

    private fun playerFor(
        timeline: Timeline,
        shuffle: Boolean,
        currentIndex: Int = 0,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
    ): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getCurrentTimeline" -> timeline
            "getShuffleModeEnabled" -> shuffle
            "getCurrentMediaItemIndex" -> currentIndex
            "getRepeatMode" -> repeatMode
            else -> error("Unexpected player access: ${method.name}")
        }
    } as Player

    private class OrderedTimeline(private val order: List<Int>) : Timeline() {
        val repeatModes = mutableListOf<Int>()

        override fun getWindowCount() = order.size
        override fun getPeriodCount() = order.size
        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
            if (shuffleModeEnabled) order.firstOrNull() ?: C.INDEX_UNSET
            else if (order.isEmpty()) C.INDEX_UNSET else 0

        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            repeatModes.add(repeatMode)
            if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
            val indices = if (shuffleModeEnabled) order else order.indices.toList()
            return indices.getOrNull(indices.indexOf(windowIndex) + 1)
                ?: if (repeatMode == Player.REPEAT_MODE_ALL) indices.firstOrNull() ?: C.INDEX_UNSET
                else C.INDEX_UNSET
        }

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            error("Traversal must not allocate or read windows")

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            error("Traversal must not read periods")

        override fun getIndexOfPeriod(uid: Any) = C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }
}
