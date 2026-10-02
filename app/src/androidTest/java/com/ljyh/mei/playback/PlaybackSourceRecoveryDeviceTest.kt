package com.ljyh.mei.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.toMediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** Actual service callbacks with empty decks; no registered lifecycle, files or network. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackSourceRecoveryDeviceTest {
    private val cloud = SongSourceIdentity(999, 88, 7, 17)

    @Test fun sameSourceRepeatCannotCancelRecoveryOrReopenItsBudget() = withService { service, recovery ->
        val job = seed(recovery, cloud.key)
        service.onMediaItemTransition(item(cloud), Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertSame(job, recovery.job)
        assertTrue(job.isActive)
        assertEquals(cloud.key, recovery.sourceKey)
        assertEquals(1, recovery.attempts)
        assertFalse(recovery.tryBeginAttempt())
    }

    @Test fun sameEntryReplacementCancelsOnlyTheRetiredSourceJob() = withService { service, recovery ->
        for (source in listOf(cloud.copy(cloudOwnerId = 89), cloud.copy(accountId = 8),
            cloud.copy(songId = 1000))) {
            val job = seed(recovery, cloud.key)
            val replacement = item(source)
            assertEquals("17", replacement.mediaId)
            service.onMediaItemTransition(replacement, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            assertTrue(job.isCancelled)
            assertNull(recovery.job)
            assertEquals(source.key, recovery.sourceKey)
            assertEquals(0, recovery.attempts)
            assertTrue(recovery.tryBeginAttempt())
        }
    }

    @Test fun publicPrivateAndEmptyTransitionsKeepSeparateRecoveryBudgets() = withService { service, recovery ->
        var job = seed(recovery, "17")
        service.onMediaItemTransition(item(cloud), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(job.isCancelled)
        assertEquals(cloud.key, recovery.sourceKey)
        job = seed(recovery, cloud.key)
        service.onMediaItemTransition(MediaItem.Builder().setMediaId("17").build(),
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(job.isCancelled)
        assertEquals("17", recovery.sourceKey)
        job = seed(recovery, "17")
        service.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(job.isCancelled)
        assertNull(recovery.sourceKey)
        assertNull(recovery.job)
        assertFalse(recovery.tryBeginAttempt())
    }

    @Test fun conflictingSourceMetadataRetiresRecoveryWithoutEscapingTheCallback() = withService { service, recovery ->
        val job = seed(recovery, cloud.key)
        val conflicting = item(cloud).buildUpon().setCustomCacheKey("17").build()
        service.onMediaItemTransition(conflicting, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(job.isCancelled)
        assertNull(recovery.job)
        assertNull(recovery.sourceKey)
        assertFalse(recovery.tryBeginAttempt())
    }

    private fun item(source: SongSourceIdentity) = MediaMetadata(
        source.entryId, "Synthetic", "", emptyList(), 1000,
        MediaMetadata.Album(1, "Test"), source = source,
    ).toMediaItem()

    private fun seed(recovery: PlaybackSourceRecovery, key: String): Job {
        recovery.clear()
        recovery.selectSource(key)
        assertTrue(recovery.tryBeginAttempt())
        return Job().also { recovery.job = it }
    }

    private fun withService(block: (MusicService, PlaybackSourceRecovery) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                fun deck() = ExoPlayer.Builder(instrumentation.targetContext)
                    .setAudioAttributes(AudioAttributes.DEFAULT, false).build().apply { volume = 0f }
                val player = StableDeckPlayer(deck(), deck(), AudioAttributes.DEFAULT)
                val service = MusicService().apply { this.player = player }
                val recovery = MusicService::class.java.getDeclaredField("sourceRecovery").apply {
                    isAccessible = true
                }.get(service) as PlaybackSourceRecovery
                try { block(service, recovery) }
                finally { recovery.clear(); service.scope.cancel(); player.release() }
            }
        }
        requireNotNull(result).getOrThrow()
    }
}
