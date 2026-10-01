package com.ljyh.mei.playback

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Frozen disk JSON contracts, independent of the current build's class/field names. */
class PlaybackCheckpointWireTest {
    private val gson = Gson()
    private val checkpoint = PlaybackCheckpoint(100, 200, 1, 12345, 2, true, false)
    private val queue = PlaybackSnapshot(savedAtEpochMs = 100,
        items = listOf(PlaybackItemSnapshot("11"), PlaybackItemSnapshot("22")), playWhenReady = true)
    private fun decode(json: String) = gson.fromJson(json, PlaybackCheckpoint::class.java)

    @Test fun writerUsesOnlyCanonicalDiskKeys() {
        val json = JsonParser.parseString(gson.toJson(checkpoint)).asJsonObject
        assertEquals(setOf("queueSavedAtEpochMs", "savedAtEpochMs", "currentIndex", "positionMs",
            "repeatMode", "shuffleModeEnabled", "playWhenReady"), json.keySet())
        assertFalse(json.get("playWhenReady").asBoolean)
        assertEquals(12345L, json.get("positionMs").asLong)
    }

    @Test fun canonicalPausedCheckpointOverridesTheOlderPlayingQueue() {
        val restored = queue.withCheckpoint(decode(CANONICAL))
        assertFalse(restored.playWhenReady)
        assertEquals(1, restored.currentIndex)
        assertEquals(12345L, restored.positionMs)
        assertEquals(200L, restored.savedAtEpochMs)
        assertTrue(restored.shuffleModeEnabled)
        assertSame(queue.items, restored.items)
    }

    @Test fun verifiedLegacyR8PausedCheckpointOverridesTheOlderPlayingQueue() {
        assertEquals(checkpoint, decode(LEGACY_R8))
        val restored = queue.withCheckpoint(decode(LEGACY_R8))
        assertFalse(restored.playWhenReady)
        assertEquals(1, restored.currentIndex)
        assertEquals(12345L, restored.positionMs)
    }

    @Test fun recoveredLegacyCheckpointIsRewrittenWithCanonicalKeys() {
        val recovered = decode(LEGACY_R8)
        assertEquals(JsonParser.parseString(CANONICAL), JsonParser.parseString(gson.toJson(recovered)))
        assertEquals(recovered, decode(gson.toJson(recovered)))
    }

    @Test fun canonicalPlayingCheckpointRetainsItsExplicitResumeState() {
        val playing = checkpoint.copy(playWhenReady = true)
        assertTrue(queue.withCheckpoint(decode(gson.toJson(playing))).playWhenReady)
    }

    @Test fun legacyPlayingCheckpointRetainsItsExplicitResumeState() {
        val playing = decode("""{"a":100,"b":200,"c":1,"d":12345,"e":2,"f":false,"g":true}""")
        assertTrue(queue.withCheckpoint(playing).playWhenReady)
        assertFalse(queue.withCheckpoint(playing).shuffleModeEnabled)
    }

    @Test fun diskLongsKeepTheirPrecisionBeyondTheIntRange() {
        val large = checkpoint.copy(queueSavedAtEpochMs = 1_790_000_000_001,
            savedAtEpochMs = 1_790_000_000_002, positionMs = 4_000_000_000)
        assertEquals(large, decode(gson.toJson(large)))
        assertEquals(large, decode("""{"a":1790000000001,"b":1790000000002,"c":1,"d":4000000000,"e":2,"f":true,"g":false}"""))
    }

    @Test fun unrelatedLegacyQueueStillCannotClaimTheCurrentProgress() {
        val unrelated = decode("""{"a":99,"b":200,"c":1,"d":12345,"e":2,"f":true,"g":false}""")
        assertSame(queue, queue.withCheckpoint(unrelated))
    }

    @Test fun recoveredLegacyValuesStillObeyIndexPositionAndRepeatBounds() {
        listOf(
            """{"a":100,"b":200,"c":2,"d":12345,"e":2,"f":true,"g":false}""",
            """{"a":100,"b":200,"c":1,"d":-1,"e":2,"f":true,"g":false}""",
            """{"a":100,"b":200,"c":1,"d":12345,"e":3,"f":true,"g":false}""",
        ).forEach { assertSame(queue, queue.withCheckpoint(decode(it))) }
    }

    companion object {
        private const val CANONICAL = """{"queueSavedAtEpochMs":100,"savedAtEpochMs":200,"currentIndex":1,"positionMs":12345,"repeatMode":2,"shuffleModeEnabled":true,"playWhenReady":false}"""
        private const val LEGACY_R8 = """{"a":100,"b":200,"c":1,"d":12345,"e":2,"f":true,"g":false}"""
    }
}
