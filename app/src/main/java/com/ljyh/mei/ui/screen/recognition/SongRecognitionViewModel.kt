package com.ljyh.mei.ui.screen.recognition

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.source.shared.SourceRecognitionRepository
import com.ljyh.mei.source.shared.SourceRecognitionSession
import com.ljyh.mei.source.shared.guard
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
    val isAvailable: Boolean = true,
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
    private val repository: SourceRecognitionRepository? = null,
) : ViewModel() {
    private constructor(
        context: Context,
        repository: SourceRecognitionRepository,
        generator: NeteaseFingerprintGenerator,
    ) : this(SongRecognitionRecorder(context)::record, generator::generate, { _, _ -> error("Recognition source is unavailable") }, generator::release, repository)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: SourceRecognitionRepository,
    ) : this(context, repository, NeteaseFingerprintGenerator(context))

    private val _state = MutableStateFlow(SongRecognitionUiState(isAvailable = repository == null))
    val state = _state.asStateFlow()
    private val lock = Any()
    private var revision = 0L
    private var recognitionJob: Job? = null
    private var bindingJob: Job? = null
    private var bound: SourceRecognitionSession? = null

    fun open(providerId: String? = null) {
        if (repository == null || bindingJob != null) return
        bindingJob = viewModelScope.launch {
            try {
                bound = repository.open(providerId).also { session ->
                    session.owner.guard(viewModelScope) {
                        stop(); bound = null
                        _state.value = _state.value.copy(phase = RecognitionPhase.Failed, results = emptyList(), isAvailable = false, error = "音源或账号已变更")
                    }
                }
                _state.value = _state.value.copy(isAvailable = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(phase = RecognitionPhase.Failed, isAvailable = false, error = error.message) }
        }
    }

    fun media(song: RecognizedSong, onReady: (androidx.media3.common.MediaItem) -> Unit) {
        val session = bound ?: return
        viewModelScope.launch {
            try { onReady(session.media(song))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { _state.value = _state.value.copy(error = error.message) }
        }
    }

    fun selectDuration(duration: RecognitionDuration) {
        synchronized(lock) {
            if (!_state.value.isWorking) _state.value = _state.value.copy(duration = duration)
        }
    }

    fun start() {
        val job = synchronized(lock) {
            if (_state.value.isWorking || !_state.value.isAvailable) return
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
                bound?.owner?.ensureCurrent()
                val samples = record(seconds)
                currentCoroutineContext().ensureActive()
                if (!publish(expected) { it.copy(phase = RecognitionPhase.Fingerprinting) }) return
                val fingerprint = generate(samples)
                currentCoroutineContext().ensureActive()
                if (!publish(expected) { it.copy(phase = RecognitionPhase.Matching) }) return
                val matches = bound?.match(fingerprint, seconds) ?: match(fingerprint, seconds)
                currentCoroutineContext().ensureActive()
                if (!publish(expected) { current ->
                    if (matches.isNotEmpty()) current.copy(
                        phase = if (duration == RecognitionDuration.Continuous) RecognitionPhase.Listening else RecognitionPhase.Results,
                        results = (matches + current.results).distinctBy(RecognizedSong::ref).take(50),
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
