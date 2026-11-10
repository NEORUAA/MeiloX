package com.ljyh.mei.recognition

import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.shared.SourceRecognitionSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecognitionSource { Microphone, Playback }

enum class RecognitionDuration(val seconds: Int?) {
    Quick(3), Balanced(6), Extended(9), Continuous(null)
}

enum class RecognitionPhase {
    Ready, Starting, Listening, Fingerprinting, Matching, Results, NoMatch, NoAudio, Failed
}

data class SongRecognitionUiState(
    val source: RecognitionSource = RecognitionSource.Microphone,
    val duration: RecognitionDuration = RecognitionDuration.Balanced,
    val phase: RecognitionPhase = RecognitionPhase.Ready,
    val results: List<RecognizedSong> = emptyList(),
    val error: String? = null,
    val playbackPanelOpen: Boolean = false,
    val lastOutcome: RecognitionOutcome? = null,
    val isAvailable: Boolean = true,
) {
    val isWorking: Boolean get() = when (phase) {
        RecognitionPhase.Starting, RecognitionPhase.Listening,
        RecognitionPhase.Fingerprinting, RecognitionPhase.Matching -> true
        else -> false
    }
}

sealed interface RecognitionOutcome {
    data class Matched(val song: RecognizedSong) : RecognitionOutcome
    data class NoMatch(val silentPlayback: Boolean) : RecognitionOutcome
    data class Failed(val message: String) : RecognitionOutcome
}

@Singleton
class SongRecognitionSession @Inject constructor() {
    private val _state = MutableStateFlow(SongRecognitionUiState())
    val state = _state.asStateFlow()
    internal var binding: SourceRecognitionSession? = null
        private set

    internal fun matchesBinding(other: SourceRecognitionSession): Boolean =
        binding?.owner?.sameSession(other.owner) == true

    internal fun invalidateBinding(owner: SourceRecognitionSession) {
        if (binding !== owner) return
        binding = null
        _state.value = _state.value.copy(results = emptyList(), lastOutcome = null)
    }

    fun selectSource(source: RecognitionSource) {
        if (!_state.value.isWorking && !_state.value.playbackPanelOpen) {
            _state.value = _state.value.copy(source = source)
        }
    }

    fun selectDuration(duration: RecognitionDuration) {
        if (!_state.value.isWorking) _state.value = _state.value.copy(duration = duration)
    }

    internal fun prepare(
        source: RecognitionSource = _state.value.source,
        owner: SourceRecognitionSession? = binding,
    ): Boolean {
        val current = _state.value
        val sameOwner = if (owner == null) binding == null else matchesBinding(owner)
        if (current.isWorking || (current.playbackPanelOpen && (source != RecognitionSource.Playback || !sameOwner))) {
            return false
        }
        binding = owner
        _state.value = current.copy(
            source = source, phase = RecognitionPhase.Starting, error = null, lastOutcome = null,
            results = if (sameOwner) current.results else emptyList(),
        )
        return true
    }

    fun openPlaybackPanel() {
        _state.value = _state.value.copy(
            source = RecognitionSource.Playback, playbackPanelOpen = true, phase = RecognitionPhase.Ready,
        )
    }

    fun closePlaybackPanel() {
        _state.value = _state.value.copy(
            playbackPanelOpen = false, phase = RecognitionPhase.Ready, error = null, lastOutcome = null,
        )
    }

    suspend fun recognize(
        record: suspend (Int) -> FloatArray,
        fingerprint: suspend (FloatArray) -> String,
        match: suspend (String, Int) -> List<RecognizedSong>,
        notify: (RecognitionOutcome) -> Unit,
    ) {
        val (source, duration) = _state.value
        val continuous = duration == RecognitionDuration.Continuous
        val seconds = duration.seconds ?: 9
        val announcedSongs = mutableSetOf<TrackRef>()
        do {
            currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(phase = RecognitionPhase.Listening)
            val samples = record(seconds)
            currentCoroutineContext().ensureActive()
            val silentPlayback = source == RecognitionSource.Playback && samples.all { it == 0f }
            val matches = if (silentPlayback) {
                emptyList()
            } else {
                _state.value = _state.value.copy(phase = RecognitionPhase.Fingerprinting)
                val audioFingerprint = fingerprint(samples)
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(phase = RecognitionPhase.Matching)
                match(audioFingerprint, seconds)
            }
            currentCoroutineContext().ensureActive()
            val bestMatch = matches.firstOrNull()
            if (bestMatch != null) {
                val merged = (matches + _state.value.results).distinctBy(RecognizedSong::ref).take(50)
                val outcome = if (announcedSongs.add(bestMatch.ref)) RecognitionOutcome.Matched(bestMatch) else null
                _state.value = _state.value.copy(
                    phase = if (continuous) RecognitionPhase.Listening else RecognitionPhase.Results,
                    results = merged,
                    lastOutcome = outcome ?: _state.value.lastOutcome,
                )
                if (outcome != null) notify(outcome)
            } else if (!continuous) {
                val phase = if (silentPlayback) RecognitionPhase.NoAudio else RecognitionPhase.NoMatch
                val outcome = RecognitionOutcome.NoMatch(silentPlayback)
                _state.value = _state.value.copy(phase = phase, lastOutcome = outcome)
                notify(outcome)
            }
        } while (continuous)
    }

    fun fail(message: String): RecognitionOutcome.Failed {
        val outcome = RecognitionOutcome.Failed(message)
        _state.value = _state.value.copy(phase = RecognitionPhase.Failed, error = message, lastOutcome = outcome)
        return outcome
    }

    fun cancel() {
        val current = _state.value
        if (!current.isWorking) return
        val hasResult = if (current.playbackPanelOpen) current.lastOutcome is RecognitionOutcome.Matched
        else current.results.isNotEmpty()
        _state.value = current.copy(phase = if (hasResult) RecognitionPhase.Results else RecognitionPhase.Ready)
    }
}
