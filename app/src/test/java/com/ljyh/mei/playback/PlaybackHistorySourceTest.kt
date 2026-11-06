package com.ljyh.mei.playback

import com.ljyh.mei.source.api.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHistorySourceTest {
    @Test
    fun fallbackKeepsLocalOriginButNeverReportsForeignAudioAsOfficialPlayback() {
        val origin = TrackRef(ProviderIds.NETEASE, "123")
        val actual = TrackRef("fixture", "123")
        val fallback = PlaybackResolution(origin, actual, actual.providerId, ProviderInstanceId("fixture"),
            AudioResource("https://example.test/audio", "lossless"), emptyList())
        assertEquals("123", TrackRefCodec.storageKey(fallback.originTrackRef))
        assertFalse(canReportNeteasePlayback(origin, fallback))
        assertTrue(canReportNeteasePlayback(origin, null))
        assertTrue(canReportNeteasePlayback(origin,
            fallback.copy(resolvedTrackRef = origin, actualAudioProviderId = ProviderIds.NETEASE)))
        assertFalse(canReportNeteasePlayback(origin,
            fallback.copy(resolvedTrackRef = origin.copy(trackId = "456"), actualAudioProviderId = ProviderIds.NETEASE)))
        assertFalse(canReportNeteasePlayback(actual, null))
    }

    @Test
    fun validSongIdUsesTrackSource() {
        assertEquals(
            PlaybackHistorySource(123L, "track"),
            resolvePlaybackHistorySource(songId = 123L),
        )
    }

    @Test
    fun invalidSongIdDoesNotEmitAZeroSource() {
        assertNull(resolvePlaybackHistorySource(songId = 0L))
        assertNull(resolvePlaybackHistorySource(songId = -1L))
    }

    @Test
    fun localHistoryRetainsTheStoredFileIdentityWithoutChangingCatalogOrPodcastIds() {
        assertEquals("local_42", playbackHistoryStorageId("-42", isLocal = true))
        assertEquals("local_42", playbackHistoryStorageId("local_42", isLocal = true))
        assertEquals("42", playbackHistoryStorageId("42", isLocal = false))
        assertEquals("-42", playbackHistoryStorageId("-42", isLocal = false))
        assertEquals(Long.MIN_VALUE.toString(), playbackHistoryStorageId(Long.MIN_VALUE.toString(), isLocal = true))
    }
}
