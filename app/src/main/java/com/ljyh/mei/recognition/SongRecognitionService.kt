package com.ljyh.mei.recognition

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.ljyh.mei.R
import com.ljyh.mei.source.shared.SourceRecognitionSession
import com.ljyh.mei.source.shared.guard
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@AndroidEntryPoint
class SongRecognitionService : Service() {
    @Inject lateinit var session: SongRecognitionSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notification by lazy { SongRecognitionNotification(this) }
    private var job: Job? = null
    private var playbackPanel: PlaybackPanel? = null
    private var panelUpdates: Job? = null
    private var bindingGuard: Job? = null

    private class PlaybackPanel(
        val projection: MediaProjection,
        val callback: MediaProjection.Callback,
        val token: String,
        val binding: SourceRecognitionSession,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (matchesPanelToken(intent)) job?.cancel()
                stopIfIdle(startId)
            }
            ACTION_STOP_MICROPHONE -> {
                stopMicrophoneRound()
                stopIfIdle(startId)
            }
            ACTION_RUN -> {
                val panel = playbackPanel
                if (panel != null && matchesPanelToken(intent) && job == null &&
                    session.prepare(RecognitionSource.Playback)
                ) {
                    launchRound(panel.binding, panel.projection)
                }
                stopIfIdle(startId)
            }
            ACTION_CLOSE -> {
                if (matchesPanelToken(intent)) closePanel()
                stopIfIdle(startId)
            }
            ACTION_START -> startCapture(intent, startId)
            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent, startId: Int) {
        if (job != null || playbackPanel != null) return
        val binding = session.binding
        val state = session.state.value
        if (binding == null || state.phase != RecognitionPhase.Starting) {
            stopSelf(startId)
            return
        }
        try {
            if (state.source == RecognitionSource.Playback) {
                val consent = checkNotNull(intent.getParcelableExtra(EXTRA_PROJECTION_CONSENT, Intent::class.java))
                openPlaybackPanel(consent, binding)
            } else {
                startForeground(
                    SongRecognitionNotification.ACTIVE_ID,
                    notification.ongoing(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                launchRound(binding)
            }
            bindingGuard = binding.owner.guard(scope) {
                session.invalidateBinding(binding)
                if (state.source == RecognitionSource.Playback) {
                    closePanel(endedMessage = R.string.recognition_source_changed)
                } else {
                    job?.cancel()
                    notification.show(session.fail(getString(R.string.recognition_source_changed)))
                }
            }
        } catch (error: Exception) {
            Timber.w(error, "Could not start song recognition")
            releasePanel()
            if (session.state.value.playbackPanelOpen) session.closePlaybackPanel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            notification.show(session.fail(getString(R.string.recognition_start_failed)))
        }
    }

    private fun openPlaybackPanel(consent: Intent, binding: SourceRecognitionSession) {
        val token = UUID.randomUUID().toString()
        val providerId = binding.owner.providerId
        startForeground(
            SongRecognitionNotification.PANEL_ID,
            notification.panel(session.state.value, token, providerId),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val manager = checkNotNull(getSystemService(MediaProjectionManager::class.java))
        val projection = checkNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent))
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (playbackPanel?.projection === projection) {
                    closePanel(endedMessage = R.string.recognition_capture_ended)
                }
            }
        }
        val panel = PlaybackPanel(projection, callback, token, binding)
        playbackPanel = panel
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        session.openPlaybackPanel()
        panelUpdates = scope.launch {
            var previous: SongRecognitionUiState? = null
            session.state.collect { state ->
                if (!state.playbackPanelOpen) return@collect
                val old = previous
                previous = state
                val outcomeChanged = old?.lastOutcome !== state.lastOutcome
                if (old == null || outcomeChanged || old.phase != state.phase) {
                    notification.updatePanel(
                        state, panel.token, providerId, alert = outcomeChanged && state.lastOutcome != null,
                    )
                }
            }
        }
    }

    private fun launchRound(binding: SourceRecognitionSession, projection: MediaProjection? = null) {
        val recognition = scope.launch(start = CoroutineStart.LAZY) { recognize(binding, projection) }
        job = recognition
        recognition.start()
    }

    private suspend fun recognize(binding: SourceRecognitionSession, projection: MediaProjection?) {
        val recorder = SongRecognitionRecorder(applicationContext)
        val generator = NeteaseFingerprintGenerator(applicationContext)
        var failure: Int? = null
        try {
            session.recognize(
                record = { seconds ->
                    binding.owner.ensureCurrent()
                    recorder.record(seconds, projection)
                },
                fingerprint = { samples ->
                    withTimeoutOrNull(15_000L) { generator.generate(samples) }
                        ?: error("Fingerprint generation timed out")
                },
                match = binding::match,
                notify = { if (projection == null) notification.show(it) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.w(error, "Song recognition failed")
            failure = when (session.state.value.phase) {
                RecognitionPhase.Fingerprinting -> R.string.recognition_fingerprint_failed
                RecognitionPhase.Matching -> R.string.recognition_match_failed
                RecognitionPhase.Starting -> R.string.recognition_start_failed
                else -> R.string.recognition_recording_failed
            }
        } finally {
            runCatching { generator.release() }.onFailure { Timber.w(it, "Could not release the fingerprint runtime") }
            job = null
            if (projection == null || playbackPanel?.projection === projection) {
                val failureMessage = failure
                if (failureMessage == null) {
                    session.cancel()
                } else {
                    val outcome = session.fail(getString(failureMessage))
                    if (projection == null) notification.show(outcome)
                }
            }
            if (projection == null) {
                bindingGuard?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun closePanel(endedMessage: Int? = null) {
        val panel = playbackPanel ?: return
        val running = job
        running?.cancel()
        releasePanel()
        scope.launch {
            running?.join()
            session.closePlaybackPanel()
            if (endedMessage != null) {
                session.fail(getString(endedMessage))
                stopForeground(STOP_FOREGROUND_DETACH)
                notification.showPanelExpired(endedMessage, panel.binding.owner.providerId)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }

    private fun releasePanel() {
        val panel = playbackPanel ?: return
        playbackPanel = null
        bindingGuard?.cancel()
        bindingGuard = null
        panelUpdates?.cancel()
        panelUpdates = null
        runCatching { panel.projection.unregisterCallback(panel.callback) }
        runCatching { panel.projection.stop() }
    }

    private fun matchesPanelToken(intent: Intent): Boolean {
        val token = intent.getStringExtra(EXTRA_PANEL_TOKEN)
        return token == null || token == playbackPanel?.token
    }

    private fun stopIfIdle(startId: Int) {
        if (job == null && !session.state.value.playbackPanelOpen) stopSelf(startId)
    }

    private fun stopMicrophoneRound() {
        if (session.state.value.source == RecognitionSource.Microphone) job?.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopMicrophoneRound()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        releasePanel()
        if (session.state.value.playbackPanelOpen) session.closePlaybackPanel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.ljyh.mei.recognition.START"
        private const val ACTION_STOP = "com.ljyh.mei.recognition.STOP"
        private const val ACTION_STOP_MICROPHONE = "com.ljyh.mei.recognition.STOP_MICROPHONE"
        private const val ACTION_RUN = "com.ljyh.mei.recognition.RUN"
        private const val ACTION_CLOSE = "com.ljyh.mei.recognition.CLOSE"
        private const val EXTRA_PROJECTION_CONSENT = "projection_consent"
        private const val EXTRA_PANEL_TOKEN = "panel_token"

        fun startIntent(context: Context, consent: Intent?): Intent =
            Intent(context, SongRecognitionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECTION_CONSENT, consent)

        fun runIntent(context: Context, token: String? = null): Intent = panelIntent(context, ACTION_RUN, token)

        fun stopIntent(context: Context, token: String? = null): Intent = panelIntent(context, ACTION_STOP, token)
        fun stopMicrophoneIntent(context: Context): Intent = panelIntent(context, ACTION_STOP_MICROPHONE, null)

        fun closeIntent(context: Context, token: String? = null): Intent = panelIntent(context, ACTION_CLOSE, token)

        private fun panelIntent(context: Context, action: String, token: String?): Intent =
            Intent(context, SongRecognitionService::class.java).setAction(action)
                .setIdentifier(token)
                .putExtra(EXTRA_PANEL_TOKEN, token)
    }
}
