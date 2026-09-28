package com.ljyh.mei.ui.screen.recognition

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.data.repository.SongRecognitionRepository
import com.ljyh.mei.recognition.NeteaseFingerprintGenerator
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.SongRecognitionRecorder
import com.ljyh.mei.di.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecognitionPhase { Ready, Listening, Fingerprinting, Matching, Results, NoMatch, Failed }

data class SongRecognitionUiState(
    val duration: RecognitionDuration = RecognitionDuration.Balanced,
    val phase: RecognitionPhase = RecognitionPhase.Ready,
    val results: List<RecognizedSong> = emptyList(),
    val error: String? = null,
) {
    val isWorking: Boolean get() = phase in setOf(
        RecognitionPhase.Listening,
        RecognitionPhase.Fingerprinting,
        RecognitionPhase.Matching,
    )
}

class SongRecognitionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: SongRecognitionRepository,
) : ViewModel() {
    private val recorder = SongRecognitionRecorder(context)
    private val generator = NeteaseFingerprintGenerator(context)
    private val _state = MutableStateFlow(SongRecognitionUiState())
    val state = _state.asStateFlow()
    private var recognitionJob: Job? = null

    fun selectDuration(duration: RecognitionDuration) {
        if (!_state.value.isWorking) _state.value = _state.value.copy(duration = duration)
    }

    fun start() {
        if (_state.value.isWorking) return
        val duration = _state.value.duration
        recognitionJob = viewModelScope.launch {
            try {
                do {
                    val seconds = duration.seconds ?: 9
                    _state.value = _state.value.copy(phase = RecognitionPhase.Listening, error = null)
                    val samples = recorder.record(seconds)
                    _state.value = _state.value.copy(phase = RecognitionPhase.Fingerprinting)
                    val fingerprint = generator.generate(samples)
                    _state.value = _state.value.copy(phase = RecognitionPhase.Matching)
                    val matches = repository.match(fingerprint, seconds)
                    if (matches.isNotEmpty()) {
                        val merged = (matches + _state.value.results).distinctBy(RecognizedSong::id).take(50)
                        _state.value = _state.value.copy(
                            phase = if (duration == RecognitionDuration.Continuous) RecognitionPhase.Listening else RecognitionPhase.Results,
                            results = merged,
                        )
                    } else if (duration != RecognitionDuration.Continuous) {
                        _state.value = _state.value.copy(phase = RecognitionPhase.NoMatch)
                    }
                } while (duration == RecognitionDuration.Continuous && currentCoroutineContext().isActive)
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(phase = RecognitionPhase.Ready)
            } catch (error: Exception) {
                _state.value = _state.value.copy(phase = RecognitionPhase.Failed, error = error.message)
            }
        }
    }

    fun stop() {
        recognitionJob?.cancel()
        recognitionJob = null
        _state.value = _state.value.copy(
            phase = if (_state.value.results.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results,
        )
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        generator.release()
    }
}
