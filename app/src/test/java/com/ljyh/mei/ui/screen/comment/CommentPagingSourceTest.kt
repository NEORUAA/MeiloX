package com.ljyh.mei.ui.screen.comment

import androidx.paging.PagingSource
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.weapi.CommentX
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommentPagingSourceTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(0, false, true) } }
    private val source = CommentTestSource()
    private fun paging(sort: CommentSortType = CommentSortType.RECOMMEND, total: (Int) -> Unit = {}) =
        CommentPagingSource(source, sessions, sessions.snapshot(), "10", sort, onTotalReceived = total)
    private fun refresh(size: Int = 20) = PagingSource.LoadParams.Refresh<CommentPageKey>(null, size, false)
    private fun append(key: CommentPageKey) = PagingSource.LoadParams.Append(key, 20, false)
    private fun PagingSource.LoadResult<CommentPageKey, CommentX>.page() = this as PagingSource.LoadResult.Page

    @Test fun rawOffsetsDoNotDependOnInitialAndAppendLoadSizes() = runTest {
        source.page = { Resource.Success(commentPage(if (it.page == 1) listOf(1, 1, 2) else listOf(2, 3), more = true)) }
        for (sort in listOf(CommentSortType.RECOMMEND, CommentSortType.HOT)) {
            val paging = paging(sort)
            val first = paging.load(refresh(60)).page()
            assertEquals(listOf(1L, 2L), first.data.map { it.commentId })
            assertEquals(3, first.nextKey!!.offset)
            assertEquals(if (sort == CommentSortType.HOT) "normalHot#3" else "3", first.nextKey!!.cursor)
            val second = paging.load(append(first.nextKey!!)).page()
            assertEquals(listOf(3L), second.data.map { it.commentId })
            assertEquals(5, second.nextKey!!.offset)
            assertEquals(3, second.nextKey!!.page)
        }
        assertEquals(listOf(60, 20, 60, 20), source.pages.map { it.size })
        assertTrue(source.pages.all { it.session.identity.anonymous })
    }

    @Test fun returnedOpaqueCursorTakesPrecedenceOverFallbacks() = runTest {
        source.page = { Resource.Success(commentPage(listOf(1), more = true, cursor = "opaque-next")) }
        CommentSortType.entries.forEach {
            assertEquals("opaque-next", paging(it).load(refresh()).page().nextKey!!.cursor)
        }
    }

    @Test fun timeCursorIsLocalToEachSourceAndFailureRetriesTheSameKey() = runTest {
        source.page = { if (it.page == 1) Resource.Success(commentPage(listOf(20, 10), more = true)) else Resource.Error("Offline") }
        val first = paging(CommentSortType.TIME)
        val key = first.load(refresh()).page().nextKey!!
        assertEquals("10000", key.cursor)
        assertTrue(first.load(append(key)) is PagingSource.LoadResult.Error)
        paging(CommentSortType.TIME).load(refresh())
        source.page = { Resource.Success(commentPage(listOf(5))) }
        assertEquals(listOf(5L), first.load(append(key)).page().data.map { it.commentId })
        assertEquals(listOf("0", "10000", "0", "10000"), source.pages.map { it.cursor })
    }

    @Test fun duplicateOnlyPageAndCursorCyclesAreErrorsWithoutPoisoningRetry() = runTest {
        source.page = { Resource.Success(commentPage(listOf(1), true, "next")) }
        val paging = paging()
        val key = paging.load(refresh()).page().nextKey!!
        assertTrue(paging.load(append(key)) is PagingSource.LoadResult.Error)
        source.page = { Resource.Success(commentPage(listOf(2), true, "0")) }
        assertTrue(paging.load(append(key)) is PagingSource.LoadResult.Error)
        source.page = { Resource.Success(commentPage(listOf(2))) }
        assertEquals(listOf(2L), paging.load(append(key)).page().data.map { it.commentId })
    }

    @Test fun malformedAndBusinessFailuresAreNotEmptySuccesses() = runTest {
        val good = commentPage(listOf(1))
        listOf(good.copy(code = 301), good.copy(data = null),
            good.copy(data = good.data!!.copy(comments = null)),
            good.copy(data = good.data!!.copy(hasMore = null)), commentPage(listOf(0)),
            commentPage(emptyList(), more = true)).forEach { response ->
            source.page = { Resource.Success(response) }
            assertTrue(paging().load(refresh()) is PagingSource.LoadResult.Error)
        }
    }

    @Test fun totalPublishesOnlyForSuccessfulCurrentFirstPage() = runTest {
        var total = -1
        source.page = { Resource.Success(commentPage(listOf(it.page.toLong()), it.page == 1, total = 99)) }
        val paging = paging(total = { total = it })
        val first = paging.load(refresh()).page()
        assertEquals(99, total)
        total = 5
        paging.load(append(first.nextKey!!))
        assertEquals(5, total)
        paging.invalidate()
        assertTrue(paging.load(refresh()) is PagingSource.LoadResult.Invalid)
        assertEquals(2, source.pages.size)
    }

    @Test fun sessionInvalidationRejectsAnAlreadyDispatchedPage() = runTest {
        val pending = CompletableDeferred<Unit>()
        source.page = { pending.await(); Resource.Success(commentPage(listOf(1))) }
        var total = -1
        val paging = paging(total = { total = it })
        val result = async { paging.load(refresh()) }
        testScheduler.runCurrent()
        sessions.invalidate()
        pending.complete(Unit)
        assertTrue(result.await() is PagingSource.LoadResult.Invalid)
        assertEquals(-1, total)
    }

    @Test fun unavailableSessionAndRecoveryMakeNoRequestAndCancellationPropagates() = runTest {
        val unbound = CommentPagingSource(source, sessions, null, "10", CommentSortType.TIME)
        assertTrue(unbound.load(refresh()) is PagingSource.LoadResult.Error)
        sessions.setRecoveryRequired(true)
        assertTrue(paging().load(refresh()) is PagingSource.LoadResult.Error)
        assertTrue(source.pages.isEmpty())
        sessions.setRecoveryRequired(false)
        source.page = { throw CancellationException("Canceled") }
        assertTrue(runCatching { paging().load(refresh()) }.exceptionOrNull() is CancellationException)
    }
}
