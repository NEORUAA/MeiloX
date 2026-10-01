package com.ljyh.mei.ui.component.player.state

import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerIntelligenceSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntelligencePlaybackStateTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val seed = Resource.Success(Tracks(200, emptyList(), emptyList()))
    private val list = Resource.Success(Intelligence(200, emptyList(), ""))
    private val reads = mutableListOf<Pair<String, SessionStamp>>()
    private var readSeed: suspend () -> Resource<Tracks> = { seed }
    private var readList: suspend () -> Resource<Intelligence> = { list }
    private val source = object : PlayerIntelligenceSource {
        override suspend fun getSongDetail(id: String, owner: SessionStamp): Resource<Tracks> {
            reads += "seed:$id" to owner
            return readSeed()
        }
        override suspend fun getIntelligenceList(id: String, playlistId: String, startSongId: String, owner: SessionStamp): Resource<Intelligence> {
            reads += "list:$id:$playlistId:$startSongId" to owner
            return readList()
        }
    }

    @Test fun seedAndListShareTheClickTimeOwnerAndPlaybackIsOneShot() = runTest {
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        val owner = sessions.snapshot()
        state.start("11", "22", "33")
        runCurrent()
        assertEquals(listOf("seed:33" to owner, "list:11:22:33" to owner), reads)
        val expected = state.state.value
        assertEquals(seed, expected.seed)
        assertEquals(list, expected.recommendations)
        val plays = mutableListOf<SessionStamp>()
        assertTrue(state.consume(expected, plays::add))
        assertFalse(state.consume(expected, plays::add))
        assertEquals(listOf(owner), plays)
        state.close()
    }

    @Test fun loadingCannotBeConsumed() = runTest {
        val deferred = CompletableDeferred<Resource<Tracks>>()
        readSeed = { deferred.await() }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        assertFalse(state.consume(state.state.value) { fail("Premature playback") })
        state.close()
    }

    @Test fun guestsAndRecoveryNeverDispatch() = runTest {
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        identity = SessionIdentity(0, false, true)
        state.start("11", "22", "33")
        identity = SessionIdentity(17, true, false)
        sessions.setRecoveryRequired(true)
        state.start("11", "22", "33")
        runCurrent()
        assertTrue(reads.isEmpty())
        state.close()
    }

    @Test fun accountChangeClearsReadyResultsSynchronously() = runTest {
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        val old = state.state.value
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        assertNull(state.state.value.owner)
        assertFalse(state.consume(old) { fail("Old account playback") })
        state.close()
    }

    @Test fun sameAccountReauthorizationAlsoRetiresAReadyResult() = runTest {
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        val old = state.state.value
        sessions.invalidate()
        assertFalse(state.consume(old) { fail("Old authorization playback") })
        state.close()
    }

    @Test fun lateSeedCannotDispatchAListUnderTheNewAccount() = runTest {
        val deferred = CompletableDeferred<Resource<Tracks>>()
        readSeed = { withContext(NonCancellable) { deferred.await() } }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        deferred.complete(seed)
        runCurrent()
        assertEquals(1, reads.size)
        assertNull(state.state.value.owner)
        state.close()
    }

    @Test fun lateRecommendationsCannotPublishAfterInvalidation() = runTest {
        val deferred = CompletableDeferred<Resource<Intelligence>>()
        readList = { withContext(NonCancellable) { deferred.await() } }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        sessions.invalidate()
        deferred.complete(list)
        runCurrent()
        assertEquals(Resource.Loading, state.state.value.recommendations)
        state.close()
    }

    @Test fun replacementClickWinsEvenWhenTheOldSourceIgnoresCancellation() = runTest {
        val deferred = CompletableDeferred<Resource<Tracks>>()
        readSeed = { withContext(NonCancellable) { deferred.await() } }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        readSeed = { seed }
        state.start("44", "55", "66")
        runCurrent()
        val current = state.state.value
        deferred.complete(seed)
        runCurrent()
        assertEquals(current, state.state.value)
        assertEquals(listOf("seed:33", "seed:66", "list:44:55:66"), reads.map { it.first })
        state.close()
    }

    @Test fun recoveryRejectsAVisibleSnapshotBeforeTheObserverRuns() = runTest {
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        val old = state.state.value
        sessions.setRecoveryRequired(true)
        assertFalse(state.consume(old) { fail("Recovery playback") })
        runCurrent()
        assertNull(state.state.value.owner)
        state.close()
    }

    @Test fun seedFailurePreservesTheOriginalListOnlyFallback() = runTest {
        readSeed = { Resource.Error("Unavailable seed") }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        assertTrue(state.consume(state.state.value) { })
        state.close()
    }

    @Test fun recommendationFailureNeverStartsPlayback() = runTest {
        readList = { throw IllegalStateException("Offline") }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        assertTrue(state.state.value.recommendations is Resource.Error)
        assertFalse(state.consume(state.state.value) { fail("Failed list playback") })
        state.close()
    }

    @Test fun closedStateIgnoresLateResultsAndNewClicks() = runTest {
        val deferred = CompletableDeferred<Resource<Tracks>>()
        readSeed = { withContext(NonCancellable) { deferred.await() } }
        val state = IntelligencePlaybackState(backgroundScope, sessions, source)
        state.start("11", "22", "33")
        runCurrent()
        state.close()
        state.start("44", "55", "66")
        deferred.complete(seed)
        runCurrent()
        assertEquals(1, reads.size)
        assertNull(state.state.value.owner)
    }
}
