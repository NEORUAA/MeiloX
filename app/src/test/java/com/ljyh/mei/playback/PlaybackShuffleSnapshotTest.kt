package com.ljyh.mei.playback

import com.google.gson.Gson
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackShuffleSnapshotTest {
    private val gson = Gson()

    @Test
    fun versionTwoRoundTripKeepsOrderSourceCurrentIndexAndProgress() {
        val original = PlaybackSnapshot(
            items = listOf("11", "22", "11").map { PlaybackItemSnapshot(mediaId = it) },
            currentIndex = 2,
            positionMs = 12345L,
            shuffleModeEnabled = true,
            shuffleOrder = listOf(2, 0, 1),
            playlistSource = PlaylistQueueSource(123L, "server-alg"),
        )
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertEquals(original, restored)
        assertTrue(restored.shuffleOrder!!.isPlaybackPermutation(restored.items.size))
    }

    @Test
    fun legacySnapshotKeepsQueueWithoutInventingAServerOrder() {
        val restored = gson.fromJson(
            """{"schemaVersion":1,"items":[{"mediaId":"11"}],"currentIndex":0,
                "positionMs":7000,"shuffleModeEnabled":true,"sourceType":"queue"}""",
            PlaybackSnapshot::class.java,
        )
        assertEquals(1, restored.schemaVersion)
        assertEquals("11", restored.items.single().mediaId)
        assertEquals(7000L, restored.positionMs)
        assertTrue(restored.shuffleModeEnabled)
        assertNull(restored.shuffleOrder)
        assertNull(restored.playlistSource)
    }

    @Test
    fun malformedPermutationIsRejectedWithoutDiscardingDecodedItems() {
        listOf("[0,0]", "[0,2]", "[-1,0]", "[0]", "[0,null]").forEach { order ->
            val restored = gson.fromJson(
                """{"schemaVersion":2,"items":[{"mediaId":"11"},{"mediaId":"22"}],"shuffleOrder":$order}""",
                PlaybackSnapshot::class.java,
            )
            assertEquals(2, restored.items.size)
            assertFalse(restored.shuffleOrder!!.isPlaybackPermutation(restored.items.size))
        }
    }

    @Test
    fun nonShuffleSnapshotStillRetainsItsStoredPermutation() {
        val original = PlaybackSnapshot(shuffleModeEnabled = false, shuffleOrder = listOf(1, 0))
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertFalse(restored.shuffleModeEnabled)
        assertEquals(listOf(1, 0), restored.shuffleOrder)
    }

    @Test
    fun fmSnapshotRetainsItsSourceType() {
        val original = PlaybackSnapshot(sourceType = PlaybackSnapshot.SOURCE_PERSONAL_FM)
        assertTrue(gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java).isFmMode)
    }
}
