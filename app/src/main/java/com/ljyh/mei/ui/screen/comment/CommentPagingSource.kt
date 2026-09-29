package com.ljyh.mei.ui.screen.comment

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.weapi.CommentX
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.CommentSource
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CommentPageKey(val page: Int, val cursor: String, val offset: Int)

class CommentPagingSource(
    private val repository: CommentSource,
    private val sessions: SessionStore,
    private val owner: SessionStamp?,
    private val songId: String,
    private val sortType: CommentSortType,
    private val isCurrent: () -> Boolean = { true },
    private val onTotalReceived: (Int) -> Unit = {},
) : PagingSource<CommentPageKey, CommentX>() {
    private val seen = mutableSetOf<Long>()
    private val cursors = mutableSetOf<String>()

    override suspend fun load(params: LoadParams<CommentPageKey>): LoadResult<CommentPageKey, CommentX> {
        if (invalid || !isCurrent()) return LoadResult.Invalid()
        val stamp = owner ?: return LoadResult.Error(IllegalStateException("Account session is not ready"))
        val key = params.key ?: CommentPageKey(1, if (sortType == CommentSortType.HOT) "normalHot#0" else "0", 0)
        return try {
            check(!sessions.recoveryRequired.value) { "Account session recovery is required" }
            sessions.requireCurrent(stamp)
            val response = repository.getComment(stamp, songId, CommentResourceType.SONG, sortType,
                key.page, params.loadSize.coerceIn(1, 100), key.cursor)
            currentCoroutineContext().ensureActive()
            val result = when (response) {
                is Resource.Success -> response.data.also { check(it.code == 200) { "Comment request failed (${it.code})" } }
                is Resource.Error -> error(response.message)
                Resource.Loading -> error("Comment request did not complete")
            }
            val data = checkNotNull(result.data)
            val raw = checkNotNull(data.comments)
            check(raw.all { it.commentId > 0 }) { "Invalid comment identity" }
            val prior = if (key.page == 1) emptySet() else seen
            val rows = raw.distinctBy { it.commentId }.filter { it.commentId !in prior }
            val more = checkNotNull(data.hasMore)
            val offset = Math.addExact(key.offset, raw.size)
            val next = if (!more) null else {
                check(raw.isNotEmpty() && rows.isNotEmpty()) { "Comment pagination did not advance" }
                val cursor = data.cursor?.takeIf { it.isNotBlank() } ?: when (sortType) {
                    CommentSortType.TIME -> raw.last().time.takeIf { it > 0 }?.toString()
                        ?: error("Comment time cursor is missing")
                    CommentSortType.HOT -> "normalHot#$offset"
                    CommentSortType.RECOMMEND -> offset.toString()
                }
                check(cursor != key.cursor && (key.page == 1 || cursor !in cursors)) { "Comment cursor did not advance" }
                CommentPageKey(Math.addExact(key.page, 1), cursor, offset)
            }
            sessions.withCurrent(stamp) {
                if (invalid || !isCurrent() || sessions.recoveryRequired.value) LoadResult.Invalid()
                else {
                    if (key.page == 1) { seen.clear(); cursors.clear(); onTotalReceived(data.totalCount?.coerceAtLeast(0) ?: 0) }
                    seen += rows.map { it.commentId }
                    cursors += key.cursor
                    LoadResult.Page(rows, null, next)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: SessionChangedException) { LoadResult.Invalid() }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (invalid || !isCurrent()) LoadResult.Invalid() else LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<CommentPageKey, CommentX>): CommentPageKey? = null
}
