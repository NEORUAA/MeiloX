package com.ljyh.mei.ui.component.player.state

import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.PlayerLikeSource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerLikeStateTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val reads = mutableListOf<Pair<Long, SessionStamp>>()
    private val writes = mutableListOf<Triple<Long, Boolean, SessionStamp>>()
    private val changes = mutableListOf<SessionStamp>()
    private var read: suspend (Long) -> Resource<Boolean> = { Resource.Success(it == 10L) }
    private var write: suspend (Long, Boolean) -> Resource<Boolean> = { _, liked -> Resource.Success(liked) }
    private val source = object : PlayerLikeSource {
        override suspend fun checkSongLike(id: Long, owner: SessionStamp): Resource<Boolean> {
            reads += id to owner
            return read(id)
        }
        override suspend fun like(id: Long, liked: Boolean, owner: SessionStamp): Resource<Boolean> {
            writes += Triple(id, liked, owner)
            return write(id, liked)
        }
    }

    @Test fun selectedTrackReadsTheCapturedOfficialAccount() = runTest {
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        assertEquals(listOf(10L to sessions.snapshot()), reads)
        assertEquals(true, state.state.value.liked)
        state.select(20)
        assertNull(state.state.value.liked)
        runCurrent()
        assertEquals(false, state.state.value.liked)
        state.close()
    }

    @Test fun absentTracksAndGuestsNeverDispatch() = runTest {
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(-1)
        runCurrent()
        identity = SessionIdentity(0, false, true)
        sessions.invalidate()
        state.select(10)
        runCurrent()
        state.toggle(state.state.value)
        runCurrent()
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
        assertNull(state.state.value.liked)
        assertFalse(state.state.value.busy)
        state.close()
    }

    @Test fun clicksDuringLoadingNeverAssumeUnliked() = runTest {
        val response = CompletableDeferred<Resource<Boolean>>()
        read = { response.await() }
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        state.toggle(state.state.value)
        runCurrent()
        assertTrue(writes.isEmpty())
        response.complete(Resource.Success(true))
        runCurrent()
        assertEquals(true, state.state.value.liked)
        state.close()
    }

    @Test fun repeatedClicksWriteOnceAndPublishOnlyAfterAcknowledgment() = runTest {
        val response = CompletableDeferred<Resource<Boolean>>()
        write = { _, _ -> response.await() }
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        val displayed = state.state.value
        state.toggle(displayed)
        state.toggle(displayed)
        state.toggle(state.state.value)
        runCurrent()
        assertEquals(listOf(Triple(10L, false, sessions.snapshot())), writes)
        assertEquals(true, state.state.value.liked)
        assertTrue(changes.isEmpty())
        response.complete(Resource.Success(false))
        runCurrent()
        assertEquals(false, state.state.value.liked)
        assertEquals(listOf(sessions.snapshot()), changes)
        state.close()
    }

    @Test fun readFailureClickRetriesWithoutWriting() = runTest {
        read = { Resource.Error("Offline") }
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        assertNull(state.state.value.liked)
        read = { Resource.Success(true) }
        state.toggle(state.state.value)
        runCurrent()
        assertEquals(2, reads.size)
        assertTrue(writes.isEmpty())
        assertEquals(true, state.state.value.liked)
        state.close()
    }

    @Test fun writeFailureClearsUncertainStateAndNextClickOnlyReconciles() = runTest {
        write = { _, _ -> Resource.Error("Unknown outcome") }
        val messages = mutableListOf<String>()
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        backgroundScope.launch { state.messages.collect(messages::add) }
        state.select(10)
        runCurrent()
        state.toggle(state.state.value)
        runCurrent()
        assertNull(state.state.value.liked)
        assertEquals(1, messages.size)
        assertTrue(changes.isEmpty())
        state.toggle(state.state.value)
        runCurrent()
        assertEquals(1, writes.size)
        assertEquals(true, state.state.value.liked)
        state.close()
    }

    @Test fun switchingAwayAndBackRejectsOldClickEvenForSameSongAndAccount() = runTest {
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        val stale = state.state.value
        state.select(20)
        state.select(10)
        runCurrent()
        state.toggle(stale)
        runCurrent()
        assertTrue(writes.isEmpty())
        state.close()
    }

    @Test fun nonCooperativeOldReadCannotOverwriteTheNewSelection() = runTest {
        val response = CompletableDeferred<Resource<Boolean>>()
        read = { if (it == 10L) withContext(NonCancellable) { response.await() } else Resource.Success(false) }
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        state.select(20)
        runCurrent()
        response.complete(Resource.Success(true))
        runCurrent()
        assertEquals(20L, state.state.value.songId)
        assertEquals(false, state.state.value.liked)
        state.close()
    }

    @Test fun nonCooperativeOldWriteCannotContaminateTheNewTrack() = runTest {
        val response = CompletableDeferred<Resource<Boolean>>()
        write = { _, _ -> withContext(NonCancellable) { response.await() } }
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(20)
        runCurrent()
        state.toggle(state.state.value)
        runCurrent()
        state.select(30)
        runCurrent()
        response.complete(Resource.Success(true))
        runCurrent()
        assertEquals(30L, state.state.value.songId)
        assertEquals(false, state.state.value.liked)
        state.close()
    }

    @Test fun accountAndSameAccountReauthorizationClearImmediatelyAndRejectOldActions() = runTest {
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        repeat(2) { index ->
            val stale = state.state.value
            if (index == 0) identity = SessionIdentity(2, true, false)
            sessions.invalidate()
            assertNull(state.state.value.liked)
            assertNull(state.state.value.owner)
            state.toggle(stale)
            runCurrent()
            assertEquals(sessions.snapshot(), state.state.value.owner)
            assertEquals(true, state.state.value.liked)
        }
        assertTrue(writes.isEmpty())
        assertEquals(3, reads.size)
        state.close()
    }

    @Test fun recoveryAndNullSelectionClearStateWithoutExtraRequests() = runTest {
        val state = PlayerLikeState(backgroundScope, sessions, source, changes::add)
        state.select(10)
        runCurrent()
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertNull(state.state.value.liked)
        assertEquals(1, reads.size)
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(true, state.state.value.liked)
        state.select(null)
        runCurrent()
        assertNull(state.state.value.songId)
        assertNull(state.state.value.liked)
        assertEquals(2, reads.size)
        state.close()
    }

    @Test fun failedLibraryRefreshDoesNotUndoAnAcceptedWrite() = runTest {
        val state = PlayerLikeState(backgroundScope, sessions, source) { error("Refresh failed") }
        state.select(10)
        runCurrent()
        state.toggle(state.state.value)
        runCurrent()
        assertEquals(false, state.state.value.liked)
        assertNull(state.state.value.error)
        state.close()
    }
}
