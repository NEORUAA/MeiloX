package com.ljyh.mei.ui.screen.recognition

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.source.netease.repository.SongRecognitionRepository
import com.ljyh.mei.recognition.NeteaseFingerprintGenerator
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.SongRecognitionRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

@HiltViewModel
class SongRecognitionViewModel internal constructor(
    private val record: suspend (Int) -> FloatArray,
    private val generate: suspend (FloatArray) -> String,
    private val match: suspend (String, Int) -> List<RecognizedSong>,
    private val release: () -> Unit,
) : ViewModel() {
    private constructor(
        context: Context,
        repository: SongRecognitionRepository,
        generator: NeteaseFingerprintGenerator,
    ) : this(SongRecognitionRecorder(context)::record, generator::generate, repository::match, generator::release)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: SongRecognitionRepository,
    ) : this(context, repository, NeteaseFingerprintGenerator(context))

    private val _state = MutableStateFlow(SongRecognitionUiState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var revision = 0L
    private var recognitionJob: Job? = null

    fun selectDuration(duration: RecognitionDuration) {
        synchronized(lock) {
            if (!_state.value.isWorking) _state.value = _state.value.copy(duration = duration)
        }
    }

    fun start() {
        val job = synchronized(lock) {
            if (_state.value.isWorking) return
            val expected = ++revision
            val duration = _state.value.duration
            _state.value = _state.value.copy(phase = RecognitionPhase.Listening, error = null)
            viewModelScope.launch(start = CoroutineStart.LAZY) { recognize(expected, duration) }
                .also { recognitionJob = it }
        }
        job.start()
    }

    private suspend fun recognize(expected: Long, duration: RecognitionDuration) {
        try {
            do {
                currentCoroutineContext().ensureActive()
                val seconds = duration.seconds ?: 9
                if (!publish(expected) { it.copy(phase = RecognitionPhase.Listening, error = null) }) return
                val samples = record(seconds)
                currentCoroutineContext().ensureActive()
                if (!publish(expected) { it.copy(phase = RecognitionPhase.Fingerprinting) }) return
                val fingerprint = generate(samples)
                currentCoroutineContext().ensureActive()
                if (!publish(expected) { it.copy(phase = RecognitionPhase.Matching) }) return
                val matches = match(fingerprint, seconds)
                currentCoroutineContext().ensureActive()
                if (!publish(expected) { current ->
                    if (matches.isNotEmpty()) current.copy(
                        phase = if (duration == RecognitionDuration.Continuous) RecognitionPhase.Listening else RecognitionPhase.Results,
                        results = (matches + current.results).distinctBy(RecognizedSong::id).take(50),
                    ) else if (duration != RecognitionDuration.Continuous) current.copy(phase = RecognitionPhase.NoMatch)
                    else current
                }) return
            } while (duration == RecognitionDuration.Continuous && currentCoroutineContext().isActive)
        } catch (_: CancellationException) {
            publish(expected) { it.copy(phase = if (it.results.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results) }
        } catch (error: Exception) {
            publish(expected) { it.copy(phase = RecognitionPhase.Failed, error = error.message) }
        } finally {
            synchronized(lock) {
                if (revision == expected) recognitionJob = null
            }
        }
    }

    private fun publish(expected: Long, update: (SongRecognitionUiState) -> SongRecognitionUiState): Boolean =
        synchronized(lock) {
            if (revision != expected) false
            else {
                _state.value = update(_state.value)
                true
            }
        }

    fun stop() {
        synchronized(lock) {
            revision++
            recognitionJob?.cancel()
            recognitionJob = null
            _state.value = _state.value.copy(
                phase = if (_state.value.results.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results,
            )
        }
    }

    override fun onCleared() {
        stop()
        release()
    }
}
