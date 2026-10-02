package com.ljyh.mei.ui.screen.recognition

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.RecognitionCapture
import com.ljyh.mei.recognition.RecognitionRecording
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongRecognitionSessionTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val records = mutableListOf<Int>()
    private val matches = mutableListOf<Triple<String, Int, SessionStamp>>()
    private var record: suspend (Int) -> FloatArray = { floatArrayOf(0.1f) }
    private var generate: suspend (FloatArray) -> String = { "fixture" }
    private var match: suspend (String, Int, SessionStamp) -> List<RecognizedSong> = { _, _, _ -> listOf(song(1)) }
    private var released = 0
    private var capture: RecognitionCapture? = null

    private fun checkModel(ownerStore: SessionStore = sessions,
        block: suspend TestScope.(SongRecognitionViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = SongRecognitionViewModel(ownerStore,
                { seconds -> records += seconds; record(seconds) }, { samples -> generate(samples) },
                { fingerprint, seconds, owner -> matches += Triple(fingerprint, seconds, owner); match(fingerprint, seconds, owner) },
                { released++ }, capture)
            store.put("recognition", model)
            runCurrent()
            block(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun permissionResultCannotStartRecordingForAReplacementAccount() = checkModel { model, _ ->
        model.preparePermission()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        model.permissionResult(true)
        runCurrent()
        assertTrue(records.isEmpty())
        assertEquals(RecognitionPhase.Ready, model.state.value.phase)
    }

    @Test fun deniedPermissionNeverStartsRecording() = checkModel { model, _ ->
        model.preparePermission()
        model.permissionResult(false)
        runCurrent()
        assertTrue(records.isEmpty())
    }

    @Test fun grantedPermissionPinsTheOriginalAccount() = checkModel { model, _ ->
        model.preparePermission()
        model.permissionResult(true)
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        assertEquals(17L, matches.single().third.identity.userId)
    }

    @Test fun recordingLeaseClosesOnStopWithoutClosingAReplacementLease() {
        val oldSamples = CompletableDeferred<FloatArray>()
        val newSamples = CompletableDeferred<FloatArray>()
        var opened = 0
        val closed = mutableListOf<Int>()
        capture = object : RecognitionCapture {
            override fun open(): RecognitionRecording {
                val id = ++opened
                return object : RecognitionRecording {
                    override suspend fun record(seconds: Int): FloatArray =
                        if (id == 1) withContext(NonCancellable) { oldSamples.await() } else newSamples.await()
                    override fun close() { closed += id }
                }
            }
        }
        checkModel { model, _ ->
            model.start()
            runCurrent()
            model.stop()
            model.start()
            runCurrent()
            assertEquals(2, opened)
            oldSamples.complete(floatArrayOf(1f))
            runCurrent()
            assertEquals(listOf(1), closed)
            assertEquals(RecognitionPhase.Listening, model.state.value.phase)
            newSamples.complete(floatArrayOf(1f))
            runCurrent()
            assertEquals(listOf(1, 2), closed)
            assertEquals(RecognitionPhase.Results, model.state.value.phase)
        }
    }

    @Test fun balancedRecognitionPreservesPhasesAndPinsTheWholePipeline() = checkModel { model, _ ->
        val samples = CompletableDeferred<FloatArray>()
        val fingerprint = CompletableDeferred<String>()
        val result = CompletableDeferred<List<RecognizedSong>>()
        record = { samples.await() }
        generate = { fingerprint.await() }
        model.selectDuration(RecognitionDuration.Balanced)
        match = { _, _, _ -> result.await() }
        model.start()
        runCurrent()
        assertEquals(RecognitionPhase.Listening, model.state.value.phase)
        samples.complete(floatArrayOf(1f))
        runCurrent()
        assertEquals(RecognitionPhase.Fingerprinting, model.state.value.phase)
        fingerprint.complete("fixture")
        runCurrent()
        assertEquals(RecognitionPhase.Matching, model.state.value.phase)
        assertEquals(listOf(6), records)
        assertEquals(Triple("fixture", 6, sessions.snapshot()), matches.single())
        result.complete(listOf(song(8)))
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        assertEquals(listOf(8L), model.state.value.results.map { it.id })
        assertEquals(sessions.snapshot(), model.state.value.session)
    }

    @Test fun originalDurationOptionsAndNoMatchStateArePreserved() = checkModel { model, _ ->
        match = { _, _, _ -> emptyList() }
        for (duration in listOf(RecognitionDuration.Quick, RecognitionDuration.Balanced, RecognitionDuration.Extended)) {
            model.selectDuration(duration)
            model.start()
            runCurrent()
            assertEquals(RecognitionPhase.NoMatch, model.state.value.phase)
            assertEquals(duration, model.state.value.duration)
        }
        assertEquals(listOf(3, 6, 9), records)
        assertEquals(listOf(3, 6, 9), matches.map { it.second })
    }

    @Test fun stoppingAndRestartingCannotBeOverwrittenByOldRecorderCleanup() = checkModel { model, _ ->
        val late = CompletableDeferred<FloatArray>()
        record = { if (records.size == 1) withContext(NonCancellable) { late.await() } else floatArrayOf(1f) }
        model.start()
        model.start()
        runCurrent()
        assertEquals(1, records.size)
        model.stop()
        assertEquals(RecognitionPhase.Ready, model.state.value.phase)
        model.start()
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        late.complete(floatArrayOf(1f))
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        assertEquals(1, matches.size)
    }

    @Test fun accountChangeDuringRecordingClearsImmediatelyAndNeverMatchesOldSamples() = checkModel { model, _ ->
        val late = CompletableDeferred<FloatArray>()
        record = { withContext(NonCancellable) { late.await() } }
        model.start()
        runCurrent()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        assertEquals(RecognitionPhase.Ready, model.state.value.phase)
        assertNull(model.state.value.session)
        runCurrent()
        late.complete(floatArrayOf(1f))
        runCurrent()
        assertTrue(matches.isEmpty())
        assertEquals(1, records.size)
    }

    @Test fun accountChangeDuringFingerprintingCannotDispatchWithTheNewAccount() = checkModel { model, _ ->
        val late = CompletableDeferred<String>()
        generate = { withContext(NonCancellable) { late.await() } }
        model.start()
        runCurrent()
        assertEquals(RecognitionPhase.Fingerprinting, model.state.value.phase)
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        runCurrent()
        late.complete("old-fingerprint")
        runCurrent()
        assertEquals(RecognitionPhase.Ready, model.state.value.phase)
        assertTrue(model.state.value.results.isEmpty())
        assertTrue(matches.isEmpty())
    }

    @Test fun lateMatchingResponseCannotPolluteReplacementAccountResults() = checkModel { model, _ ->
        val late = CompletableDeferred<List<RecognizedSong>>()
        match = { _, _, owner -> if (owner.identity.userId == 17L) withContext(NonCancellable) { late.await() } else listOf(song(2)) }
        model.start()
        runCurrent()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        assertTrue(model.state.value.results.isEmpty())
        runCurrent()
        model.start()
        runCurrent()
        late.complete(listOf(song(1)))
        runCurrent()
        assertEquals(listOf(2L), model.state.value.results.map { it.id })
        assertEquals(listOf(17L, 18L), matches.map { it.third.identity.userId })
    }

    @Test fun reauthorizationClearsResultsWithoutAutomaticallyRecordingAgain() = checkModel { model, _ ->
        model.start()
        runCurrent()
        sessions.invalidate()
        assertTrue(model.state.value.results.isEmpty())
        runCurrent()
        assertEquals(1, records.size)
        model.start()
        runCurrent()
        assertEquals(2, records.size)
        assertEquals(sessions.snapshot(), model.state.value.session)
    }

    @Test fun recoveryCancelsWorkAndResumeNeverStartsTheMicrophone() = checkModel { model, _ ->
        val late = CompletableDeferred<List<RecognizedSong>>()
        match = { _, _, _ -> withContext(NonCancellable) { late.await() } }
        model.start()
        runCurrent()
        sessions.setRecoveryRequired(true)
        runCurrent()
        model.start()
        assertEquals(1, records.size)
        assertEquals(RecognitionPhase.Ready, model.state.value.phase)
        late.complete(listOf(song(1)))
        runCurrent()
        assertTrue(model.state.value.results.isEmpty())
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(1, records.size)
        assertNull(model.state.value.session)
    }

    @Test fun anonymousSessionsRemainEligibleForExplicitRecognition() = checkModel { model, _ ->
        identity = SessionIdentity(0, false, true)
        sessions.invalidate()
        runCurrent()
        model.start()
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        assertEquals(sessions.snapshot(), matches.single().third)
    }

    @Test fun unreadySessionsNeverRecordAndBindingDoesNotAutoStart() {
        val unready = SessionStore()
        checkModel(unready) { model, _ ->
            model.start()
            runCurrent()
            assertTrue(records.isEmpty())
            unready.bind { identity }
            runCurrent()
            assertTrue(records.isEmpty())
            assertEquals(RecognitionPhase.Ready, model.state.value.phase)
        }
    }

    @Test fun continuousModeKeepsNineSecondWindowsDeduplicatesAndCapsResults() = checkModel { model, _ ->
        val nextWindow = CompletableDeferred<FloatArray>()
        record = { if (records.size <= 2) floatArrayOf(1f) else nextWindow.await() }
        match = { _, _, _ -> if (matches.size == 1) (1L..51L).map { song(it) } else listOf(song(1, "Updated"), song(52)) }
        model.selectDuration(RecognitionDuration.Continuous)
        model.start()
        runCurrent()
        assertEquals(listOf(9, 9, 9), records)
        assertEquals(50, model.state.value.results.size)
        assertEquals(listOf(1L, 52L), model.state.value.results.take(2).map { it.id })
        assertEquals("Updated", model.state.value.results.first().name)
        model.stop()
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        assertEquals(50, model.state.value.results.size)
    }

    @Test fun oldFailuresAndCancellationCannotReplaceNewRecognitionState() = checkModel { model, _ ->
        val late = CompletableDeferred<Unit>()
        match = { _, _, _ -> if (matches.size == 1) { withContext(NonCancellable) { late.await() }; throw IOException("Old failure") } else listOf(song(2)) }
        model.start()
        runCurrent()
        model.stop()
        model.start()
        runCurrent()
        late.complete(Unit)
        runCurrent()
        assertEquals(RecognitionPhase.Results, model.state.value.phase)
        assertNull(model.state.value.error)
        assertEquals(listOf(2L), model.state.value.results.map { it.id })
    }

    @Test fun currentFailuresRemainVisibleAndCancellationIsNotAFailure() = checkModel { model, _ ->
        record = { throw IOException("Fixture microphone error") }
        model.start()
        runCurrent()
        assertEquals(RecognitionPhase.Failed, model.state.value.phase)
        assertEquals("Fixture microphone error", model.state.value.error)
        record = { throw CancellationException("Canceled fixture") }
        model.start()
        runCurrent()
        assertEquals(RecognitionPhase.Ready, model.state.value.phase)
        assertNull(model.state.value.error)
    }

    @Test fun clearingTheViewModelReleasesInputAndRejectsLateWork() = checkModel { model, store ->
        val late = CompletableDeferred<FloatArray>()
        record = { withContext(NonCancellable) { late.await() } }
        model.start()
        runCurrent()
        model.selectDuration(RecognitionDuration.Extended)
        assertEquals(RecognitionDuration.Balanced, model.state.value.duration)
        store.clear()
        assertEquals(1, released)
        late.complete(floatArrayOf(1f))
        runCurrent()
        assertTrue(matches.isEmpty())
        assertTrue(model.state.value.results.isEmpty())
    }

    @Test fun unavailableSessionReadersClearWorkWithoutCrashingAndAllowExplicitRetry() {
        var unavailable = false
        val ownerStore = SessionStore().apply { bind {
            if (unavailable) throw IOException("Unavailable session fixture")
            identity
        } }
        checkModel(ownerStore) { model, _ ->
            match = { _, _, _ -> unavailable = true; listOf(song(1)) }
            model.start()
            runCurrent()
            assertEquals(RecognitionPhase.Ready, model.state.value.phase)
            assertNull(model.state.value.session)
            assertTrue(model.state.value.results.isEmpty())
            unavailable = false
            match = { _, _, _ -> listOf(song(2)) }
            model.start()
            runCurrent()
            assertEquals(listOf(2L), model.state.value.results.map { it.id })
        }
    }

    private fun song(id: Long, name: String = "Track $id") = RecognizedSong(id, name, listOf("Artist"), "Album", null, 1000, null)
}
