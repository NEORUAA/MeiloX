package com.ljyh.mei.ui.screen.podcast

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgram
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.data.repository.PodcastSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastSessionTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val source = Source()

    private class Source : PodcastSource {
        var home: suspend () -> PodcastHome = { PodcastHome(emptyList(), emptyList(), emptyList()) }
        var category: suspend (Long) -> List<Podcast> = { emptyList() }
        var subscriptions: suspend (Int) -> PodcastPage = { PodcastPage(emptyList(), false, 0) }
        var detail: suspend (Long) -> PodcastDetail = { PodcastDetail(podcast(it), listOf(program(1)), false, 1) }
        var programs: suspend (Int) -> PodcastProgramPage = { PodcastProgramPage(emptyList(), false, 0) }
        var mutation: suspend (Long, Boolean) -> Unit = { _, _ -> }
        val subscriptionOffsets = mutableListOf<Int>()
        val programOffsets = mutableListOf<Int>()
        val writes = mutableListOf<Pair<Long, Boolean>>()
        val requestedSessions = mutableListOf<SessionStamp>()
        override suspend fun podcastHome(session: SessionStamp) = home().also { requestedSessions += session }
        override suspend fun podcasts(session: SessionStamp, categoryId: Long, offset: Int, limit: Int) = category(categoryId)
        override suspend fun podcastDetail(session: SessionStamp, id: Long, offset: Int, limit: Int) = detail(id)
        override suspend fun podcastPrograms(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastProgramPage {
            requestedSessions += session
            programOffsets += offset
            return programs(offset)
        }
        override suspend fun subscribedPodcasts(session: SessionStamp, offset: Int, limit: Int): PodcastPage {
            requestedSessions += session
            subscriptionOffsets += offset
            return subscriptions(offset)
        }
        override suspend fun setPodcastSubscribed(session: SessionStamp, id: Long, subscribed: Boolean) {
            requestedSessions += session
            writes += id to subscribed
            mutation(id, subscribed)
        }
    }

    private fun checkModels(check: suspend TestScope.(PodcastViewModel, PodcastDetailViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = AccountStore(sessions, {
            AccountProfile(identity.userId, "Account", null, null, null, null, null, null, null, null)
        }, backgroundScope)
        val store = ViewModelStore()
        try {
            val list = PodcastViewModel(source, accounts)
            val detail = PodcastDetailViewModel(source, accounts)
            store.put("list", list)
            store.put("detail", detail)
            check(list, detail, store)
        } finally {
            store.clear()
            accounts.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun officialIdentityLoadsSubscriptionsWithoutCookiePreferences() {
        source.subscriptions = { PodcastPage(listOf(podcast(1)), false, 1) }
        checkModels { list, _, _ ->
            runCurrent()
            list.selectTab(PodcastTab.Subscriptions)
            runCurrent()
            assertTrue(list.state.value.authenticated)
            assertEquals(listOf(1L), list.state.value.subscribedPodcasts.map { it.id })
            assertTrue(list.state.value.subscriptionsLoaded)
        }
    }

    @Test fun guestCanBrowseButCannotReadSubscriptionsOrMutate() {
        identity = SessionIdentity(0, false, true)
        checkModels { list, detail, _ ->
            runCurrent()
            list.selectTab(PodcastTab.Subscriptions)
            detail.load(1)
            runCurrent()
            detail.toggleSubscription()
            runCurrent()
            assertFalse(list.state.value.authenticated)
            assertTrue(source.subscriptionOffsets.isEmpty())
            assertTrue(source.writes.isEmpty())
            assertEquals("Official sign-in required", detail.state.value.error)
        }
    }

    @Test fun subscriptionCursorUsesRawPageSizeAndRejectsConcurrentLoads() {
        source.subscriptions = { offset -> when (offset) {
            0 -> PodcastPage(listOf(podcast(1), podcast(1)), true, 4, fetchedCount = 3)
            3 -> PodcastPage(listOf(podcast(1), podcast(2)), true, 4)
            else -> PodcastPage(listOf(podcast(3)), false, 4)
        } }
        checkModels { list, _, _ ->
            runCurrent()
            list.ensureSubscriptionsLoaded()
            runCurrent()
            list.loadMoreSubscriptions()
            list.loadMoreSubscriptions()
            runCurrent()
            list.loadMoreSubscriptions()
            runCurrent()
            assertEquals(listOf(0, 3, 5), source.subscriptionOffsets)
            assertEquals(listOf(1L, 2L, 3L), list.state.value.subscribedPodcasts.map { it.id })
            assertFalse(list.state.value.hasMoreSubscriptions)
        }
    }

    @Test fun stalledSubscriptionPageIsAnErrorAndKeepsItsRetryCursor() {
        source.subscriptions = { PodcastPage(listOf(podcast(1)), true, 3) }
        checkModels { list, _, _ ->
            runCurrent()
            list.ensureSubscriptionsLoaded()
            runCurrent()
            list.loadMoreSubscriptions()
            runCurrent()
            assertEquals("Podcast pagination did not advance", list.state.value.subscriptionsLoadMoreError)
            assertEquals(1, list.state.value.subscriptionOffset)
            source.subscriptions = { PodcastPage(listOf(podcast(2)), false, 2) }
            list.loadMoreSubscriptions()
            runCurrent()
            assertEquals(listOf(0, 1, 1), source.subscriptionOffsets)
            assertEquals(2, list.state.value.subscribedPodcasts.size)
        }
    }

    @Test fun accountChangeClearsSubscriptionsAndRejectsThePreviousAccountsLatePage() {
        val pending = CompletableDeferred<PodcastPage>()
        source.subscriptions = {
            if (identity.userId == 1L) withContext(NonCancellable) { pending.await() }
            else PodcastPage(listOf(podcast(2)), false, 1)
        }
        checkModels { list, _, _ ->
            try {
                runCurrent()
                list.ensureSubscriptionsLoaded()
                runCurrent()
                sessions.beginTransition().use {
                    identity = SessionIdentity(2, true, false)
                    assertNull(list.state.value.session)
                    assertTrue(list.state.value.subscribedPodcasts.isEmpty())
                }
                runCurrent()
                assertEquals(listOf(2L), list.state.value.subscribedPodcasts.map { it.id })
            } finally {
                pending.complete(PodcastPage(listOf(podcast(1)), false, 1))
                runCurrent()
            }
            assertEquals(listOf(2L), list.state.value.subscribedPodcasts.map { it.id })
        }
    }

    @Test fun rapidReselectionOfTheSameCategoryRejectsAnOlderResult() {
        val old = CompletableDeferred<List<Podcast>>()
        var loads = 0
        source.category = { if (loads++ == 0) withContext(NonCancellable) { old.await() } else listOf(podcast(2)) }
        checkModels { list, _, _ ->
            try {
                runCurrent()
                list.selectCategory(1)
                runCurrent()
                list.selectCategory(1)
                runCurrent()
            } finally {
                old.complete(listOf(podcast(1)))
                runCurrent()
            }
            assertEquals(listOf(2L), list.state.value.categoryPodcasts.map { it.id })
        }
    }

    @Test fun refreshFailurePreservesCurrentSubscriptionsAndCanRecover() {
        source.subscriptions = { PodcastPage(listOf(podcast(1)), false, 1) }
        checkModels { list, _, _ ->
            runCurrent()
            list.ensureSubscriptionsLoaded()
            runCurrent()
            source.subscriptions = { error("Offline") }
            list.refreshSubscriptions()
            runCurrent()
            assertEquals("Offline", list.state.value.subscriptionsError)
            assertEquals(1, list.state.value.subscribedPodcasts.size)
            source.subscriptions = { PodcastPage(emptyList(), false, 0) }
            list.refreshSubscriptions()
            runCurrent()
            assertNull(list.state.value.subscriptionsError)
            assertTrue(list.state.value.subscribedPodcasts.isEmpty())
        }
    }

    @Test fun programPagesAndBulkActionsUseTheRawCursor() {
        source.detail = { PodcastDetail(podcast(it), listOf(program(1), program(1)), true, 4, nextOffset = 3) }
        source.programs = { offset -> if (offset == 3) PodcastProgramPage(listOf(program(1), program(2)), true, 4)
            else PodcastProgramPage(listOf(program(3)), false, 4) }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            detail.loadMore()
            detail.loadMore()
            runCurrent()
            assertEquals(5, detail.state.value.detail!!.nextOffset)
            assertEquals(listOf(1L, 2L, 3L), detail.allPrograms(1).map { it.id })
            assertEquals(listOf(3, 5), source.programOffsets)
            assertEquals(3, detail.allPrograms(1).size)
            assertEquals(2, source.programOffsets.size)
        }
    }

    @Test fun downloadPreparationRejectsAnotherDetailRecoveryAndStaleAuthorization() {
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            val owner = sessions.snapshot()
            detail.requireDetail(owner, 1)
            assertTrue(runCatching { detail.requireDetail(owner, 2) }.isFailure)
            sessions.setRecoveryRequired(true)
            assertTrue(runCatching { detail.requireDetail(owner, 1) }.isFailure)
            sessions.setRecoveryRequired(false)
            sessions.invalidate()
            assertTrue(runCatching { detail.requireDetail(owner, 1) }.isFailure)
        }
    }

    @Test fun accountChangeInvalidatesTheBulkCacheAndDetailSubscriptionState() {
        source.detail = { PodcastDetail(podcast(it).copy(isSubscribed = identity.userId == 1L), listOf(program(identity.userId)), false, 1) }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            assertEquals(1L, detail.allPrograms(1).single().id)
            sessions.beginTransition().use {
                identity = SessionIdentity(2, true, false)
                assertNull(detail.state.value.detail)
            }
            runCurrent()
            assertFalse(detail.state.value.detail!!.podcast.isSubscribed)
            assertEquals(2L, detail.allPrograms(1).single().id)
        }
    }

    @Test fun obsoleteMutationFailureCannotUndoAForceReload() {
        val old = CompletableDeferred<Unit>()
        source.mutation = { _, _ -> withContext(NonCancellable) { old.await(); error("Old failure") } }
        checkModels { _, detail, _ ->
            try {
                detail.load(1)
                runCurrent()
                detail.toggleSubscription()
                detail.toggleSubscription()
                runCurrent()
                assertEquals(1, source.writes.size)
                detail.load(1, true)
                runCurrent()
            } finally {
                old.complete(Unit)
                runCurrent()
            }
            assertNull(detail.state.value.error)
            assertFalse(detail.state.value.isUpdatingSubscription)
            assertTrue(detail.state.value.detail!!.podcast.isSubscribed)
        }
    }

    @Test fun currentMutationFailureRollsBackOnlyTheOptimisticSubscription() {
        source.mutation = { _, _ -> error("Rejected") }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            detail.toggleSubscription()
            assertFalse(detail.state.value.detail!!.podcast.isSubscribed)
            runCurrent()
            assertTrue(detail.state.value.detail!!.podcast.isSubscribed)
            assertEquals("Rejected", detail.state.value.error)
        }
    }

    @Test fun accountChangeStopsBulkPagingBeforeAnotherPageOrCachePublication() {
        val page = CompletableDeferred<PodcastProgramPage>()
        source.detail = { PodcastDetail(podcast(it), listOf(program(1)), true, 3) }
        source.programs = { withContext(NonCancellable) { page.await() } }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            val bulk = async { runCatching { detail.allPrograms(1) } }
            runCurrent()
            sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
            page.complete(PodcastProgramPage(listOf(program(2)), true, 3))
            runCurrent()
            assertTrue(bulk.await().isFailure)
            assertEquals(listOf(1), source.programOffsets)
        }
    }

    @Test fun stalledProgramPageDoesNotSilentlyDeclareTheListComplete() {
        source.detail = { PodcastDetail(podcast(it), listOf(program(1)), true, 2) }
        source.programs = { PodcastProgramPage(listOf(program(1)), true, 2) }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            detail.loadMore()
            runCurrent()
            assertEquals("Podcast pagination did not advance", detail.state.value.loadMoreError)
            assertTrue(detail.state.value.detail!!.hasMore)
            assertTrue(runCatching { detail.allPrograms(1) }.isFailure)
        }
    }

    @Test fun clearingViewModelsPreventsLatePresentation() {
        val late = CompletableDeferred<PodcastHome>()
        source.home = { withContext(NonCancellable) { late.await() } }
        checkModels { list, _, store ->
            runCurrent()
            store.clear()
            val before = list.state.value
            late.complete(PodcastHome(emptyList(), listOf(podcast(1)), emptyList()))
            runCurrent()
            assertEquals(before, list.state.value)
        }
    }

    @Test fun bulkFailureDoesNotCachePartialProgramsOrRetryAutomatically() {
        source.detail = { PodcastDetail(podcast(it), listOf(program(1)), true, 3) }
        source.programs = { offset ->
            if (offset == 1) PodcastProgramPage(listOf(program(2)), true, 3)
            else error("Rate limited")
        }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            val failure = runCatching { detail.allPrograms(1) }.exceptionOrNull()
            assertEquals("Rate limited", failure?.message)
            runCurrent()
            assertEquals(listOf(1, 2), source.programOffsets)
            assertEquals(listOf(1L), detail.state.value.detail!!.programs.map { it.id })
            source.programs = { PodcastProgramPage(listOf(program(2), program(3)), false, 3) }
            assertEquals(listOf(1L, 2L, 3L), detail.allPrograms(1).map { it.id })
            assertEquals(listOf(1, 2, 1), source.programOffsets)
        }
    }

    @Test fun failedSessionRecoveryShowsErrorsAndResumesAfterRecovery() {
        checkModels { list, detail, _ ->
            detail.load(1)
            runCurrent()
            val transition = sessions.beginTransition()
            try {
                sessions.setRecoveryRequired(true)
                runCurrent()
                assertFalse(list.state.value.isLoading)
                assertEquals("Official session recovery is required", list.state.value.error)
                assertEquals(list.state.value.error, list.state.value.subscriptionsError)
                assertFalse(detail.state.value.isLoading)
                assertEquals(list.state.value.error, detail.state.value.error)
                assertNull(detail.state.value.detail)
            } finally {
                sessions.setRecoveryRequired(false)
                transition.close()
                runCurrent()
            }
            assertNull(list.state.value.error)
            assertEquals(1L, detail.state.value.detail!!.podcast.id)
            assertFalse(detail.state.value.isLoading)
        }
    }

    @Test fun accountChangeBeforeAScheduledMutationNeverWritesToTheNewAccount() {
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            detail.toggleSubscription()
            sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
            runCurrent()
            assertTrue(source.writes.isEmpty())
            assertEquals(2L, detail.state.value.session!!.identity.userId)
        }
    }

    @Test fun successfulMutationCarriesItsOwningStampAndReturningToSubscriptionsRefreshes() {
        checkModels { list, detail, _ ->
            runCurrent()
            list.refreshSubscriptions()
            list.refreshSubscriptions()
            detail.load(1)
            runCurrent()
            assertEquals(listOf(0), source.subscriptionOffsets)
            detail.toggleSubscription()
            runCurrent()
            assertEquals(sessions.snapshot(), source.requestedSessions.last())
            assertEquals(listOf(1L to false), source.writes)
            assertFalse(detail.state.value.detail!!.podcast.isSubscribed)
            list.refreshSubscriptions()
            runCurrent()
            assertEquals(listOf(0, 0), source.subscriptionOffsets)
        }
    }

    @Test fun sameAccountReauthorizationClearsTheProgramCache() {
        var episode = 1L
        source.detail = { PodcastDetail(podcast(it), listOf(program(episode)), false, 1) }
        checkModels { _, detail, _ ->
            detail.load(1)
            runCurrent()
            assertEquals(1L, detail.allPrograms(1).single().id)
            episode = 2L
            sessions.invalidate()
            assertNull(detail.state.value.detail)
            runCurrent()
            assertEquals(2L, detail.allPrograms(1).single().id)
        }
    }

    companion object {
        private fun podcast(id: Long) = Podcast(id, "Podcast $id", null, null, null, null, null, null,
            0, 0, 0, null, true, null)
        private fun program(id: Long) = PodcastProgram(id, "Episode $id", null, null, null, 1000,
            0, 0, 0, null, 1, "Podcast", null, id)
    }
}
