package com.ljyh.mei.ui.screen.recognition

import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.recognition.RecognitionDuration
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongRecognitionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun stoppedRecordingCannotAdvanceOrResetTheRestartedTask() = runTest(dispatcher) {
        val recordings = mutableListOf<Continuation<FloatArray>>()
        var generated = 0
        val viewModel = SongRecognitionViewModel(
            record = { suspendCoroutine { recordings += it } },
            generate = { generated++; "fingerprint" },
            match = { _, _ -> listOf(song(2)) },
            release = {},
        )

        viewModel.start()
        viewModel.start()
        runCurrent()
        assertEquals(1, recordings.size)
        viewModel.stop()
        viewModel.start()
        runCurrent()
        assertEquals(2, recordings.size)

        recordings[0].resume(floatArrayOf(1f))
        runCurrent()
        assertEquals(0, generated)
        assertEquals(RecognitionPhase.Listening, viewModel.state.value.phase)

        recordings[1].resume(floatArrayOf(1f))
        runCurrent()
        assertEquals(1, generated)
        assertEquals(listOf("2"), viewModel.state.value.results.map { it.id })
        assertEquals(RecognitionPhase.Results, viewModel.state.value.phase)
    }

    @Test
    fun lateMatchingResultAndFailureCannotReplaceTheRestartedResults() = runTest(dispatcher) {
        val matches = mutableListOf<Continuation<List<RecognizedSong>>>()
        val viewModel = SongRecognitionViewModel(
            record = { floatArrayOf(1f) },
            generate = { "fingerprint" },
            match = { _, _ -> suspendCoroutine { matches += it } },
            release = {},
        )

        viewModel.start()
        runCurrent()
        viewModel.stop()
        viewModel.start()
        runCurrent()
        matches[1].resume(listOf(song(2)))
        runCurrent()
        matches[0].resume(listOf(song(1)))
        runCurrent()
        assertEquals(listOf("2"), viewModel.state.value.results.map { it.id })

        viewModel.start()
        runCurrent()
        viewModel.stop()
        matches[2].resumeWithException(IllegalStateException("Retired request"))
        runCurrent()
        assertEquals(RecognitionPhase.Results, viewModel.state.value.phase)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun stopKeepsContinuousMatchesAfterTheCancellationHandlerRuns() = runTest(dispatcher) {
        var recordingCount = 0
        val viewModel = SongRecognitionViewModel(
            record = {
                if (++recordingCount > 1) awaitCancellation()
                floatArrayOf(1f)
            },
            generate = { "fingerprint" },
            match = { _, _ -> listOf(song(1), song(1)) },
            release = {},
        )
        viewModel.selectDuration(RecognitionDuration.Continuous)
        viewModel.start()
        runCurrent()
        assertEquals(2, recordingCount)

        viewModel.stop()
        runCurrent()

        assertEquals(RecognitionPhase.Results, viewModel.state.value.phase)
        assertEquals(listOf("1"), viewModel.state.value.results.map { it.id })
        assertFalse(viewModel.state.value.isWorking)
    }

    @Test
    fun canceledFingerprintDoesNotStartMatching() = runTest(dispatcher) {
        lateinit var fingerprint: Continuation<String>
        var matched = false
        val viewModel = SongRecognitionViewModel(
            record = { floatArrayOf(1f) },
            generate = { suspendCoroutine { fingerprint = it } },
            match = { _, _ -> matched = true; emptyList() },
            release = {},
        )
        viewModel.start()
        runCurrent()
        viewModel.stop()
        fingerprint.resume("retired-fingerprint")
        runCurrent()

        assertFalse(matched)
        assertEquals(RecognitionPhase.Ready, viewModel.state.value.phase)
    }

    @Test
    fun opaqueRecognitionIdsAreDeduplicatedByProviderScopedReference() = runTest(dispatcher) {
        fun candidate(provider: String) = RecognizedSong(com.ljyh.mei.source.api.TrackRef(provider, "opaque/song/42"), "Song", emptyList(), "Album", null, 1000, null)
        val viewModel = SongRecognitionViewModel(record = { floatArrayOf(1f) }, generate = { "fingerprint" },
            match = { _, _ -> listOf(candidate("first.source"), candidate("second.source"), candidate("first.source")) }, release = {})
        viewModel.start(); runCurrent()
        assertEquals(listOf("first.source", "second.source"), viewModel.state.value.results.map { it.ref.providerId })
        assertEquals(listOf("opaque/song/42", "opaque/song/42"), viewModel.state.value.results.map { it.id })
    }

    private fun song(id: Long) = RecognizedSong(id, "Song $id", emptyList(), "Album", null, 1_000, null)
}
