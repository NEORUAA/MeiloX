package com.ljyh.mei.ui.screen.recognition

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.data.repository.SongRecognitionRepository
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.recognition.NeteaseFingerprintGenerator
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.SongRecognitionRecorder
import com.ljyh.mei.di.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RecognitionPhase { Ready, Listening, Fingerprinting, Matching, Results, NoMatch, Failed }

data class SongRecognitionUiState(
    val duration: RecognitionDuration = RecognitionDuration.Balanced,
    val phase: RecognitionPhase = RecognitionPhase.Ready,
    val results: List<RecognizedSong> = emptyList(),
    val error: String? = null,
    val session: SessionStamp? = null,
) {
    val isWorking: Boolean get() = phase in setOf(
        RecognitionPhase.Listening,
        RecognitionPhase.Fingerprinting,
        RecognitionPhase.Matching,
    )
}

class SongRecognitionViewModel internal constructor(
    private val sessions: SessionStore,
    private val record: suspend (Int) -> FloatArray,
    private val generate: suspend (FloatArray) -> String,
    private val match: suspend (String, Int, SessionStamp) -> List<RecognizedSong>,
    private val release: () -> Unit,
) : ViewModel() {
    private constructor(context: Context, repository: SongRecognitionRepository, sessions: SessionStore,
        generator: NeteaseFingerprintGenerator) : this(
        sessions, SongRecognitionRecorder(context)::record, generator::generate, repository::match, generator::release,
    )

    @Inject constructor(@ApplicationContext context: Context, repository: SongRecognitionRepository,
        sessions: SessionStore) : this(context, repository, sessions, NeteaseFingerprintGenerator(context))

    private val _state = MutableStateFlow(SongRecognitionUiState())
    val state = _state.asStateFlow()
    private val lock = Any()
    private var revision = 0L
    private var recognitionJob: Job? = null
    private val invalidation = sessions.onInvalidated { generation ->
        synchronized(lock) {
            if ((_state.value.session?.generation ?: -1) < generation) clear()
        }
    }

    init {
        viewModelScope.launch {
            combine(sessions.changes, sessions.recoveryRequired) { _, recovery -> recovery }.collect { recovery ->
                val owner = if (recovery) null else runCatching { sessions.snapshot() }.getOrNull()
                synchronized(lock) {
                    if (_state.value.session != null && _state.value.session != owner) clear()
                }
            }
        }
    }

    private fun clear() {
        revision++
        recognitionJob?.cancel()
        recognitionJob = null
        _state.value = SongRecognitionUiState(duration = _state.value.duration)
    }

    fun selectDuration(duration: RecognitionDuration) {
        synchronized(lock) {
            if (!_state.value.isWorking) _state.value = _state.value.copy(duration = duration)
        }
    }

    fun start() {
        if (sessions.recoveryRequired.value) return
        val owner = runCatching { sessions.snapshot() }.getOrNull() ?: return
        val job = runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (_state.value.isWorking || sessions.recoveryRequired.value) return@synchronized null
                    if (_state.value.session != owner) clear()
                    val expected = ++revision
                    val duration = _state.value.duration
                    _state.value = _state.value.copy(phase = RecognitionPhase.Listening, error = null, session = owner)
                    viewModelScope.launch(start = CoroutineStart.LAZY) { recognize(owner, expected, duration) }
                        .also { recognitionJob = it }
                }
            }
        }.getOrNull()
        job?.start()
    }

    private suspend fun recognize(owner: SessionStamp, expected: Long, duration: RecognitionDuration) {
        try {
            do {
                currentCoroutineContext().ensureActive()
                val seconds = duration.seconds ?: 9
                if (!publish(owner, expected) { it.copy(phase = RecognitionPhase.Listening, error = null) }) return
                val samples = record(seconds)
                currentCoroutineContext().ensureActive()
                if (!publish(owner, expected) { it.copy(phase = RecognitionPhase.Fingerprinting) }) return
                val fingerprint = generate(samples)
                currentCoroutineContext().ensureActive()
                if (!publish(owner, expected) { it.copy(phase = RecognitionPhase.Matching) }) return
                val matches = match(fingerprint, seconds, owner)
                currentCoroutineContext().ensureActive()
                if (!publish(owner, expected) { current ->
                    if (matches.isNotEmpty()) current.copy(
                        phase = if (duration == RecognitionDuration.Continuous) RecognitionPhase.Listening else RecognitionPhase.Results,
                        results = (matches + current.results).distinctBy(RecognizedSong::id).take(50),
                    ) else if (duration != RecognitionDuration.Continuous) current.copy(phase = RecognitionPhase.NoMatch)
                    else current
                }) return
            } while (duration == RecognitionDuration.Continuous && currentCoroutineContext().isActive)
        } catch (_: CancellationException) {
            publish(owner, expected) { it.copy(phase = if (it.results.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results) }
        } catch (error: Exception) {
            publish(owner, expected) { it.copy(phase = RecognitionPhase.Failed, error = error.message) }
        } finally {
            synchronized(lock) { if (revision == expected) recognitionJob = null }
        }
    }

    private fun publish(owner: SessionStamp, expected: Long, update: (SongRecognitionUiState) -> SongRecognitionUiState): Boolean =
        try {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (revision != expected) false
                    else if (sessions.recoveryRequired.value) { clear(); false }
                    else { _state.value = update(_state.value); true }
                }
            }
        } catch (_: Exception) {
            synchronized(lock) { if (revision == expected) clear() }
            false
        }

    fun stop() {
        val owner = _state.value.session ?: return
        runCatching {
            sessions.withCurrent(owner) {
                synchronized(lock) {
                    if (sessions.recoveryRequired.value) { clear(); return@synchronized }
                    revision++
                    recognitionJob?.cancel()
                    recognitionJob = null
                    _state.value = _state.value.copy(
                        phase = if (_state.value.results.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results,
                    )
                }
            }
        }.onFailure { synchronized(lock) { if (_state.value.session == owner) clear() } }
    }

    override fun onCleared() {
        invalidation.close()
        synchronized(lock) { clear() }
        release()
    }
}
