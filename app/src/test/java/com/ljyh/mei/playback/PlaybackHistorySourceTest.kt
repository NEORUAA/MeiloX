package com.ljyh.mei.playback

import com.ljyh.mei.source.api.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHistorySourceTest {
    @Test
    fun everySourceReportsOnlyItsOwnOriginalRecordingWhileLocalHistoryKeepsTheOrigin() {
        for (providerId in listOf(ProviderIds.NETEASE, "qq", "plugin:opaque")) {
            val origin = TrackRef(providerId, "123")
            val actual = TrackRef("fixture", "123")
            val fallback = PlaybackResolution(origin, actual, actual.providerId, ProviderInstanceId("fixture"),
                AudioResource("https://example.test/audio", "lossless"), emptyList())
            assertEquals(origin, TrackRefCodec.decode(TrackRefCodec.storageKey(fallback.originTrackRef)))
            assertFalse(canReportProviderPlayback(origin, fallback))
            assertTrue(canReportProviderPlayback(origin, null))
            assertTrue(canReportProviderPlayback(origin,
                fallback.copy(resolvedTrackRef = origin, actualAudioProviderId = providerId)))
            assertFalse(canReportProviderPlayback(origin,
                fallback.copy(resolvedTrackRef = origin.copy(trackId = "456"), actualAudioProviderId = providerId)))
            assertFalse(canReportProviderPlayback(origin,
                fallback.copy(resolvedTrackRef = origin, actualAudioProviderId = "fixture")))
        }
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
