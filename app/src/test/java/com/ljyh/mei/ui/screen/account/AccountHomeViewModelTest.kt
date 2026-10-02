package com.ljyh.mei.ui.screen.account

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.AccountDetail
import com.ljyh.mei.data.model.melox.AccountPlaylist
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
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
class AccountHomeViewModelTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private var loadProfile: suspend (SessionStamp) -> AccountProfile = { profile(it.identity.userId) }
    private var loadDetail: suspend (Long, SessionStamp) -> AccountDetail = { userId, stamp ->
        sessions.requireCurrent(stamp)
        AccountDetail(profile(userId), 1, 10, null)
    }
    private var loadPlaylists: suspend (Long, SessionStamp) -> List<AccountPlaylist> = { userId, stamp ->
        sessions.requireCurrent(stamp)
        listOf(playlist(userId * 10, userId))
    }

    private fun checkModel(check: suspend TestScope.(AccountHomeViewModel, AccountStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val accounts = AccountStore(sessions, { loadProfile(it) }, backgroundScope)
        try {
            val viewModel = AccountHomeViewModel(
                accounts,
                { userId, stamp -> loadDetail(userId, stamp) },
                { userId, stamp -> loadPlaylists(userId, stamp) },
                { "Profile unavailable" },
            )
            store.put("account-home", viewModel)
            runCurrent()
            check(viewModel, accounts)
        } finally {
            store.clear()
            accounts.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun currentRenderedRankingsAndPlaylistNavigationRemainAvailable() = checkModel { viewModel, _ ->
        val navigations = mutableListOf<String>()
        assertFalse(viewModel.state.value.loading)
        viewModel.navigationActions(navigations::add).forEach { it() }
        assertEquals(listOf("rank:1", "playlist:10"), navigations)
    }

    @Test fun replacementAccountRejectsRetainedRankingsAndPlaylistNavigation() = checkModel { viewModel, _ ->
        val navigations = mutableListOf<String>()
        val old = viewModel.navigationActions(navigations::add)
        sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        runCurrent()
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        viewModel.navigationActions(navigations::add).forEach { it() }
        assertEquals(listOf("rank:2", "playlist:20"), navigations)
    }

    @Test fun sameAccountReauthorizationRejectsRetainedRankingsAndPlaylistNavigation() = checkModel { viewModel, _ ->
        val navigations = mutableListOf<String>()
        val old = viewModel.navigationActions(navigations::add)
        val owner = sessions.snapshot()
        sessions.invalidate()
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        runCurrent()
        assertEquals(owner.identity, sessions.snapshot().identity)
        assertNotEquals(owner, sessions.snapshot())
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        viewModel.navigationActions(navigations::add).forEach { it() }
        assertEquals(listOf("rank:1", "playlist:10"), navigations)
    }

    @Test fun sameStampRecoveryBlocksRetainedNavigationBeforeAccountObserversRun() = checkModel { viewModel, _ ->
        val navigations = mutableListOf<String>()
        val old = viewModel.navigationActions(navigations::add)
        val owner = sessions.snapshot()
        sessions.setRecoveryRequired(true)
        assertEquals(owner, sessions.snapshot())
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        runCurrent()
        sessions.setRecoveryRequired(false)
        runCurrent()
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        viewModel.navigationActions(navigations::add).forEach { it() }
        assertEquals(listOf("rank:1", "playlist:10"), navigations)
    }

    @Test fun refreshedProfileRejectsRetainedNavigationWithinTheSameSession() = checkModel { viewModel, _ ->
        val navigations = mutableListOf<String>()
        val old = viewModel.navigationActions(navigations::add)
        val owner = sessions.snapshot()
        loadProfile = { profile(it.identity.userId).copy(nickname = "Refreshed") }
        viewModel.refresh()
        runCurrent()
        assertEquals(owner, viewModel.state.value.session)
        assertEquals("Refreshed", viewModel.state.value.profile!!.nickname)
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        viewModel.navigationActions(navigations::add).forEach { it() }
        assertEquals(listOf("rank:1", "playlist:10"), navigations)
    }

    @Test fun identicalProfileAndResourceRefreshDoesNotReviveRetainedNavigation() {
        val sameProfile = profile(1)
        loadProfile = { sameProfile }
        checkModel { viewModel, _ ->
            val navigations = mutableListOf<String>()
            val before = viewModel.state.value
            val old = viewModel.navigationActions(navigations::add)
            viewModel.refresh()
            old.forEach { it() }
            assertTrue(navigations.isEmpty())
            runCurrent()
            val current = viewModel.state.value
            assertSame(before.profile, current.profile)
            assertEquals(before.copy(revision = current.revision), current)
            assertTrue(current.revision > before.revision)
            assertNotSame(before, current)
            old.forEach { it() }
            assertTrue(navigations.isEmpty())
            viewModel.navigationActions(navigations::add).forEach { it() }
            assertEquals(listOf("rank:1", "playlist:10"), navigations)
        }
    }

    @Test fun removedPlaylistRejectsItsRetainedNavigationAfterRefresh() = checkModel { viewModel, accounts ->
        val navigations = mutableListOf<String>()
        val old = viewModel.navigationActions(navigations::add)
        val owner = sessions.snapshot()
        loadPlaylists = { _, _ -> emptyList() }
        viewModel.refresh()
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        runCurrent()
        assertEquals(owner, viewModel.state.value.session)
        assertTrue(viewModel.state.value.playlists.isEmpty())
        old.forEach { it() }
        assertTrue(navigations.isEmpty())
        val current = viewModel.state.value
        assertFalse(current.loading)
        assertFalse(current.requiresLogin)
        assertEquals(accounts.state.value.session, current.session)
        assertEquals(accounts.state.value.profile, current.profile)
        assertFalse(accounts.state.value.recoveryRequired)
        assertTrue(accounts.state.value.authenticated)
        viewModel.withCurrent(current) { navigations += "rank:${current.profile!!.id}" }
        assertEquals(listOf("rank:1"), navigations)
    }

    @Test fun anotherRefreshRejectsANonCooperativeEarlierPlaylistReload() {
        val sameProfile = profile(1)
        loadProfile = { sameProfile }
        val pending = CompletableDeferred<List<AccountPlaylist>>()
        var calls = 0
        loadPlaylists = { userId, _ ->
            when (++calls) {
                1 -> listOf(playlist(10, userId))
                2 -> withContext(NonCancellable) { pending.await() }
                else -> listOf(playlist(30, userId))
            }
        }
        checkModel { viewModel, _ ->
            val owner = sessions.snapshot()
            try {
                viewModel.refresh()
                runCurrent()
                assertEquals(2, calls)
                viewModel.refresh()
            } finally {
                pending.complete(listOf(playlist(20, 1)))
                runCurrent()
            }
            assertEquals(owner, viewModel.state.value.session)
            assertEquals(listOf(30L), viewModel.state.value.playlists.map { it.id })
            val navigations = mutableListOf<String>()
            viewModel.navigationActions(navigations::add).forEach { it() }
            assertEquals(listOf("rank:1", "playlist:30"), navigations)
        }
    }

    @Test fun guestAndForgedProfileSnapshotsCannotNavigateAccountResources() {
        identity = SessionIdentity(0, false, true)
        checkModel { viewModel, _ ->
            var navigations = 0
            val current = viewModel.state.value
            assertTrue(current.requiresLogin)
            viewModel.withCurrent(current) { navigations++ }
            viewModel.withCurrent(current.copy(profile = profile(1))) { navigations++ }
            assertEquals(0, navigations)
        }
    }

    private companion object {
        fun AccountHomeViewModel.navigationActions(navigate: (String) -> Unit): List<() -> Unit> {
            val rendered = state.value
            val displayedProfile = requireNotNull(rendered.detail?.profile ?: rendered.profile)
            val playlist = rendered.playlists.single()
            return listOf(
                { withCurrent(rendered) { navigate("rank:${displayedProfile.id}") } },
                { withCurrent(rendered) { navigate("playlist:${playlist.id}") } },
            )
        }

        fun profile(id: Long) = AccountProfile(id, "Account $id", null, null, null, null, null, null, null, null)
        fun playlist(id: Long, userId: Long) = AccountPlaylist(id, "Playlist $id", null, 1, userId, "Account $userId")
    }
}
