package com.ljyh.mei.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 metadata and timers, with a closed sink and no account/statistics writes. */
@RunWith(AndroidJUnit4::class)
class CloudPlaybackReportDeviceTest {
    private val source = SongSourceIdentity(999, 88, 7, 17)
    private fun metadata(source: SongSourceIdentity? = this.source) = MediaMetadata(
        source?.entryId ?: 17, "Synthetic", "", emptyList(), 120_000, MediaMetadata.Album(1, "Test"), source = source,
    )

    @Test fun realMediaItemsKeepFullTimerKeysAndUseAudioIdentityForReporting() {
        val item = metadata().toMediaItem()
        assertEquals("17", item.mediaId)
        assertEquals(source.key, item.playbackHistoryKey)
        assertEquals(source, item.playbackHistorySourceIdentityOrNull())
        val ordinary = metadata(null).toMediaItem()
        assertEquals("17", ordinary.playbackHistoryKey)
        assertEquals(SongSourceIdentity(17), ordinary.playbackHistorySourceIdentityOrNull())
    }

    @Test fun conflictingSourcesFailClosedInsteadOfFallingBackToTheVisibleEntry() {
        val item = metadata().toMediaItem().buildUpon().setCustomCacheKey("17").build()
        assertNull(item.playbackHistoryKey)
        assertNull(item.playbackHistorySourceIdentityOrNull())
    }

    @Test fun localAndPodcastTimersRemainUsableWithoutCreatingSongReports() {
        for (metadata in listOf(metadata(null).copy(isLocal = true), metadata(null).copy(isPodcast = true))) {
            val item = metadata.toMediaItem()
            assertEquals("17", item.playbackHistoryKey)
            assertNull(item.playbackHistorySourceIdentityOrNull())
        }
    }

    @Test fun sameEntryReplacementFinishesTheOldPrivateFileAndIgnoresPausedTime() {
        val item = metadata().toMediaItem()
        val next = metadata(source.copy(cloudOwnerId = 89)).toMediaItem()
        val timer = PlaybackHistorySession()
        val started = 1_700_000_000_123L
        assertEquals(started, timer.update(item.playbackHistoryKey, true, started, 0).startedAtMs)
        timer.update(item.playbackHistoryKey, false, started + 35_000, 35_000)
        timer.update(item.playbackHistoryKey, true, started + 40_000, 40_000)
        val completion = checkNotNull(timer.onMediaItemTransition(next.playbackHistoryKey,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 75_000))
        assertEquals(source.key, completion.mediaId)
        assertEquals(70_000L, completion.playedDurationMs)
        assertEquals(started, completion.startedAtMs)
        assertEquals(started + 75_000, timer.update(next.playbackHistoryKey, true, started + 75_000, 75_000).startedAtMs)
    }

    @Test fun mediaItemToReporterPipelineChecksTheCapturedAccountBeforeClosedDelivery() {
        var account = SessionIdentity(7, true, false)
        val sessions = SessionStore().apply { bind { account } }
        val ids = mutableListOf<Long>()
        val sink = object : PlaybackReportSink {
            override val sessions = sessions
            override fun requireOwner(owner: SessionStamp) {
                sessions.requireCurrent(owner)
                if (sessions.recoveryRequired.value) throw SessionChangedException()
            }
            override suspend fun submit(action: String, fields: Map<String, Any>, owner: SessionStamp, details: PlaybackReportDetails) {
                requireReportSource(fields, owner, details)
                ids += fields["id"] as Long
            }
        }
        val reporter = PlaybackHistoryReporter(sink, Dispatchers.Unconfined)
        val item = metadata().toMediaItem()
        val started = 1_700_000_000_123L
        try {
            reporter.recordStart(checkNotNull(item.playbackHistoryKey), checkNotNull(item.playbackHistorySourceIdentityOrNull()),
                checkNotNull(resolvePlaybackHistorySource(source.songId)), started)
            assertEquals(listOf(999L), ids)
            account = SessionIdentity(8, true, false)
            reporter.recordDuration(CompletedPlaybackHistorySession(source.key, 45_000, started, "ui"), started + 50_000)
            reporter.recordStart(source.key, source, PlaybackHistorySource(999, "track"), started + 50_001)
            assertEquals(listOf(999L), ids)
        } finally { reporter.close() }
    }
}
