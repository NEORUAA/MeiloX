package com.ljyh.mei.parasite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Component/audio namespace proof only, not the production MusicService replacement. */
class HostRuntimeProbeService : Service() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadedMedia: ProbeMedia? = null
    private var closing = false
    private var lastReportedSecond = -1L
    private val pcmMeter = Pcm16Meter()
    @Volatile private var pcmSampleRate = 0
    @Volatile private var pcmChannels = 0
    @Volatile private var pcm16 = false
    private val pcmSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            pcmSampleRate = sampleRateHz
            pcmChannels = channelCount
            pcm16 = encoding == C.ENCODING_PCM_16BIT
        }
        override fun handleBuffer(buffer: ByteBuffer) {
            if (pcm16) pcmMeter.record(buffer)
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (stopStalePlayback()) return
            updateState()
            val second = player.currentPosition / 1000
            if (player.isPlaying && second / 5 != lastReportedSecond / 5) {
                HostRuntimeProbe.report("runtime_playback position_ms=${player.currentPosition} playing=true ${pcmReport()}")
                lastReportedSecond = second
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(HostRuntimeProbe.wrap(newBase))

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MeiloX runtime", NotificationManager.IMPORTANCE_LOW),
        )
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(pcmSink)))
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        session = MediaSession(this, "MeiloXRuntimeProbe").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { playMedia() }
                override fun onPause() { player.pause() }
                override fun onStop() { stopSelf() }
            })
            setSessionActivity(activityIntent())
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "MeiloX runtime").build())
            isActive = true
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (closing) return
                val observedMedia = loadedMedia
                // ExoPlayer can invoke this synchronously while playback acceptance owns the session monitor.
                handler.post {
                    if (closing || loadedMedia != observedMedia || stopStalePlayback()) return@post
                    updateState()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                    HostRuntimeProbe.report("runtime_player playing=${player.isPlaying} state=${player.playbackState}")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                HostRuntimeProbe.report("runtime_player_error code=${error.errorCodeName}")
                stopSelf()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    HostRuntimeProbe.report("runtime_playback_ended position_ms=${player.currentPosition} duration_ms=${player.duration} ${pcmReport()}")
                    stopSelf()
                }
            }
        })
        startForeground(NOTIFICATION, notification())
        scope.launch {
            HostRuntimeProbe.playbackState.collect {
                handler.post { if (!closing) stopStalePlayback() }
            }
        }
        handler.post(ticker)
        HostRuntimeProbe.report("runtime_service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> playMedia()
            PAUSE -> player.pause()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        closing = true
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        player.release()
        session.release()
        HostRuntimeProbe.updatePlayback(loadedMedia, false, 0)
        loadedMedia = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        HostRuntimeProbe.report("runtime_service_destroyed")
        super.onDestroy()
    }

    private fun updateState() {
        HostRuntimeProbe.updatePlayback(loadedMedia, player.isPlaying, player.currentPosition)
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(if (player.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                player.currentPosition, if (player.isPlaying) 1f else 0f)
            .build())
    }

    private fun playMedia() {
        if (closing) return
        val accepted = HostRuntimeProbe.withCurrentMedia { media ->
            if (loadedMedia != media) {
                loadedMedia = null
                player.stop()
                player.clearMediaItems()
                loadedMedia = media
                player.setMediaItem(MediaItem.fromUri(media.url))
                player.prepare()
            }
            player.play()
        }
        if (!accepted) {
            HostRuntimeProbe.report("runtime_media_play_rejected")
            retirePlayback()
        }
    }

    private fun stopStalePlayback(): Boolean {
        val media = loadedMedia ?: return false
        if (HostRuntimeProbe.isMediaCurrent(media)) return false
        HostRuntimeProbe.report("runtime_media_retired")
        retirePlayback()
        return true
    }

    private fun retirePlayback() {
        loadedMedia = null
        player.stop()
        player.clearMediaItems()
        stopSelf()
    }

    private fun pcmReport(): String {
        val pcm = pcmMeter.snapshot()
        val frames = pcm.samples / pcmChannels.coerceAtLeast(1)
        return "pcm16=$pcm16 sample_rate=$pcmSampleRate channels=$pcmChannels frames=$frames nonzero=${pcm.nonzeroSamples} peak=${pcm.peak} rms=${String.format(Locale.ROOT, "%.6f", pcm.rms)}"
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 8102, Intent().setClassName(packageName, HostRuntimeProbe.ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(): Notification {
        val action = if (player.isPlaying) PAUSE else PLAY
        val toggle = PendingIntent.getService(this, 8103,
            HostRuntimeProbe.playbackIntent(this, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 8104,
            HostRuntimeProbe.playbackIntent(this, STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MeiloX")
            .setContentText("Runtime playback")
            .setContentIntent(activityIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(player.isPlaying)
            .addAction(Notification.Action.Builder(
                if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (player.isPlaying) "Pause" else "Play", toggle).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    companion object {
        const val PLAY = "com.neoruaa.meilox.parasite.probe.PLAY"
        const val PAUSE = "com.neoruaa.meilox.parasite.probe.PAUSE"
        const val STOP = "com.neoruaa.meilox.parasite.probe.STOP"
        private const val CHANNEL = "meilox_parasite_runtime"
        private const val NOTIFICATION = 8102
    }
}
