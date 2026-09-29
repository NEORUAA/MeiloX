package com.ljyh.mei.ui.screen.comment

import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingSource
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.weapi.FloorComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
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
class CommentSessionTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val source = CommentTestSource()
    private fun checkModel(check: suspend TestScope.(CommentViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = CommentViewModel(source, sessions)
            store.put("comments", model)
            runCurrent()
            check(model, store)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    private suspend fun CommentPagingSource.first() = load(PagingSource.LoadParams.Refresh(null, 20, false))
    private fun CommentViewModel.replyIds() = (floorComments.value as Resource.Success).data.map { it.commentId }

    @Test fun songAndSortChangesInvalidatePagesAndCollapseReplies() = checkModel { model, _ ->
        assertTrue(source.pages.isEmpty())
        model.setSongId("10")
        val old = model.newPagingSource()
        old.first()
        model.toggleFloorComments(1, 1)
        runCurrent()
        assertEquals(listOf(1L), model.replyIds())
        model.setSongId("20")
        assertTrue(old.invalid)
        assertEquals(0, model.total.value)
        assertNull(model.expandedCommentId.value)
        val second = model.newPagingSource()
        second.first()
        model.setSortType(CommentSortType.TIME)
        assertTrue(second.invalid)
        model.newPagingSource().first()
        assertEquals(listOf("10", "20", "20"), source.pages.map { it.song })
        assertEquals(CommentSortType.TIME, source.pages.last().sort)
        assertEquals("0", source.pages.last().cursor)
    }

    @Test fun sameAccountInvalidationClearsRowsBeforeAnyDispatcherRuns() = checkModel { model, _ ->
        model.setSongId("10")
        val old = model.newPagingSource()
        old.first()
        model.toggleFloorComments(1, 1)
        runCurrent()
        val previous = source.pages.single().session
        sessions.invalidate()
        assertTrue(old.invalid)
        assertEquals(0, model.total.value)
        assertNull(model.expandedCommentId.value)
        assertEquals(Resource.Loading, model.floorComments.value)
        runCurrent()
        model.newPagingSource().first()
        assertNotEquals(previous, source.pages.last().session)
    }

    @Test fun floorPagesUseReturnedTimeAndDeduplicateUntilExplicitTerminalPage() = checkModel { model, _ ->
        model.setSongId("10")
        source.floor = { Resource.Success(if (it.time == -1L) replyPage(listOf(1, 2), true, 9000) else replyPage(listOf(2, 3))) }
        model.toggleFloorComments(50, 3)
        runCurrent()
        assertEquals(listOf(-1L, 9000L), source.replies.map { it.time })
        assertEquals(listOf(1L, 2L, 3L), model.replyIds())
        assertEquals(3, model.expandedFloorCount.value)
    }

    @Test fun failedReplyAppendIsNotPublishedAsCompleteAndCanReopen() = checkModel { model, _ ->
        model.setSongId("10")
        source.floor = { if (it.time == -1L) Resource.Success(replyPage(listOf(1), true)) else Resource.Error("Offline") }
        model.toggleFloorComments(50, 2)
        runCurrent()
        assertTrue(model.floorComments.value is Resource.Error)
        assertEquals(listOf(-1L, 1000L), source.replies.map { it.time })
        source.floor = { Resource.Success(replyPage(listOf(1, 2))) }
        model.toggleFloorComments(50, 2)
        model.toggleFloorComments(50, 2)
        runCurrent()
        assertEquals(listOf(1L, 2L), model.replyIds())
        assertEquals(-1L, source.replies.last().time)
        assertEquals(3, source.replies.size)
    }

    @Test fun nonAdvancingRepliesFailInsteadOfLooping() = checkModel { model, _ ->
        model.setSongId("10")
        source.floor = { Resource.Success(replyPage(listOf(1), true, 1000)) }
        model.toggleFloorComments(50, 100)
        runCurrent()
        assertEquals(2, source.replies.size)
        assertTrue(model.floorComments.value is Resource.Error)
    }

    @Test fun delayedOldParentCannotReplaceTheNewExpansion() {
        val pending = CompletableDeferred<FloorComment>()
        source.floor = { if (it.parent == 1L) withContext(NonCancellable) { Resource.Success(pending.await()) }
            else Resource.Success(replyPage(listOf(2))) }
        checkModel { model, _ ->
            try {
                model.setSongId("10")
                model.toggleFloorComments(1, 1)
                runCurrent()
                model.toggleFloorComments(2, 1)
                runCurrent()
            } finally { pending.complete(replyPage(listOf(1))); runCurrent() }
            assertEquals(2L, model.expandedCommentId.value)
            assertEquals(listOf(2L), model.replyIds())
        }
    }

    @Test fun collapseAndAccountChangeRejectNonCancellableReplies() {
        val pending = CompletableDeferred<FloorComment>()
        source.floor = { withContext(NonCancellable) { Resource.Success(pending.await()) } }
        checkModel { model, _ ->
            try {
                model.setSongId("10")
                model.toggleFloorComments(1, 1)
                runCurrent()
                model.toggleFloorComments(1, 1)
                assertNull(model.expandedCommentId.value)
                model.toggleFloorComments(2, 1)
                runCurrent()
                sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
                runCurrent()
            } finally { pending.complete(replyPage(listOf(1))); runCurrent() }
            assertNull(model.expandedCommentId.value)
            assertEquals(Resource.Loading, model.floorComments.value)
            source.floor = { Resource.Success(replyPage(listOf(3))) }
            model.toggleFloorComments(3, 1)
            runCurrent()
            assertEquals(listOf(3L), model.replyIds())
            assertEquals(2L, source.replies.last().session.identity.userId)
        }
    }

    @Test fun recoveryStopsRequestsAndGuestCanReadAfterRecovery() = checkModel { model, _ ->
        model.setSongId("10")
        sessions.setRecoveryRequired(true)
        runCurrent()
        assertTrue(model.newPagingSource().first() is PagingSource.LoadResult.Error)
        model.toggleFloorComments(1, 1)
        runCurrent()
        assertTrue(model.floorComments.value is Resource.Error)
        assertTrue(source.pages.isEmpty() && source.replies.isEmpty())
        sessions.beginTransition().use {
            identity = SessionIdentity(0, false, true)
            sessions.setRecoveryRequired(false)
        }
        runCurrent()
        assertTrue(model.newPagingSource().first() is PagingSource.LoadResult.Page)
        assertTrue(source.pages.single().session.identity.anonymous)
    }

    @Test fun clearingViewModelCancelsAndRejectsLateReplies() {
        val pending = CompletableDeferred<FloorComment>()
        source.floor = { withContext(NonCancellable) { Resource.Success(pending.await()) } }
        checkModel { model, store ->
            model.setSongId("10")
            model.toggleFloorComments(1, 1)
            runCurrent()
            store.clear()
            pending.complete(replyPage(listOf(1)))
            runCurrent()
            assertNull(model.expandedCommentId.value)
            assertEquals(Resource.Loading, model.floorComments.value)
        }
    }
}
