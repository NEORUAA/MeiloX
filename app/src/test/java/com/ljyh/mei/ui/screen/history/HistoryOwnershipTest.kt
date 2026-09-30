package com.ljyh.mei.ui.screen.history

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.dao.HistoryDao
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.di.repository.HistoryRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
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
class HistoryOwnershipTest {
    private val source = SongSourceIdentity(999, 88, 7, 17)
    private fun row(id: String = source.key, historyId: Long = 1) =
        HistoryItem(Song(id, "Fixture", emptyList(), "", "", 123_871), historyId, 1_700_000_000_123)

    @Test fun accountChangeMasksPrivateRowsAndRejectsRetainedClicksWithoutDeletingData() = runTest {
        withFixture { f ->
            f.local.value = listOf(row(), row("2", 2))
            runCurrent()
            val original = f.model.state.value
            assertEquals(source, original.items.first().song.source)
            var clicked = false
            f.model.withCurrent(original, original.items) { clicked = true }
            assertTrue(clicked)
            f.identity = SessionIdentity(8, true, false)
            f.sessions.invalidate()
            clicked = false
            f.model.withCurrent(original, original.items) { clicked = true }
            assertFalse(clicked)
            runCurrent()
            assertEquals(listOf(2L), f.model.state.value.items.map { it.song.id })
            assertEquals(2, f.local.value.size)
            f.identity = SessionIdentity(7, true, false)
            f.sessions.invalidate()
            runCurrent()
            assertEquals(source, f.model.state.value.items.first().song.source)
        }
    }

    @Test fun recoveryClearsRemoteResultsButRetainsOwnedLocalFilesForOfflineReplay() = runTest {
        withFixture { f ->
            f.local.value = listOf(row())
            f.loadRecent = { listOf(remote(42)) }
            runCurrent()
            assertTrue(f.model.state.value.items.any { it.song.id == 42L })
            val calls = f.calls
            f.sessions.setRecoveryRequired(true)
            runCurrent()
            assertEquals(calls, f.calls)
            assertEquals(listOf(source), f.model.state.value.items.map { it.song.source })
            assertFalse(f.model.state.value.isRefreshing)
            f.model.refresh()
            runCurrent()
            assertEquals(calls, f.calls)
        }
    }

    @Test fun latePreviousAccountReplyCannotPolluteNewHistoryOrItsLoadingState() = runTest {
        withFixture { f ->
            val oldReply = CompletableDeferred<Unit>()
            val newReply = CompletableDeferred<Unit>()
            f.loadRecent = {
                if (f.calls == 1) withContext(NonCancellable) { oldReply.await(); listOf(remote(999)) }
                else { newReply.await(); listOf(remote(42)) }
            }
            runCurrent()
            f.identity = SessionIdentity(8, true, false)
            f.sessions.invalidate()
            runCurrent()
            assertTrue(f.model.state.value.isRefreshing)
            oldReply.complete(Unit)
            runCurrent()
            assertTrue(f.model.state.value.isRefreshing)
            assertTrue(f.model.state.value.items.isEmpty())
            newReply.complete(Unit)
            runCurrent()
            assertEquals(listOf(42L), f.model.state.value.items.map { it.song.id })
            assertEquals(8L, f.model.state.value.session?.identity?.userId)
        }
    }

    @Test fun aChangedSessionReaderMasksPrivateDataEvenBeforeTheAccountObserverUpdates() = runTest {
        withFixture { f ->
            f.local.value = listOf(row())
            runCurrent()
            assertEquals(1, f.model.state.value.items.size)
            f.identity = SessionIdentity(8, true, false)
            f.local.value = listOf(row(historyId = 2))
            runCurrent()
            assertTrue(f.model.state.value.items.isEmpty())
            assertNull(f.model.state.value.session)
            assertEquals(1, f.local.value.size)
        }
    }

    @Test fun substitutedMetadataAndRemovedEntriesCannotReuseAVisibleClick() = runTest {
        withFixture { f ->
            f.local.value = listOf(row())
            runCurrent()
            val state = f.model.state.value
            val entry = state.items.single()
            var clicks = 0
            f.model.withCurrent(state, listOf(entry.copy(song = entry.song.copy(source = source.copy(accountId = 8))))) { clicks++ }
            f.model.withCurrent(state, listOf(entry.copy(key = "missing"))) { clicks++ }
            f.local.value = emptyList()
            runCurrent()
            f.model.withCurrent(state, listOf(entry)) { clicks++ }
            assertEquals(0, clicks)
        }
    }

    @Test fun guestHistoryKeepsOrdinaryRowsWithoutCallingTheRemoteBackend() = runTest {
        withFixture { f ->
            f.identity = SessionIdentity(0, false, true)
            f.local.value = listOf(row(), row("2", 2))
            runCurrent()
            assertEquals(0, f.calls)
            assertEquals(listOf(2L), f.model.state.value.items.map { it.song.id })
            var clicked = false
            val state = f.model.state.value
            f.model.withCurrent(state, state.items) { clicked = true }
            assertTrue(clicked)
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try { block(fixture) } finally { fixture.close(); runCurrent(); Dispatchers.resetMain() }
    }

    private class Fixture(scope: TestScope) {
        var identity = SessionIdentity(7, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val local = MutableStateFlow<List<HistoryItem>>(emptyList())
        private val dao = Proxy.newProxyInstance(HistoryDao::class.java.classLoader, arrayOf(HistoryDao::class.java)) { _, method, _ ->
            check(method.name == "getHistory")
            local
        } as HistoryDao
        private val unused = Proxy.newProxyInstance(SongDao::class.java.classLoader, arrayOf(SongDao::class.java)) { _, _, _ ->
            error("Closed history fixture must not write")
        } as SongDao
        val accounts = AccountStore(sessions, {
            AccountProfile(identity.userId, "Fixture", null, null, null, null, null, null, null, null)
        }, scope.backgroundScope)
        var calls = 0
        var loadRecent: suspend () -> List<AccountSong> = { emptyList() }
        val model = HistoryViewModel(HistoryRepository(dao, unused, sessions), { calls++; loadRecent() }, accounts)
        private val store = ViewModelStore().apply { put("history", model) }
        fun close() { store.clear(); accounts.close() }
    }

    private fun remote(id: Long) = AccountSong(id, "Fixture", emptyList(), "", null, 123_871)
}
