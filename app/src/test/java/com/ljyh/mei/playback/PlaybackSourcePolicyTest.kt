package com.ljyh.mei.playback

import com.ljyh.mei.data.model.SongUrl
import com.ljyh.mei.data.model.isFullSourceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourcePolicyTest {
    @Test
    fun qualityFallbacksFollowTheRequestedQuality() {
        assertEquals(
            listOf("jymaster", "hires", "lossless", "exhigh", "standard"),
            playbackQualityFallbacks("JYMASTER"),
        )
        assertEquals(
            listOf("jyeffect", "lossless", "exhigh", "standard"),
            playbackQualityFallbacks("jyeffect"),
        )
        assertEquals(listOf("standard"), playbackQualityFallbacks(" standard "))
    }

    @Test
    fun cacheKeysNormalizeQualityAndSeparateVersions() {
        assertEquals(
            playbackCacheKey("123", "EXHIGH"),
            playbackCacheKey(" 123 ", "exhigh"),
        )
        assertEquals("meilox-media-v2:123:exhigh", playbackCacheKey("123", "EXHIGH"))
        assertFalse(playbackCacheKey("123", "exhigh") == "123")
        assertFalse(playbackCacheKey("123", "exhigh") == playbackCacheKey("123", "lossless"))
    }

    @Test
    fun effectiveQualityUsesTheServerLevelWhenFallbackIsUsed() {
        assertEquals("lossless", effectivePlaybackQuality("LOSSLESS", "jymaster"))
        assertEquals("exhigh", effectivePlaybackQuality(null, "EXHIGH"))
    }

    @Test
    fun fullSourceRequiresMatchingSuccessfulNonTrialSource() {
        val source = source()
        assertNotNull(SongUrl(200, listOf(source)).fullSourceFor("123"))
        assertNull(SongUrl(500, listOf(source)).fullSourceFor("123"))
        assertNull(SongUrl(200, listOf(source.copy(id = 456))).fullSourceFor("123"))
        assertNull(SongUrl(200, listOf(source.copy(code = 403))).fullSourceFor("123"))
        assertNull(SongUrl(200, listOf(source.copy(url = " "))).fullSourceFor("123"))
        assertNull(
            SongUrl(
                200,
                listOf(source.copy(freeTrialInfo = SongUrl.Data.FreeTrialInfo(end = 30))),
            ).fullSourceFor("123")
        )
    }

    @Test
    fun nonPaidFlagDoesNotRejectAFullSource() {
        assertTrue(source(payed = 0).isFullSourceFor("123"))
    }

    private fun source(
        id: Long = 123,
        code: Int = 200,
        url: String? = "https://example.invalid/song.flac",
        freeTrialInfo: SongUrl.Data.FreeTrialInfo? = null,
        payed: Int = 1,
    ) = SongUrl.Data(
        br = 320_000,
        canExtend = false,
        channelLayout = "stereo",
        closedGain = 0,
        closedPeak = 0,
        code = code,
        effectTypes = emptyList<Any>(),
        encodeType = "flac",
        expi = 7_200,
        fee = 0,
        flag = 0,
        freeTimeTrialPrivilege = SongUrl.Data.FreeTimeTrialPrivilege(
            remainTime = 0,
            resConsumable = false,
            type = 0,
            userConsumable = false,
        ),
        freeTrialInfo = freeTrialInfo,
        freeTrialPrivilege = SongUrl.Data.FreeTrialPrivilege(
            cannotListenReason = 0,
            freeLimitTagType = 0,
            listenType = 0,
            playReason = 0,
            resConsumable = false,
            userConsumable = false,
        ),
        gain = 0,
        id = id,
        level = "exhigh",
        levelConfuse = 0,
        md5 = "",
        message = "",
        musicId = id.toString(),
        payed = payed,
        peak = 0.0,
        podcastCtrp = 0,
        rightSource = 0,
        size = 1_024,
        time = 240_000,
        type = "flac",
        uf = 0,
        url = url,
        urlSource = 0,
    )
}
