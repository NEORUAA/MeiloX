package com.ljyh.mei.ui.screen.podcast

import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastPagingTest {
    @Test
    fun subscriptionPagesAppendOnlyNewPodcasts() {
        val merged = appendUniquePodcasts(
            existing = listOf(podcast(1), podcast(2)),
            incoming = listOf(podcast(2), podcast(3)),
        )

        assertEquals(listOf(1L, 2L, 3L), merged.map(Podcast::id))
    }

    @Test
    fun programPagesAppendOnlyNewEpisodes() {
        val merged = appendUniquePrograms(
            existing = listOf(program(10), program(11)),
            incoming = listOf(program(11), program(12)),
        )

        assertEquals(listOf(10L, 11L, 12L), merged.map(PodcastProgram::id))
    }

    private fun podcast(id: Long) = Podcast(
        id = id,
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
        id = id,
        name = "Episode $id",
        coverUrl = null,
        description = null,
        createTime = null,
        durationMs = 0,
        listenerCount = 0,
        likedCount = 0,
        commentCount = 0,
        serialNumber = null,
        radioId = 1,
        radioName = "Podcast",
        host = null,
        mainSongId = null,
    )
}
