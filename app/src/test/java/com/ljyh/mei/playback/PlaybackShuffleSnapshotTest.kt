package com.ljyh.mei.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.constants.RestorePlaybackPositionKey
import com.ljyh.mei.constants.RestorePlaybackStateKey
import com.ljyh.mei.playback.queue.PlaylistQueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertFalse(restored.queueAutoplayEnabled)
        assertNull(restored.queueAutoplayStartIndex)
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

    @Test
    fun similarFmRoundTripKeepsTheOriginalSeedsForRefill() {
        val original = PlaybackSnapshot(
            sourceType = PlaybackSnapshot.SOURCE_PERSONAL_FM,
            similarFmSourceIds = listOf("42", "19", "7"),
        )
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertTrue(restored.isFmMode)
        assertEquals(listOf("42", "19", "7"), restored.similarFmSourceIds)
        assertEquals("[42,19,7]", SimilarFmRequest.fromSongIds(restored.similarFmSourceIds!!)!!.body()["sourceIds"])
    }

    @Test
    fun oldPersonalFmSnapshotDoesNotInventSimilarSources() {
        val restored = gson.fromJson(
            """{"schemaVersion":2,"sourceType":"personal_fm","items":[]}""",
            PlaybackSnapshot::class.java,
        )
        assertTrue(restored.isFmMode)
        assertNull(restored.similarFmSourceIds)
    }

    @Test
    fun queueAutoplayRoundTripKeepsTheOriginalQueueSourceModesAndPausedProgress() {
        val original = PlaybackSnapshot(
            items = listOf("11", "42", "101").map { PlaybackItemSnapshot(mediaId = it) },
            currentIndex = 1,
            positionMs = 12_345L,
            repeatMode = 1,
            shuffleModeEnabled = true,
            shuffleOrder = listOf(1, 0, 2),
            playWhenReady = false,
            queueTitle = "Original playlist",
            playlistSource = PlaylistQueueSource(88L, "existing-source"),
            similarFmSourceIds = listOf("42"),
            queueAutoplayEnabled = true,
            queueAutoplayStartIndex = 2,
        )
        val restored = gson.fromJson(gson.toJson(original), PlaybackSnapshot::class.java)
        assertEquals(original, restored)
        assertEquals(PlaybackSnapshot.SOURCE_QUEUE, restored.sourceType)
        assertTrue(restored.isFmMode)
        assertTrue(restored.queueAutoplayEnabled)
        assertFalse(restored.playWhenReady)
        assertEquals(listOf("42"), restored.similarFmSourceIds)
    }

    @Test
    fun restorePreferencesIndependentlyControlProgressAndStateAfterTheLatestCheckpoint() {
        val saved = PlaybackSnapshot(
            savedAtEpochMs = 100L,
            items = listOf("11", "22").map { PlaybackItemSnapshot(mediaId = it) },
            shuffleOrder = listOf(1, 0),
            playlistSource = PlaylistQueueSource(123L, "server-alg"),
            queueTitle = "Saved queue",
        )
        val latest = saved.withCheckpoint(PlaybackCheckpoint(100L, 200L, 1, 12345L, 2, true, true))

        listOf(false, true).forEach { restorePosition ->
            listOf(false, true).forEach { restoreState ->
                val restored = latest.withRestorePreferences(preferencesOf(
                    RestorePlaybackPositionKey to restorePosition,
                    RestorePlaybackStateKey to restoreState,
                ))
                assertEquals(if (restorePosition) 12345L else 0L, restored.positionMs)
                assertEquals(restoreState, restored.playWhenReady)
                assertEquals(latest, restored.copy(positionMs = latest.positionMs, playWhenReady = true))
            }
        }
        assertEquals(12345L, latest.positionMs)
        assertTrue(latest.playWhenReady)
    }

    @Test
    fun missingRestorePreferencesKeepExistingBehaviorAndNeverStartAPausedSnapshot() {
        val playing = PlaybackSnapshot(positionMs = 7000L, playWhenReady = true)
        assertEquals(playing, playing.withRestorePreferences(emptyPreferences()))
        val paused = playing.copy(playWhenReady = false)
        assertEquals(paused, paused.withRestorePreferences(emptyPreferences()))
        assertEquals(paused, paused.withRestorePreferences(preferencesOf(RestorePlaybackStateKey to true)))
        assertEquals(0L, paused.copy(positionMs = -1L).withRestorePreferences(emptyPreferences()).positionMs)
    }

    @Test
    fun checkpointWritesCanonicalKeysAndRewritesVerifiedLegacyKeys() {
        val checkpoint = PlaybackCheckpoint(100L, 200L, 1, 12345L, 2, true, false)
        val canonical = """{"queueSavedAtEpochMs":100,"savedAtEpochMs":200,"currentIndex":1,"positionMs":12345,"repeatMode":2,"shuffleModeEnabled":true,"playWhenReady":false}"""
        val legacy = """{"a":100,"b":200,"c":1,"d":12345,"e":2,"f":true,"g":false}"""

        assertEquals(JsonParser.parseString(canonical), JsonParser.parseString(gson.toJson(checkpoint)))
        listOf(canonical, legacy).forEach { encoded ->
            val restored = gson.fromJson(encoded, PlaybackCheckpoint::class.java)
            assertEquals(checkpoint, restored)
            assertEquals(JsonParser.parseString(canonical), JsonParser.parseString(gson.toJson(restored)))
        }
    }

    @Test
    fun legacyCheckpointOverridesOlderPlaybackStateWithoutReplacingQueueOrSimilarFmSource() {
        val saved = PlaybackSnapshot(
            savedAtEpochMs = 100L,
            items = listOf("11", "22").map { PlaybackItemSnapshot(mediaId = it) },
            sourceType = PlaybackSnapshot.SOURCE_PERSONAL_FM,
            similarFmSourceIds = listOf("42", "19"),
            shuffleOrder = listOf(1, 0),
            playWhenReady = true,
        )
        val legacy = gson.fromJson(
            """{"a":100,"b":200,"c":1,"d":12345,"e":2,"f":true,"g":false}""",
            PlaybackCheckpoint::class.java,
        )
        val restored = saved.withCheckpoint(legacy)

        assertFalse(restored.playWhenReady)
        assertEquals(1, restored.currentIndex)
        assertEquals(12345L, restored.positionMs)
        assertEquals(200L, restored.savedAtEpochMs)
        assertTrue(restored.shuffleModeEnabled)
        assertSame(saved.items, restored.items)
        assertSame(saved.shuffleOrder, restored.shuffleOrder)
        assertEquals(saved.similarFmSourceIds, restored.similarFmSourceIds)
        assertTrue(restored.isFmMode)
        assertEquals(0L, restored.withRestorePreferences(
            preferencesOf(RestorePlaybackPositionKey to false),
        ).positionMs)
    }

    @Test
    fun checkpointKeepsLongPrecisionAndExplicitPlayingStateAcrossBothFormats() {
        val checkpoint = PlaybackCheckpoint(1_790_000_000_001L, 1_790_000_000_002L, 1, 4_000_000_000L, 2, false, true)
        val legacy = """{"a":1790000000001,"b":1790000000002,"c":1,"d":4000000000,"e":2,"f":false,"g":true}"""
        val saved = PlaybackSnapshot(
            savedAtEpochMs = checkpoint.queueSavedAtEpochMs,
            items = listOf(PlaybackItemSnapshot("11"), PlaybackItemSnapshot("22")),
        )

        listOf(legacy, gson.toJson(checkpoint)).forEach { encoded ->
            val restored = gson.fromJson(encoded, PlaybackCheckpoint::class.java)
            assertEquals(checkpoint, restored)
            assertTrue(saved.withCheckpoint(restored).playWhenReady)
            assertFalse(saved.withCheckpoint(restored).withRestorePreferences(
                preferencesOf(RestorePlaybackStateKey to false),
            ).playWhenReady)
        }
    }

    @Test
    fun legacyCheckpointStillRejectsUnrelatedQueuesAndInvalidProgress() {
        val saved = PlaybackSnapshot(
            savedAtEpochMs = 100L,
            items = listOf(PlaybackItemSnapshot("11"), PlaybackItemSnapshot("22")),
        )
        listOf(
            """{"a":99,"b":200,"c":1,"d":12345,"e":2,"f":true,"g":false}""",
            """{"a":100,"b":200,"c":2,"d":12345,"e":2,"f":true,"g":false}""",
            """{"a":100,"b":200,"c":1,"d":-1,"e":2,"f":true,"g":false}""",
            """{"a":100,"b":200,"c":1,"d":12345,"e":3,"f":true,"g":false}""",
        ).forEach { encoded ->
            assertSame(saved, saved.withCheckpoint(gson.fromJson(encoded, PlaybackCheckpoint::class.java)))
        }
    }
}
