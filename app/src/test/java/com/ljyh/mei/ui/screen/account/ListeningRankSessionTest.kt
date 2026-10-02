package com.ljyh.mei.ui.screen.account

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.melox.UserPlayRecord
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
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
class ListeningRankSessionTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val calls = mutableListOf<Triple<Long, Boolean, SessionStamp>>()
    private var reply: suspend (Long, Boolean, SessionStamp) -> List<UserPlayRecord> = { id, all, _ -> records(id * 10 + if (all) 1 else 0) }

    private fun checkModel(ownerStore: SessionStore = sessions,
        block: suspend TestScope.(ListeningRankViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = ListeningRankViewModel({ id, all, owner -> calls += Triple(id, all, owner); reply(id, all, owner) }, ownerStore)
            store.put("rank", model)
            runCurrent()
            block(model, store)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun cacheKeepsTargetAndPeriodSeparateUnderOneCapturedSession() = checkModel { model, _ ->
        val owner = sessions.snapshot()
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        model.load(18, ListeningPeriod.Week)
        runCurrent()
        assertEquals(180L, model.state.value.records.single().song.id)
        model.load(17, ListeningPeriod.AllTime)
        runCurrent()
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        assertEquals(170L, model.state.value.records.single().song.id)
        assertEquals(listOf(Triple(17L, false, owner), Triple(18L, false, owner), Triple(17L, true, owner)), calls)
        model.load(17, ListeningPeriod.Week, force = true)
        runCurrent()
        assertEquals(4, calls.size)
    }

    @Test fun duplicatePendingLoadsDoNotDispatchAgain() = checkModel { model, _ ->
        val pending = CompletableDeferred<List<UserPlayRecord>>()
        reply = { _, _, _ -> pending.await() }
        model.load(17, ListeningPeriod.Week)
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        assertEquals(1, calls.size)
        pending.complete(records(1))
        runCurrent()
        assertFalse(model.state.value.loading)
    }

    @Test fun replacementAccountClearsSynchronouslyAndReloadsTheViewedTarget() = checkModel { model, _ ->
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        val old = model.state.value
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        assertTrue(model.state.value.records.isEmpty())
        assertNull(model.state.value.session)
        var clicks = 0
        model.withCurrent(old) { clicks++ }
        runCurrent()
        assertEquals(0, clicks)
        assertEquals(17L, calls.last().first)
        assertEquals(18L, calls.last().third.identity.userId)
        assertEquals(sessions.snapshot(), model.state.value.session)
    }

    @Test fun sameAccountReauthorizationCannotReuseItsPreviousCache() = checkModel { model, _ ->
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        sessions.invalidate()
        assertTrue(model.state.value.records.isEmpty())
        runCurrent()
        assertEquals(2, calls.size)
        assertNotEquals(calls.first().third, calls.last().third)
    }

    @Test fun lateNonCooperativePreviousAccountReplyCannotPopulateTheNewCache() = checkModel { model, _ ->
        val pending = CompletableDeferred<List<UserPlayRecord>>()
        reply = { _, _, owner -> if (owner.identity.userId == 17L) withContext(NonCancellable) { pending.await() } else records(2) }
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        runCurrent()
        pending.complete(records(999))
        runCurrent()
        assertEquals(2L, model.state.value.records.single().song.id)
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        assertEquals(2, calls.size)
        assertEquals(2L, model.state.value.records.single().song.id)
    }

    @Test fun retiredPeriodResponseOrFailureCannotReplaceTheCurrentRequest() = checkModel { model, _ ->
        val pending = CompletableDeferred<Unit>()
        reply = { _, all, _ -> if (!all) { withContext(NonCancellable) { pending.await() }; throw IOException("Retired week") } else records(2) }
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        model.load(17, ListeningPeriod.AllTime)
        runCurrent()
        pending.complete(Unit)
        runCurrent()
        assertEquals(ListeningPeriod.AllTime, model.state.value.period)
        assertEquals(2L, model.state.value.records.single().song.id)
        assertNull(model.state.value.error)
    }

    @Test fun recoveryDefersRequestsAndRejectsRetainedPlaybackCallbacks() = checkModel { model, _ ->
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        val old = model.state.value
        sessions.setRecoveryRequired(true)
        var clicks = 0
        model.withCurrent(old) { clicks++ }
        runCurrent()
        model.load(17, ListeningPeriod.AllTime)
        runCurrent()
        assertEquals(0, clicks)
        assertTrue(model.state.value.records.isEmpty())
        assertFalse(model.state.value.loading)
        assertEquals(1, calls.size)
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertTrue(calls.last().second)
        assertEquals(2, calls.size)
    }

    @Test fun transitionDefersTheRememberedReadUntilTheNewOwnerIsReady() = checkModel { model, _ ->
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        val transition = sessions.beginTransition()
        assertTrue(model.state.value.records.isEmpty())
        model.load(18, ListeningPeriod.AllTime)
        runCurrent()
        assertEquals(1, calls.size)
        identity = SessionIdentity(18, true, false)
        transition.close()
        runCurrent()
        assertEquals(Triple(18L, true, sessions.snapshot()), calls.last())
    }

    @Test fun unreadyAndGuestSessionsRetainPublicRankingReads() {
        val unready = SessionStore()
        checkModel(unready) { model, _ ->
            model.load(17, ListeningPeriod.Week)
            runCurrent()
            assertTrue(calls.isEmpty())
            identity = SessionIdentity(0, false, true)
            unready.bind { identity }
            runCurrent()
            assertEquals(Triple(17L, false, unready.snapshot()), calls.single())
        }
    }

    @Test fun failuresAllowRetryAndRefreshedRowsRejectOlderCallbacks() = checkModel { model, _ ->
        reply = { _, _, _ -> throw IOException("Synthetic failure") }
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        assertEquals("Synthetic failure", model.state.value.error)
        reply = { _, _, _ -> records(1) }
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        val old = model.state.value
        var clicks = 0
        model.withCurrent(old) { clicks++ }
        reply = { _, _, _ -> records(2) }
        model.load(17, ListeningPeriod.Week, force = true)
        val pending = model.state.value
        runCurrent()
        model.withCurrent(old) { clicks++ }
        model.withCurrent(pending) { clicks++ }
        model.withCurrent(model.state.value) { clicks++ }
        assertEquals(2, clicks)
        assertEquals(3, calls.size)
    }

    @Test fun clearingTheViewModelCancelsItsOwnedRead() = checkModel { model, store ->
        val pending = CompletableDeferred<List<UserPlayRecord>>()
        reply = { _, _, _ -> pending.await() }
        model.load(17, ListeningPeriod.Week)
        runCurrent()
        store.clear()
        runCurrent()
        pending.complete(records(999))
        runCurrent()
        assertTrue(model.state.value.records.isEmpty())
        assertNull(model.state.value.session)
    }

    private fun records(id: Long) = listOf(UserPlayRecord(AccountSong(id, "Fixture", emptyList(), "", null, 1000), 1, 1))
}
