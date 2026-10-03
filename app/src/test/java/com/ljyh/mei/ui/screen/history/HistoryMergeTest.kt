package com.ljyh.mei.ui.screen.history

import com.ljyh.mei.data.model.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMergeTest {
    @Test
    fun localEntryUsesActualPlaybackTimeAgainstCloudHistory() {
        val result = mergeHistoryEntries(
            remote = listOf(entry(2, "cloud-2", 300_000), entry(4, "cloud-4", 200_000)),
            local = listOf(entry(1, "local-1", 400_000), entry(3, "local-3", 100_000)),
        )

        assertEquals(listOf(1L, 2L, 4L, 3L), result.map { it.song.id })
    }

    @Test
    fun threeHundredRemoteEntriesKeepLatestLocalFirstAndPreferLaterRemoteDuplicate() {
        val remote = buildList {
            add(entry(2, "cloud-2-later", 500_000))
            addAll((3L..301L).map { id -> entry(id, "cloud-$id") })
        }
        val result = mergeHistoryEntries(
            remote = remote,
            local = listOf(entry(1, "local-latest", 600_000), entry(2, "local-older", 400_000)),
        )

        assertEquals(300, remote.size)
        assertEquals(301, result.size)
        assertEquals("local-latest", result.first().key)
        assertEquals("cloud-2-later", result.single { it.song.id == 2L }.key)
        assertEquals(500_000L, result.single { it.song.id == 2L }.playedAt)
        assertTrue(result.drop(2).all { it.playedAt == null })
    }

    @Test
    fun duplicateSongKeepsEntryWithLatestPlaybackTime() {
        val result = mergeHistoryEntries(
            remote = listOf(entry(2, "cloud-2", 400_000)),
            local = listOf(entry(1, "local-1", 300_000), entry(2, "local-2", 500_000)),
        )

        assertEquals(listOf(2L, 1L), result.map { it.song.id })
        assertEquals("local-2", result.first().key)
    }

    @Test
    fun missingCloudTimeStaysAtStableTailWithoutSyntheticRequestTime() {
        val result = mergeHistoryEntries(
            remote = listOf(entry(2, "cloud-2"), entry(3, "cloud-3")),
            local = listOf(entry(1, "local-1", 100_000)),
        )

        assertEquals(listOf(1L, 2L, 3L), result.map { it.song.id })
        assertEquals(listOf("cloud-2", "cloud-3"), result.drop(1).map(ListeningHistoryEntry::key))
    }

    @Test
    fun localKnownTimeWinsOverCloudEntryWithoutTimeForSameSong() {
        val result = mergeHistoryEntries(
            remote = listOf(entry(1, "cloud-1")),
            local = listOf(entry(1, "local-1", 100_000)),
        )

        assertEquals(listOf("local-1"), result.map(ListeningHistoryEntry::key))
        assertEquals(100_000L, result.single().playedAt)
    }

    @Test
    fun localHistoryIsSortedAndDeduplicatedWithoutCloudData() {
        val result = mergeHistoryEntries(
            remote = null,
            local = listOf(
                entry(1, "local-old", 100_000),
                entry(1, "local-new", 200_000),
                entry(2, "local-unknown"),
            ),
        )

        assertEquals(listOf(1L, 2L), result.map { it.song.id })
        assertEquals("local-new", result.first().key)
    }

    @Test
    fun localHistoryRemainsAvailableWithoutCloudData() {
        val result = mergeHistoryEntries(
            remote = null,
            local = listOf(entry(1, "local-new", 200), entry(1, "local-old", 100)),
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
