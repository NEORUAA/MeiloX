package com.ljyh.mei.ui.screen.recognition

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.RecognitionPhase
import com.ljyh.mei.recognition.RecognitionSource
import com.ljyh.mei.recognition.SongRecognitionNotification
import com.ljyh.mei.recognition.SongRecognitionService
import com.ljyh.mei.recognition.SongRecognitionSession
import com.ljyh.mei.recognition.SongRecognitionUiState
import com.ljyh.mei.source.shared.SourceRecognitionRepository
import com.ljyh.mei.source.shared.SourceRecognitionSession
import com.ljyh.mei.source.shared.guard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class SongRecognitionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SongRecognitionSession,
    private val repository: SourceRecognitionRepository,
) : ViewModel() {
    private data class Binding(val source: SourceRecognitionSession? = null, val error: String? = null)

    private val binding = MutableStateFlow(Binding())
    private var bindingJob: Job? = null

    val state = combine(session.state, binding) { capture, page ->
        val sameBinding = page.source?.let(session::matchesBinding) == true
        capture.copy(
            phase = if (page.source == null && page.error != null) RecognitionPhase.Failed else capture.phase,
            results = if (sameBinding) capture.results else emptyList(),
            error = page.error ?: capture.error,
            isAvailable = page.source != null && (sameBinding || !capture.isWorking && !capture.playbackPanelOpen),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SongRecognitionUiState(isAvailable = false))

    fun open(providerId: String?) {
        if (bindingJob != null) return
        bindingJob = viewModelScope.launch {
            try {
                val source = repository.open(providerId)
                binding.value = Binding(source)
                source.owner.guard(viewModelScope) {
                    binding.value = Binding(error = context.getString(R.string.recognition_source_changed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                binding.value = Binding(error = error.message ?: context.getString(R.string.recognition_start_failed))
            }
        }
    }

    fun media(song: RecognizedSong, onReady: (MediaItem) -> Unit) {
        val source = binding.value.source ?: return
        viewModelScope.launch {
            try {
                onReady(source.media(song))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                binding.value = binding.value.copy(error = error.message)
            }
        }
    }

    fun selectSource(source: RecognitionSource) = session.selectSource(source)

    fun selectDuration(duration: RecognitionDuration) = session.selectDuration(duration)

    fun start(source: RecognitionSource, consent: Intent? = null) {
        val owner = binding.value.source ?: return
        if (!session.prepare(source, owner)) return
        binding.value = Binding(owner)
        try {
            ContextCompat.startForegroundService(context, SongRecognitionService.startIntent(context, consent))
        } catch (error: Exception) {
            Timber.w(error, "Could not launch song recognition service")
            val failure = session.fail(context.getString(R.string.recognition_start_failed))
            SongRecognitionNotification(context).show(failure)
        }
    }

    fun showStartFailure(@StringRes message: Int) {
        if (!session.state.value.isWorking) session.fail(context.getString(message))
    }

    fun stop() = sendCommand(SongRecognitionService.stopIntent(context))

    fun startPlaybackRound() {
        val owner = binding.value.source ?: return
        if (session.matchesBinding(owner)) sendCommand(SongRecognitionService.runIntent(context))
    }

    fun closePlaybackPanel() = sendCommand(SongRecognitionService.closeIntent(context))

    override fun onCleared() {
        val current = session.state.value
        val owner = binding.value.source ?: return
        if (session.binding === owner && current.source == RecognitionSource.Microphone && current.isWorking) {
            sendCommand(SongRecognitionService.stopMicrophoneIntent(context))
        }
    }

    private fun sendCommand(intent: Intent) {
        try {
            context.startService(intent)
        } catch (error: IllegalStateException) {
            Timber.w(error, "Could not send song recognition command")
        }
    }
}
