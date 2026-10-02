package com.ljyh.mei.ui.screen.song

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.SongWiki
import com.ljyh.mei.data.model.melox.SongWikiMemoryItem
import com.ljyh.mei.data.model.melox.SongWikiMemoryKind
import com.ljyh.mei.data.model.melox.SongWikiPlaylistReference
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
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
class SongWikiSessionTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val calls = mutableListOf<Pair<Long, SessionStamp>>()
    private var reply: suspend (Long, SessionStamp) -> SongWiki = { id, owner -> wiki("$id-${owner.identity.userId}") }

    private fun checkModel(ownerStore: SessionStore = sessions,
        block: suspend TestScope.(SongWikiViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = SongWikiViewModel({ id, owner -> calls += id to owner; reply(id, owner) }, ownerStore)
            store.put("wiki", model)
            runCurrent()
            block(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun requestsCarryTheOwnerAndSameSessionCacheIsPreserved() = checkModel { model, _ ->
        model.load(10)
        runCurrent()
        assertEquals(listOf(10L to sessions.snapshot()), calls)
        assertEquals("10-17", model.state.value.wiki!!.memories.single().id)
        assertEquals(sessions.snapshot(), model.state.value.session)
        model.load(10)
        runCurrent()
        assertEquals(1, calls.size)
    }

    @Test fun duplicatePendingLoadsDoNotCreateAnotherRequest() = checkModel { model, _ ->
        val pending = CompletableDeferred<SongWiki>()
        reply = { _, _ -> pending.await() }
        model.load(10)
        model.load(10)
        runCurrent()
        model.load(10)
        runCurrent()
        assertEquals(1, calls.size)
        pending.complete(wiki("done"))
        runCurrent()
        assertFalse(model.state.value.isLoading)
    }

    @Test fun accountChangeClearsMemoriesSynchronouslyAndReloadsTheSameSong() = checkModel { model, _ ->
        model.load(10)
        runCurrent()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        assertNull(model.state.value.wiki)
        assertNull(model.state.value.session)
        runCurrent()
        assertEquals("10-18", model.state.value.wiki!!.memories.single().id)
        assertEquals(listOf(17L, 18L), calls.map { it.second.identity.userId })
    }

    @Test fun lateOldAccountMemoriesCannotOverwriteReplacementAccount() = checkModel { model, _ ->
        val pending = CompletableDeferred<SongWiki>()
        reply = { id, owner -> if (owner.identity.userId == 17L) withContext(NonCancellable) { pending.await() } else wiki("$id-new") }
        model.load(10)
        runCurrent()
        identity = SessionIdentity(18, true, false)
        sessions.invalidate()
        runCurrent()
        pending.complete(wiki("private-old"))
        runCurrent()
        assertEquals("10-new", model.state.value.wiki!!.memories.single().id)
        assertNull(model.state.value.error)
    }

    @Test fun sameAccountReauthorizationInvalidatesCachedMemories() = checkModel { model, _ ->
        model.load(10)
        runCurrent()
        val old = model.state.value.session
        sessions.invalidate()
        assertNull(model.state.value.wiki)
        runCurrent()
        assertEquals(2, calls.size)
        assertNotEquals(old, model.state.value.session)
    }

    @Test fun changingSongsRejectsLateNonCooperativeResponsesAndErrors() = checkModel { model, _ ->
        val pending = CompletableDeferred<Unit>()
        reply = { id, _ -> if (id == 10L) { withContext(NonCancellable) { pending.await() }; throw IOException("Old song") } else wiki("new-song") }
        model.load(10)
        runCurrent()
        model.load(20)
        runCurrent()
        pending.complete(Unit)
        runCurrent()
        assertEquals(20L, model.state.value.songId)
        assertEquals("new-song", model.state.value.wiki!!.memories.single().id)
        assertNull(model.state.value.error)
    }

    @Test fun recoveryClearsMemoriesAndDefersReloadUntilTheSessionRecovers() = checkModel { model, _ ->
        model.load(10)
        runCurrent()
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertNull(model.state.value.wiki)
        assertFalse(model.state.value.isLoading)
        model.load(10)
        runCurrent()
        assertEquals(1, calls.size)
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(2, calls.size)
        assertNotNull(model.state.value.wiki)
    }

    @Test fun sessionTransitionsClearOldMemoriesWithoutDispatchingDuringTheTransition() = checkModel { model, _ ->
        model.load(10)
        runCurrent()
        val transition = sessions.beginTransition()
        assertNull(model.state.value.wiki)
        runCurrent()
        model.load(10)
        runCurrent()
        assertEquals(1, calls.size)
        identity = SessionIdentity(18, true, false)
        transition.close()
        runCurrent()
        assertEquals("10-18", model.state.value.wiki!!.memories.single().id)
        assertEquals(2, calls.size)
    }

    @Test fun unreadySessionsRememberTheRequestedSongUntilBinding() {
        val unready = SessionStore()
        checkModel(unready) { model, _ ->
            model.load(10)
            runCurrent()
            assertTrue(calls.isEmpty())
            unready.bind { identity }
            runCurrent()
            assertEquals(10L, calls.single().first)
            assertEquals("10-17", model.state.value.wiki!!.memories.single().id)
        }
    }

    @Test fun anonymousSessionsCanStillReadWikiAndErrorsCanBeRetried() = checkModel { model, _ ->
        identity = SessionIdentity(0, false, true)
        sessions.invalidate()
        runCurrent()
        reply = { _, _ -> throw IOException("Offline fixture") }
        model.load(10)
        runCurrent()
        assertEquals("Offline fixture", model.state.value.error)
        assertFalse(model.state.value.isLoading)
        reply = { _, _ -> wiki("public") }
        model.load(10)
        runCurrent()
        assertEquals("public", model.state.value.wiki!!.memories.single().id)
        assertNull(model.state.value.error)
        assertTrue(calls.all { it.second == sessions.snapshot() })
    }

    @Test fun cancellationDoesNotBecomeAnErrorOrPermanentLoadingState() = checkModel { model, _ ->
        reply = { _, _ -> throw CancellationException("Retired wiki") }
        model.load(10)
        runCurrent()
        assertNull(model.state.value.error)
        assertFalse(model.state.value.isLoading)
        reply = { _, _ -> wiki("retry") }
        model.load(10)
        runCurrent()
        assertEquals("retry", model.state.value.wiki!!.memories.single().id)
    }

    @Test fun clearingTheViewModelRejectsLateMemoriesAndClosesTheObserver() = checkModel { model, store ->
        val pending = CompletableDeferred<SongWiki>()
        reply = { _, _ -> withContext(NonCancellable) { pending.await() } }
        model.load(10)
        runCurrent()
        store.clear()
        pending.complete(wiki("late-private"))
        runCurrent()
        sessions.invalidate()
        runCurrent()
        assertNull(model.state.value.wiki)
        assertEquals(1, calls.size)
    }

    @Test fun unavailableSessionReadersDiscardMemoriesWithoutCrashingAndAllowRetry() {
        var unavailable = false
        val ownerStore = SessionStore().apply { bind {
            if (unavailable) throw IOException("Unavailable session fixture")
            identity
        } }
        checkModel(ownerStore) { model, _ ->
            reply = { _, _ -> unavailable = true; wiki("unqualified") }
            model.load(10)
            runCurrent()
            assertNull(model.state.value.wiki)
            assertNull(model.state.value.session)
            assertFalse(model.state.value.isLoading)
            unavailable = false
            reply = { _, _ -> wiki("retry") }
            model.load(10)
            runCurrent()
            assertEquals("retry", model.state.value.wiki!!.memories.single().id)
        }
    }

    @Test fun currentAnonymousNavigationKeepsPlaylistAndContributionBrowsingBoundToTheSong() {
        identity = SessionIdentity(0, false, true)
        reply = { _, _ -> navigationWiki(1, "https://example.invalid/wiki/10") }
        checkModel { model, _ ->
            model.load(10)
            runCurrent()
            val displayed = model.state.value
            var navigations = 0
            model.withPlaylist(displayed, 10, 1) { navigations++ }
            model.withContribution(displayed, 10, "https://example.invalid/wiki/10") { navigations++ }
            model.withPlaylist(displayed, 10, 2) { navigations++ }
            model.withPlaylist(displayed, 20, 1) { navigations++ }
            model.withContribution(displayed, 10, "https://example.invalid/wiki/20") { navigations++ }
            model.withContribution(displayed, 20, "https://example.invalid/wiki/10") { navigations++ }
            assertEquals(2, navigations)
        }
    }

    @Test fun retainedWikiNavigationCannotCrossAReplacementAccount() {
        reply = { _, _ -> navigationWiki(1, "https://example.invalid/wiki") }
        checkModel { model, _ ->
            model.load(10)
            runCurrent()
            val displayed = model.state.value
            var navigations = 0
            val onClick = {
                model.withPlaylist(displayed, 10, 1) { navigations++ }
                model.withContribution(displayed, 10, "https://example.invalid/wiki") { navigations++ }
            }
            sessions.beginTransition().use { identity = SessionIdentity(18, true, false) }
            runCurrent()
            onClick()
            assertEquals(0, navigations)
            model.withPlaylist(model.state.value, 10, 1) { navigations++ }
            model.withContribution(model.state.value, 10, "https://example.invalid/wiki") { navigations++ }
            assertEquals(2, navigations)
        }
    }

    @Test fun retainedWikiNavigationCannotCrossSameAccountReauthorization() {
        reply = { _, _ -> navigationWiki(1, "https://example.invalid/wiki") }
        checkModel { model, _ ->
            model.load(10)
            runCurrent()
            val displayed = model.state.value
            var navigations = 0
            sessions.invalidate()
            runCurrent()
            model.withPlaylist(displayed, 10, 1) { navigations++ }
            model.withContribution(displayed, 10, "https://example.invalid/wiki") { navigations++ }
            assertEquals(0, navigations)
            model.withPlaylist(model.state.value, 10, 1) { navigations++ }
            model.withContribution(model.state.value, 10, "https://example.invalid/wiki") { navigations++ }
            assertEquals(2, navigations)
        }
    }

    @Test fun retainedWikiNavigationRejectsRecoveryWithTheSameOwner() {
        reply = { _, _ -> navigationWiki(1, "https://example.invalid/wiki") }
        checkModel { model, _ ->
            model.load(10)
            runCurrent()
            val displayed = model.state.value
            var navigations = 0
            val onClick = {
                model.withPlaylist(displayed, 10, 1) { navigations++ }
                model.withContribution(displayed, 10, "https://example.invalid/wiki") { navigations++ }
            }
            sessions.setRecoveryRequired(true)
            onClick()
            runCurrent()
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(displayed.session, model.state.value.session)
            onClick()
            assertEquals(0, navigations)
            model.withPlaylist(model.state.value, 10, 1) { navigations++ }
            model.withContribution(model.state.value, 10, "https://example.invalid/wiki") { navigations++ }
            assertEquals(2, navigations)
        }
    }

    @Test fun retainedWikiNavigationRejectsAChangedSongEvenWithIdenticalRelatedResources() {
        reply = { _, _ -> navigationWiki(1, "https://example.invalid/wiki") }
        checkModel { model, _ ->
            model.load(10)
            runCurrent()
            val displayed = model.state.value
            var navigations = 0
            model.withPlaylist(displayed, 20, 1) { navigations++ }
            model.withContribution(displayed, 20, "https://example.invalid/wiki") { navigations++ }
            model.load(20)
            runCurrent()
            model.withPlaylist(displayed, 10, 1) { navigations++ }
            model.withContribution(displayed, 10, "https://example.invalid/wiki") { navigations++ }
            assertEquals(0, navigations)
            model.withPlaylist(model.state.value, 20, 1) { navigations++ }
            model.withContribution(model.state.value, 20, "https://example.invalid/wiki") { navigations++ }
            assertEquals(2, navigations)
        }
    }

    @Test fun retainedWikiNavigationRejectsRemovedPlaylistsAndChangedContributionUrlsAfterReload() {
        reply = { _, _ -> navigationWiki(1, "https://example.invalid/old") }
        checkModel { model, _ ->
            model.load(10)
            runCurrent()
            val displayed = model.state.value
            var navigations = 0
            reply = { _, _ -> navigationWiki(2, "https://example.invalid/new") }
            model.load(20)
            runCurrent()
            model.load(10)
            runCurrent()
            model.withPlaylist(displayed, 10, 1) { navigations++ }
            model.withContribution(displayed, 10, "https://example.invalid/old") { navigations++ }
            val current = model.state.value
            model.withPlaylist(current, 10, 1) { navigations++ }
            model.withContribution(current, 10, "https://example.invalid/old") { navigations++ }
            assertEquals(0, navigations)
            model.withPlaylist(current, 10, 2) { navigations++ }
            model.withContribution(current, 10, "https://example.invalid/new") { navigations++ }
            assertEquals(2, navigations)
        }
    }

    private fun wiki(memory: String) = SongWiki(listOf(SongWikiMemoryItem(memory, SongWikiMemoryKind.FirstListen)),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)

    private fun navigationWiki(playlistId: Long, url: String) = wiki("navigation").copy(
        relatedPlaylists = listOf(SongWikiPlaylistReference(playlistId, "Playlist", null, 0)),
        contributionUrl = url,
    )
}
