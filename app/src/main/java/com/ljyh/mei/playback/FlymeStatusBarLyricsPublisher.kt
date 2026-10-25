package com.ljyh.mei.playback

import android.content.Context
import androidx.media3.common.Player
import com.ljyh.mei.constants.FlymeStatusBarLyricsEnabledKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.lyric.LyricManager
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Publishes timed lines in the service, independently of other lyric sharing services and ROM. */
internal class FlymeStatusBarLyricsPublisher(
    context: Context,
    private val player: Player,
    private val lyricManager: LyricManager,
    private val notificationProvider: FlymeLyricsNotificationProvider,
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var enabled = false
    private var metadata: MediaMetadata? = null
    private var song: Song? = null
    private var updateJob: Job? = null

    init {
        player.addListener(this)
        scope.launch {
            context.dataStore.data.map { it[FlymeStatusBarLyricsEnabledKey] ?: false }
                .distinctUntilChanged().collect {
                    enabled = it
                    if (enabled) syncTrack() else {
                        metadata = null
                        song = null
                        restartUpdates()
                    }
                }
        }
        scope.launch {
            lyricManager.lyricData.collect {
                if (enabled) syncLyrics()
            }
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!enabled) return
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_METADATA_CHANGED,
            )
        ) syncTrack()
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
            )
        ) restartUpdates()
    }

    private fun syncTrack() {
        val current = player.currentMediaItem?.metadata
        if (current != metadata) {
            metadata = current
            song = null
            publish(null)
        }
        if (current != null) lyricManager.loadLyrics(current)
        syncLyrics()
    }

    private fun syncLyrics() {
        song = metadata?.takeIf {
            player.currentMediaItem?.mediaId == it.id.toString() &&
                lyricManager.songId == it.id.toString()
        }?.toSystemLyricSong(lyricManager.lyricData.value)
        restartUpdates()
    }

    private fun restartUpdates() {
        updateJob?.cancel()
        updateJob = null
        publishCurrentLine()
        if (!enabled || !player.isPlaying || song?.lyrics.isNullOrEmpty()) return
        updateJob = scope.launch {
            while (isActive && enabled && player.isPlaying) {
                val position = player.currentPosition.coerceAtLeast(0L)
                val nextBoundary = song?.lyrics.orEmpty().asSequence()
                    .flatMap { sequenceOf(it.begin, it.end) }
                    .filter { it > position }.minOrNull()
                val waitMs = nextBoundary?.let {
                    ((it - position) / player.playbackParameters.speed.toDouble()).toLong()
                } ?: 1_000L
                delay(waitMs.coerceIn(20L, 1_000L))
                publishCurrentLine()
            }
        }
    }

    private fun publishCurrentLine() {
        val current = song
        val canPublish = enabled && player.isPlaying &&
            current?.id == player.currentMediaItem?.mediaId
        publish(current?.flymeLyricAt(player.currentPosition.coerceAtLeast(0L), canPublish))
    }

    private fun publish(text: String?) {
        try {
            notificationProvider.setLyric(text)
        } catch (error: Exception) {
            // A rejected notification must not affect audio playback.
            Timber.w(error, "Flyme lyric notification update failed")
        }
    }

    fun release() {
        enabled = false
        player.removeListener(this)
        scope.cancel()
        publish(null)
    }
}

internal fun Song.flymeLyricAt(positionMs: Long, active: Boolean): String? =
    if (!active) null else lyrics.orEmpty().lastOrNull {
        positionMs >= it.begin && positionMs < it.end
    }?.text?.takeIf(String::isNotBlank)
