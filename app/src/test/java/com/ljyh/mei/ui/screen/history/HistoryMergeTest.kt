package com.ljyh.mei.ui.screen.history

import com.ljyh.mei.data.model.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMergeTest {
    @Test
    fun cloudOrderWinsAndLocalDuplicatesAreRemoved() {
        val result = mergeHistoryEntries(
            remote = listOf(entry(2, "cloud-2"), entry(1, "cloud-1")),
            local = listOf(entry(1, "local-1", 100), entry(3, "local-3", 90)),
            remoteRequestStartedAt = 200,
        )

        assertEquals(listOf(2L, 1L, 3L), result.map { it.song.id })
    }

    @Test
    fun localPlaybackAfterRefreshIsOptimisticallyPrepended() {
        val result = mergeHistoryEntries(
            remote = listOf(entry(2, "cloud-2"), entry(4, "cloud-4")),
            local = listOf(entry(4, "local-4", 300), entry(3, "local-3", 100)),
            remoteRequestStartedAt = 200,
        )

        assertEquals(listOf(4L, 2L, 3L), result.map { it.song.id })
        assertEquals("local-4", result.first().key)
    }

    @Test
    fun localHistoryRemainsAvailableWithoutCloudData() {
        val result = mergeHistoryEntries(
            remote = null,
            local = listOf(entry(1, "local-new", 200), entry(1, "local-old", 100)),
            remoteRequestStartedAt = null,
        )

        assertEquals(listOf("local-new"), result.map(ListeningHistoryEntry::key))
    }

    private fun entry(id: Long, key: String, playedAt: Long? = null) = ListeningHistoryEntry(
        key = key,
        song = MediaMetadata(
            id = id,
            title = "Song $id",
            coverUrl = "",
            artists = listOf(MediaMetadata.Artist(id, "Artist $id")),
            duration = 180_000,
            album = MediaMetadata.Album(id, "Album $id"),
        ),
        playedAt = playedAt,
    )
}
