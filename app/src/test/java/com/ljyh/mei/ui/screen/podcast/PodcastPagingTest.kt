package com.ljyh.mei.ui.screen.podcast

import com.ljyh.mei.source.shared.OnlinePodcast as Podcast
import com.ljyh.mei.source.shared.OnlinePodcastDetail as PodcastDetail
import com.ljyh.mei.source.shared.OnlinePodcastPage as PodcastPage
import com.ljyh.mei.source.shared.OnlinePodcastProgram as PodcastProgram
import com.ljyh.mei.source.shared.OnlinePodcastProgramPage as PodcastProgramPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastPagingTest {
    @Test
    fun subscriptionPagesAppendOnlyNewPodcasts() {
        val merged = appendUniquePodcasts(
            existing = listOf(podcast(1), podcast(2)),
            incoming = listOf(podcast(2), podcast(3)),
        )

        assertEquals(listOf("1", "2", "3"), merged.map(Podcast::id))
    }

    @Test
    fun programPagesAppendOnlyNewEpisodes() {
        val merged = appendUniquePrograms(
            existing = listOf(program(10), program(11)),
            incoming = listOf(program(11), program(12)),
        )

        assertEquals(listOf("10", "11", "12"), merged.map(PodcastProgram::id))
    }

    @Test
    fun subscriptionCursorCountsFilteredAndDuplicateRowsAndResetsOnRefresh() {
        var state = PodcastUiState().withSubscriptionPage(
            PodcastPage(listOf(podcast(1), podcast(2)), true, 10, fetchedCount = 4),
            reset = true,
        )
        assertEquals(4, state.subscriptionOffset)
        state = state.withSubscriptionPage(
            PodcastPage(listOf(podcast(2)), true, 10, fetchedCount = 3),
            reset = false,
        )
        assertEquals(listOf("1", "2"), state.subscribedPodcasts.map(Podcast::id))
        assertEquals(7, state.subscriptionOffset)
        assertTrue(state.hasMoreSubscriptions)

        state = state.withSubscriptionPage(
            PodcastPage(emptyList(), true, 10, fetchedCount = 2),
            reset = false,
        )
        assertEquals(9, state.subscriptionOffset)
        assertTrue(state.hasMoreSubscriptions)
        state = state.withSubscriptionPage(
            PodcastPage(listOf(podcast(3)), false, 10),
            reset = false,
        )
        assertEquals(listOf("1", "2", "3"), state.subscribedPodcasts.map(Podcast::id))
        assertEquals(10, state.subscriptionOffset)
        assertFalse(state.hasMoreSubscriptions)

        state = state.withSubscriptionPage(PodcastPage(listOf(podcast(4)), false, 1), reset = true)
        assertEquals(listOf("4"), state.subscribedPodcasts.map(Podcast::id))
        assertEquals(1, state.subscriptionOffset)
    }

    @Test
    fun programCursorKeepsMovingThroughDuplicateAndFullyFilteredPages() {
        var detail = PodcastDetail(
            podcast = podcast(1),
            programs = listOf(program(10)),
            hasMore = true,
            totalCount = 8,
            nextOffset = 3,
        )
        detail = detail.withProgramPage(
            PodcastProgramPage(listOf(program(10)), true, 8, fetchedCount = 2),
        )
        assertEquals(listOf("10"), detail.programs.map(PodcastProgram::id))
        assertEquals(5, detail.nextOffset)
        assertTrue(detail.hasMore)

        detail = detail.withProgramPage(PodcastProgramPage(emptyList(), true, 8, fetchedCount = 2))
        assertEquals(7, detail.nextOffset)
        assertTrue(detail.hasMore)
        detail = detail.withProgramPage(PodcastProgramPage(listOf(program(11)), false, 8))
        assertEquals(listOf("10", "11"), detail.programs.map(PodcastProgram::id))
        assertEquals(8, detail.nextOffset)
        assertFalse(detail.hasMore)
    }

    private fun podcast(id: Long) = Podcast(
        id = id.toString(),
        name = "Podcast $id",
        picUrl = null,
        description = null,
        recommendation = null,
        categoryId = null,
        category = null,
        secondCategory = null,
        programCount = 0,
        subscriberCount = 0,
        playCount = 0,
        host = null,
        isSubscribed = true,
        feeType = null,
    )

    private fun program(id: Long) = PodcastProgram(
        id = id.toString(),
        name = "Episode $id",
        coverUrl = null,
        description = null,
        createTime = null,
        durationMs = 0,
        listenerCount = 0,
        likedCount = 0,
        commentCount = 0,
        serialNumber = null,
        radioId = "1",
        radioName = "Podcast",
        host = null,
        mainSongId = null,
    )
}
