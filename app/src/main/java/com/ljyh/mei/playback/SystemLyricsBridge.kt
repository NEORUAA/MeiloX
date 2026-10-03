package com.ljyh.mei.playback

import android.content.Context
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.ljyh.mei.constants.LyricRomanizationEnabledKey
import com.ljyh.mei.constants.LyricTranslationEnabledKey
import com.ljyh.mei.constants.SystemLyricsEnabledKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.lyric.LyricManager
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.service.addConnectionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/** Publishes the same lyric timeline from the playback service, including while the UI is absent. */
internal class SystemLyricsBridge(
    private val context: Context,
    private val player: StableDeckPlayer,
    private val lyricManager: LyricManager,
    session: MediaSession,
) : Player.Listener {
    private val colorOsPublisher = ColorOsLyricsPublisher(session)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val superLyricPublisher = SuperLyricPublisher(player, scope)
    private var provider: LyriconProvider? = null
    private var enabled = false
    private var displayTranslation = true
    private var displayRoma = true
    private var metadata: MediaMetadata? = null
    private var publishedLyrics: LyricData? = null
    private var generation = 0L

    init {
        player.addListener(this)
        scope.launch {
            context.dataStore.data.map {
                Triple(
                    it[SystemLyricsEnabledKey] ?: true,
                    it[LyricTranslationEnabledKey] ?: true,
                    it[LyricRomanizationEnabledKey] ?: true,
                )
            }.distinctUntilChanged().collect { (newEnabled, translation, roma) ->
                displayTranslation = translation
                displayRoma = roma
                if (newEnabled != enabled) {
                    enabled = newEnabled
                    if (enabled) {
                        startProvider()
                        syncTrack()
                    } else {
                        superLyricPublisher.stop()
                        stopProvider()
                        colorOsPublisher.setLyrics(null, null)
                        metadata = null
                        publishedLyrics = null
                    }
                }
                if (enabled) publishLyrics(force = true)
            }
        }
        scope.launch {
            lyricManager.lyricData.collect {
                if (enabled) publishLyrics()
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
        ) {
            syncTrack()
        }
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
            )
        ) {
            syncPlayback(events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }
    }

    private fun startProvider() {
        withProvider {
            provider = LyriconFactory.createProvider(context.applicationContext).also { created ->
                created.autoSync = true
                created.service.addConnectionListener {
                    onConnected { scope.launch { if (enabled) publishLyrics(force = true) } }
                    onReconnected { scope.launch { if (enabled) publishLyrics(force = true) } }
                }
                created.register()
            }
        }
    }

    private fun syncTrack() {
        val current = player.currentMediaItem?.metadata
        if (current == metadata) return
        if (current?.id != metadata?.id) generation++
        metadata = current
        publishedLyrics = null
        colorOsPublisher.setLyrics(null, null)
        // Clear the old timeline before any asynchronous lyric request can finish.
        superLyricPublisher.setSong(null, null, displayTranslation, displayRoma)
        withProvider { provider?.player?.setSong(current?.toSystemLyricSong(null)) }
        if (current != null) lyricManager.loadLyrics(current)
        publishLyrics(force = true)
    }

    private fun publishLyrics(force: Boolean = false) {
        val current = metadata ?: run {
            superLyricPublisher.setSong(null, null, displayTranslation, displayRoma)
            withProvider { provider?.player?.setSong(null) }
            syncPlayback()
            return
        }
        if (player.currentMediaItem?.mediaId != current.id.toString()) return
        if (lyricManager.songId != current.id.toString()) return
        val data = lyricManager.lyricData.value
        if (!force && publishedLyrics === data) return
        publishedLyrics = data
        val song = current.toSystemLyricSong(data)
        superLyricPublisher.setSong(song, current.album.title, displayTranslation, displayRoma)
        colorOsPublisher.setLyrics(
            current.id.toString(),
            song.toColorOsLyricInfo(context.packageName, generation),
        )
        withProvider {
            provider?.player?.apply {
                setSong(song)
                setDisplayTranslation(displayTranslation)
                setDisplayRoma(displayRoma)
            }
        }
        syncPlayback()
    }

    private fun syncPlayback(seek: Boolean = false) {
        superLyricPublisher.syncPlayback()
        withProvider {
            val remote = provider?.player ?: return@withProvider
            val position = player.currentPosition.coerceAtLeast(0L)
            val state = when {
                player.isPlaying -> PlaybackState.STATE_PLAYING
                player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
                player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
                else -> PlaybackState.STATE_PAUSED
            }
            remote.setPosition(position)
            if (seek) remote.seekTo(position)
            // Let the receiver advance its clock without a polling loop or UI updates.
            remote.setPlaybackState(
                PlaybackState.Builder().setState(
                    state,
                    position,
                    if (player.isPlaying) player.playbackParameters.speed else 0f,
                    SystemClock.elapsedRealtime(),
                ).build(),
            )
        }
    }

    private fun stopProvider() {
        val current = provider ?: return
        provider = null
        withProvider { current.player.setPlaybackState(false) }
        withProvider { current.player.setSong(null) }
        withProvider { current.destroy() }
    }

    private inline fun withProvider(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            // An unavailable external lyric service must never interrupt audio playback.
            Timber.w(error, "System lyric provider operation failed")
        }
    }

    fun release() {
        enabled = false
        player.removeListener(this)
        scope.cancel()
        superLyricPublisher.stop()
        stopProvider()
        colorOsPublisher.release()
    }
}
