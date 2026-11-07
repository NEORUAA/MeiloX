package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.source.api.*
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
    fun initialSourceBindingAndQueueSelectionDoNotCancelRestoredPreparation() {
        fun source(id: String) = object : MusicProvider {
            override val id = id
            override val displayName = id
            override val capabilities = setOf(SourceCapability.PLAYBACK)
        }
        val first = source("first")
        val second = source("second")
        assertFalse(shouldInvalidatePlaybackOwner(null, null, first, "account-A", sameSource = true))
        assertFalse(shouldInvalidatePlaybackOwner(first, "account-A", first, "account-A", sameSource = true))
        assertFalse(shouldInvalidatePlaybackOwner(first, "account-A", second, "account-B", sameSource = false))
        assertTrue(shouldInvalidatePlaybackOwner(first, "account-A", first, "account-B", sameSource = true))
        assertTrue(shouldInvalidatePlaybackOwner(first, "account-A", source("first"), "account-A", sameSource = true))
        assertTrue(shouldInvalidatePlaybackOwner(first, "account-A", null, null, sameSource = true))
    }

    @Test
    fun resolvedCacheSeparatesActualProviderInstanceRecordingQualityAndContentRevision() {
        val origin = TrackRef("netease", "123")
        val target = TrackRef("fixture", "123/: 雪")
        val resolution = PlaybackResolution(origin, target, target.providerId, ProviderInstanceId("fixture:account-a"),
            AudioResource("https://audio.example/track", "lossless", contentId = "revision-a", contentLength = 120), emptyList())
        val key = resolvedPlaybackCacheKey(resolution)
        assertEquals("lossless", resolvedCacheQuality("123", key))
        assertNull(resolvedCacheQuality("124", key))
        assertTrue(key.startsWith(resolvedPlaybackCacheKeyPrefix("123", "lossless")!!))
        assertEquals(target, cachedPlaybackResolution("123", key, "opaque:cached")?.resolvedTrackRef)
        assertEquals(resolution.instanceId, cachedPlaybackResolution("123", key, "opaque:cached")?.instanceId)
        for (changed in listOf(
            resolution.copy(instanceId = ProviderInstanceId("fixture:account-b")),
            resolution.copy(resolvedTrackRef = TrackRef("other", target.trackId), actualAudioProviderId = "other"),
            resolution.copy(resolvedTrackRef = target.copy(trackId = "different")),
            resolution.copy(resource = resolution.resource.copy(quality = "standard")),
            resolution.copy(resource = resolution.resource.copy(contentId = "revision-b")),
        )) assertFalse(key == resolvedPlaybackCacheKey(changed))
        assertEquals("lossless", resolvedCacheQuality("123", playbackCacheKey("123", "lossless", "legacy", 120)))
        assertNull(cachedPlaybackResolution("123", key + ":invalid", "opaque:cached"))
    }

    @Test
    fun consecutiveFailuresRemainBoundedAcrossUnsuccessfulTracks() {
        val failures = PlaybackFailureTracker()
        failures.onMediaItemTransition("entry-0", Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        repeat(4) { index ->
            assertFalse(failures.recordFailure())
            failures.recordProgress("entry-$index", 0L, index * 1_000L, isPlaying = false)
            failures.expectAutomaticSkip("entry-${index + 1}")
            failures.onMediaItemTransition("entry-$index", Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            failures.onMediaItemTransition("entry-${index + 1}", Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            failures.onMediaItemTransition("entry-${index + 1}", Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            assertEquals(index + 1, failures.consecutiveFailures)
        }
        assertTrue(failures.recordFailure())
        assertEquals(5, failures.consecutiveFailures)
        failures.reset()
        assertFalse(failures.recordFailure())
        assertEquals(1, failures.consecutiveFailures)
    }

    @Test
    fun automaticTransitionsKeepFailuresButUserSelectionStartsANewAttempt() {
        val failures = PlaybackFailureTracker()
        failures.recordFailure()
        failures.onMediaItemTransition("next", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(1, failures.consecutiveFailures)
        failures.expectAutomaticSkip("failed-next")
        failures.onMediaItemTransition("selected", Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(0, failures.consecutiveFailures)
        failures.recordFailure()
        failures.onMediaItemTransition("replacement", Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(0, failures.consecutiveFailures)
    }

    @Test
    fun onlySustainedProgressOnTheSameEntryResetsFailures() {
        val failures = PlaybackFailureTracker()
        failures.recordFailure()
        failures.recordProgress("a", 0L, 0L, isPlaying = true)
        failures.recordProgress("a", 0L, 2_000L, isPlaying = true)
        assertEquals(1, failures.consecutiveFailures)
        failures.recordProgress("b", 0L, 3_000L, isPlaying = true)
        failures.recordProgress("b", 1_000L, 4_000L, isPlaying = true)
        assertEquals(1, failures.consecutiveFailures)
        failures.recordProgress("b", 2_000L, 5_000L, isPlaying = true)
        assertEquals(0, failures.consecutiveFailures)
    }

    @Test
    fun pauseAndSeekCannotCountAsSuccessfulPlayback() {
        val failures = PlaybackFailureTracker()
        failures.recordFailure()
        failures.recordProgress("a", 0L, 0L, isPlaying = true)
        failures.recordProgress("a", 1_000L, 1_000L, isPlaying = true)
        failures.recordProgress("a", 1_000L, 2_000L, isPlaying = false)
        failures.recordProgress("a", 1_100L, 3_000L, isPlaying = true)
        failures.resetProgress()
        failures.recordProgress("a", 30_000L, 4_000L, isPlaying = true)
        assertEquals(1, failures.consecutiveFailures)
        failures.recordProgress("a", 31_000L, 5_000L, isPlaying = true)
        assertEquals(1, failures.consecutiveFailures)
        failures.recordProgress("a", 32_000L, 6_000L, isPlaying = true)
        assertEquals(0, failures.consecutiveFailures)
    }

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
        assertEquals(
            listOf("dolby", "sky", "jyeffect", "hires", "lossless", "exhigh", "standard"),
            playbackQualityFallbacks("dolby"),
        )
    }

    @Test
    fun cacheKeysNormalizeQualityAndSeparateSourceRevisions() {
        assertEquals(
            playbackCacheKey("123", "EXHIGH", "ABCDEF", 10L),
            playbackCacheKey(" 123 ", "exhigh", "abcdef", 10L),
        )
        assertEquals(
            "meilox-media-v3:123:exhigh:abcdef",
            playbackCacheKey("123", "EXHIGH", "ABCDEF", 10L),
        )
        assertEquals(
            "meilox-media-v3:123:exhigh:size-1024",
            playbackCacheKey("123", "exhigh", "", 1_024L),
        )
        assertFalse(playbackCacheKey("123", "exhigh", "a", 10L) == "123")
        assertFalse(
            playbackCacheKey("123", "exhigh", "a", 10L) ==
                playbackCacheKey("123", "lossless", "a", 10L),
        )
        assertFalse(
            playbackCacheKey("123", "exhigh", "a", 10L) ==
                playbackCacheKey("123", "exhigh", "b", 10L),
        )
        assertTrue(
            playbackCacheKey("123", "exhigh", "a", 10L)
                .startsWith(playbackCacheKeyPrefix("123", "exhigh")),
        )
        assertTrue(
            playbackCacheKey("123", "exhigh", "a", 10L)
                .startsWith(playbackCacheKeyPrefix("123")),
        )
    }

    @Test
    fun effectiveQualityUsesTheServerLevelWhenFallbackIsUsed() {
        assertEquals("lossless", effectivePlaybackQuality("LOSSLESS", "jymaster"))
        assertEquals("exhigh", effectivePlaybackQuality(null, "EXHIGH"))
        assertEquals("dolby", effectivePlaybackQuality(" ", "DOLBY"))
    }

    @Test
    fun supportedPlaybackLevelsExcludeUnrecognizedServerFormats() {
        for (quality in MusicQuality.entries) {
            assertTrue(isSupportedPlaybackQuality(quality.text))
            assertTrue(isSupportedPlaybackQuality(" ${quality.text.uppercase()} "))
        }
        assertFalse(isSupportedPlaybackQuality(null))
        assertFalse(isSupportedPlaybackQuality(" "))
        assertFalse(isSupportedPlaybackQuality("vivid"))
        assertFalse(isSupportedPlaybackQuality("future-surround"))
    }

    @Test
    fun cachedQualityRequiresTheCurrentSongAndASupportedReportedLevel() {
        for (quality in MusicQuality.entries) {
            assertEquals(
                quality.text,
                cachedPlaybackQuality("123", playbackCacheKey("123", quality.text, "source", 10L)),
            )
        }
        assertNull(cachedPlaybackQuality("123", playbackCacheKey("123", "vivid", "source", 10L)))
        assertNull(cachedPlaybackQuality("123", playbackCacheKey("123", "future-surround", "source", 10L)))
        assertNull(cachedPlaybackQuality("123", playbackCacheKey("456", "dolby", "source", 10L)))
        assertNull(cachedPlaybackQuality("123", "meilox-media-v2:123:dolby:source"))
        assertNull(cachedPlaybackQuality("123", "meilox-media-v3:123:dolby:"))
        assertNull(cachedPlaybackQuality("123", "123"))
    }

    @Test
    fun networkAndExpiredHttpResourcesEnterBoundedRecoveryBeforeFinalSkip() {
        assertTrue(shouldRetryOnlinePlaybackSource(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(shouldRetryOnlinePlaybackSource(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(shouldRetryOnlinePlaybackSource(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(shouldRetryOnlinePlaybackSource(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE))
        assertFalse(shouldRetryOnlinePlaybackSource(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(shouldRetryOnlinePlaybackSource(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
    }

    @Test
    fun outOfRangeReadRefreshesTheCurrentSource() {
        assertTrue(
            shouldRefreshPlaybackSource(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ),
        )
        assertFalse(
            shouldRefreshPlaybackSource(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
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
