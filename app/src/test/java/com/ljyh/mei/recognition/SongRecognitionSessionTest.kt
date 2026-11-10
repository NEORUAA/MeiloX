package com.ljyh.mei.recognition

import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.source.api.TrackRef
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongRecognitionSessionTest {
    private val song = RecognizedSong(1, "First", listOf("Artist"), "Album", null, 180_000, null)
    private val other = song.copy(ref = song.ref.copy(trackId = "2"), name = "Second")

    @Test
    fun openingPlaybackPanelWaitsForAnExplicitStartAndLocksTheSource() {
        val session = SongRecognitionSession()
        assertTrue(session.prepare(RecognitionSource.Playback))
        session.openPlaybackPanel()

        assertTrue(session.state.value.playbackPanelOpen)
        assertEquals(RecognitionPhase.Ready, session.state.value.phase)
        session.selectSource(RecognitionSource.Microphone)
        assertEquals(RecognitionSource.Playback, session.state.value.source)
        assertFalse(session.prepare(RecognitionSource.Microphone))
    }

    @Test
    fun playbackPanelRetainsItsResultAndCanStartAnotherRound() = runBlocking {
        val session = SongRecognitionSession()
        session.prepare(RecognitionSource.Playback)
        session.openPlaybackPanel()
        assertTrue(session.prepare(RecognitionSource.Playback))
        session.recognize(
            record = { floatArrayOf(0.5f) },
            fingerprint = { "fingerprint" },
            match = { _, _ -> listOf(song) },
            notify = {},
        )

        assertTrue(session.state.value.playbackPanelOpen)
        assertEquals(RecognitionOutcome.Matched(song), session.state.value.lastOutcome)
        assertTrue(session.prepare(RecognitionSource.Playback))
        assertEquals(null, session.state.value.lastOutcome)
        session.cancel()
        assertTrue(session.state.value.playbackPanelOpen)
        session.closePlaybackPanel()
        assertFalse(session.state.value.playbackPanelOpen)
        assertEquals(RecognitionPhase.Ready, session.state.value.phase)
    }

    @Test
    fun singleRunNotifiesTopResultAndKeepsAllCandidates() = runBlocking {
        val session = SongRecognitionSession()
        session.selectDuration(RecognitionDuration.Quick)
        assertTrue(session.prepare())
        assertFalse(session.prepare())
        val outcomes = mutableListOf<RecognitionOutcome>()

        session.recognize(
            record = { seconds ->
                assertEquals(3, seconds)
                floatArrayOf(0.5f)
            },
            fingerprint = { "fingerprint" },
            match = { _, _ -> listOf(song, other) },
            notify = outcomes::add,
        )

        assertEquals(listOf(RecognitionOutcome.Matched(song)), outcomes)
        assertEquals(RecognitionPhase.Results, session.state.value.phase)
        assertEquals(listOf(song, other), session.state.value.results)
    }

    @Test
    fun singleRunNotifiesWhenThereIsNoMatch() = runBlocking {
        val session = SongRecognitionSession()
        session.prepare()
        val outcomes = mutableListOf<RecognitionOutcome>()

        session.recognize(
            record = { floatArrayOf(0.5f) },
            fingerprint = { "fingerprint" },
            match = { _, _ -> emptyList() },
            notify = outcomes::add,
        )

        assertEquals(listOf(RecognitionOutcome.NoMatch(silentPlayback = false)), outcomes)
        assertEquals(RecognitionPhase.NoMatch, session.state.value.phase)
    }

    @Test
    fun silentPlaybackSkipsFingerprintingAndReportsUnavailableAudio() = runBlocking {
        val session = SongRecognitionSession()
        session.prepare(RecognitionSource.Playback)
        val outcomes = mutableListOf<RecognitionOutcome>()

        session.recognize(
            record = { FloatArray(8_000) },
            fingerprint = { error("Silent playback should skip fingerprinting") },
            match = { _, _ -> error("Silent playback should skip the network") },
            notify = outcomes::add,
        )

        assertEquals(listOf(RecognitionOutcome.NoMatch(silentPlayback = true)), outcomes)
        assertEquals(RecognitionPhase.NoAudio, session.state.value.phase)
    }

    @Test
    fun continuousRecognitionNotifiesEachTopMatchOnceAndSkipsEmptyRounds() = runBlocking {
        val session = SongRecognitionSession()
        session.selectDuration(RecognitionDuration.Continuous)
        session.prepare()
        val rounds = listOf(emptyList(), listOf(song, song), listOf(song), listOf(other)).iterator()
        val outcomes = mutableListOf<RecognitionOutcome>()

        try {
            session.recognize(
                record = { seconds ->
                    assertEquals(9, seconds)
                    if (!rounds.hasNext()) throw CancellationException()
                    floatArrayOf(0.5f)
                },
                fingerprint = { "fingerprint" },
                match = { _, _ -> rounds.next() },
                notify = outcomes::add,
            )
        } catch (_: CancellationException) {
            session.cancel()
        }

        assertEquals(listOf(RecognitionOutcome.Matched(song), RecognitionOutcome.Matched(other)), outcomes)
        assertEquals(listOf(other, song), session.state.value.results)
        assertEquals(RecognitionPhase.Results, session.state.value.phase)
    }

    @Test
    fun cancelledRecordingCannotAdvanceOrResetRestartedRecognition() = runTest {
        val session = SongRecognitionSession()
        val recordings = mutableListOf<Continuation<FloatArray>>()
        var generated = 0
        val recognize: suspend () -> Unit = {
            session.recognize(
                record = { suspendCoroutine { recordings += it } },
                fingerprint = { generated++; "fingerprint" },
                match = { _, _ -> listOf(song) },
                notify = {},
            )
        }

        assertTrue(session.prepare())
        assertFalse(session.prepare())
        val first = launch { recognize() }
        runCurrent()
        first.cancel()
        session.cancel()
        assertTrue(session.prepare())
        val second = launch { recognize() }
        runCurrent()
        assertEquals(2, recordings.size)

        recordings[0].resume(floatArrayOf(1f))
        first.join()
        assertEquals(0, generated)
        assertEquals(RecognitionPhase.Listening, session.state.value.phase)
        recordings[1].resume(floatArrayOf(1f))
        second.join()
        assertEquals(1, generated)
        assertEquals(listOf(song), session.state.value.results)
    }

    @Test
    fun cancelledMatchingCannotReplaceRestartedResults() = runTest {
        val session = SongRecognitionSession()
        val matches = mutableListOf<Continuation<List<RecognizedSong>>>()
        val outcomes = mutableListOf<RecognitionOutcome>()
        val recognize: suspend () -> Unit = {
            session.recognize(
                record = { floatArrayOf(1f) },
                fingerprint = { "fingerprint" },
                match = { _, _ -> suspendCoroutine { matches += it } },
                notify = outcomes::add,
            )
        }

        assertTrue(session.prepare())
        val first = launch { recognize() }
        runCurrent()
        first.cancel()
        session.cancel()
        assertTrue(session.prepare())
        val second = launch { recognize() }
        runCurrent()
        matches[1].resume(listOf(other))
        second.join()
        matches[0].resume(listOf(song))
        first.join()
        assertEquals(listOf(other), session.state.value.results)
        assertEquals(listOf(RecognitionOutcome.Matched(other)), outcomes)

        assertTrue(session.prepare())
        val third = launch { runCatching { recognize() } }
        runCurrent()
        third.cancel()
        session.cancel()
        matches[2].resumeWithException(IllegalStateException("Retired request"))
        third.join()
        assertEquals(RecognitionPhase.Results, session.state.value.phase)
        assertNull(session.state.value.error)
    }

    @Test
    fun cancelledFingerprintDoesNotStartMatching() = runTest {
        val session = SongRecognitionSession()
        lateinit var fingerprint: Continuation<String>
        var matched = false
        assertTrue(session.prepare())
        val recognition = launch {
            session.recognize(
                record = { floatArrayOf(1f) },
                fingerprint = { suspendCoroutine { fingerprint = it } },
                match = { _, _ -> matched = true; emptyList() },
                notify = {},
            )
        }
        runCurrent()
        recognition.cancel()
        session.cancel()
        fingerprint.resume("retired-fingerprint")
        recognition.join()
        assertFalse(matched)
        assertEquals(RecognitionPhase.Ready, session.state.value.phase)
    }

    @Test
    fun opaqueRecognitionIdsAreDeduplicatedByProviderScopedReference() = runBlocking {
        val session = SongRecognitionSession()
        val first = song.copy(ref = TrackRef("first.source", "opaque/song/42"))
        val second = song.copy(ref = TrackRef("second.source", "opaque/song/42"))
        assertTrue(session.prepare())
        session.recognize(
            record = { floatArrayOf(1f) },
            fingerprint = { "fingerprint" },
            match = { _, _ -> listOf(first, second, first) },
            notify = {},
        )
        assertEquals(listOf(first, second), session.state.value.results)
    }

    @Test
    fun failureKeepsItsMessageWhenTheServiceCleansUp() {
        val session = SongRecognitionSession()
        session.prepare()

        assertEquals(RecognitionOutcome.Failed("Network unavailable"), session.fail("Network unavailable"))
        session.cancel()

        assertEquals(RecognitionPhase.Failed, session.state.value.phase)
        assertEquals("Network unavailable", session.state.value.error)
    }
}
