package com.ljyh.mei.data.model

import com.google.gson.Gson
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.api.GetSongUrlV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongAudioAvailabilityTest {
    private val gson = Gson()

    @Test
    fun missingOrNullResourceFieldsDoNotInventQualities() {
        assertTrue(track("{}").availableMusicQualities().isEmpty())
        assertTrue(
            track("""{"l":null,"h":null,"sq":null,"hr":null,"je":null,"sk":null,"jm":null,"dl":null}""")
                .availableMusicQualities().isEmpty(),
        )
    }

    @Test
    fun zeroResourcePlaceholdersDoNotAdvertisePlayback() {
        assertTrue(
            track("""{"l":{},"h":{"br":0},"sq":{"size":0},"hr":{"fid":0},"je":{},"sk":{},"jm":{},"dl":{}}""")
                .availableMusicQualities().isEmpty(),
        )
    }

    @Test
    fun normalQualitiesRequireTheirOwnAudioResources() {
        val qualities = track(
            """{"l":{"br":128000},"h":{"size":7500000},"sq":{"fid":123},"hr":null}""",
        ).availableMusicQualities()

        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.EXHIGH, MusicQuality.LOSSLESS),
            qualities,
        )
    }

    @Test
    fun advancedResourceFieldsExposeDistinctSurroundDolbyAndMasterCandidates() {
        val qualities = track(
            """{"hr":{"br":2304000},"je":{"size":12345},"sk":{"fid":123},"jm":{"br":4608000},"dl":{"size":23456}}""",
        ).availableMusicQualities()

        assertEquals(
            listOf(
                MusicQuality.HIRES,
                MusicQuality.JYEFFECT,
                MusicQuality.SKY,
                MusicQuality.JYMASTER,
                MusicQuality.DOLBY,
            ),
            qualities,
        )
    }

    @Test
    fun privilegeFlagsProvideCatalogCandidatesWithoutInventingNormalResources() {
        val privilege = gson.fromJson(
            """{"flag":479232}""",
            PlaylistDetail.Privilege::class.java,
        )

        assertEquals(
            listOf(
                MusicQuality.HIRES,
                MusicQuality.JYEFFECT,
                MusicQuality.SKY,
                MusicQuality.JYMASTER,
                MusicQuality.DOLBY,
            ),
            track("{}").availableMusicQualities(privilege),
        )
    }

    @Test
    fun resourceAndFlagHintsAreNotDuplicated() {
        val privilege = gson.fromJson(
            """{"flag":81920}""",
            PlaylistDetail.Privilege::class.java,
        )

        assertEquals(
            listOf(MusicQuality.JYMASTER, MusicQuality.DOLBY),
            track("""{"jm":{"size":123},"dl":{"size":456}}""")
                .availableMusicQualities(privilege),
        )
    }

    @Test
    fun dolbyCapabilityIsSentOnlyForDolbyRequests() {
        val normal = gson.toJsonTree(GetSongUrlV1("[1]", "jymaster")).asJsonObject
        val dolby = gson.toJsonTree(GetSongUrlV1("[1]", "dolby")).asJsonObject
        val sky = GetSongUrlV1("[1]", "sky")

        assertFalse(normal.has("supportDolby"))
        assertTrue(dolby.get("supportDolby").asBoolean)
        assertEquals("c51", sky.immerseType)
        assertNull(sky.supportDolby)
    }

    @Test
    fun addingDolbyPreservesExistingQualityOrdinals() {
        assertEquals(0, MusicQuality.STANDARD.ordinal)
        assertEquals(6, MusicQuality.JYMASTER.ordinal)
        assertEquals(7, MusicQuality.DOLBY.ordinal)
    }

    private fun track(json: String): PlaylistDetail.Playlist.Track =
        gson.fromJson(json, PlaylistDetail.Playlist.Track::class.java)
}
