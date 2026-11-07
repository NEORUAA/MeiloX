package com.ljyh.mei.ui.screen.comment

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.source.shared.OnlineComment as CommentX
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.source.shared.SharedOnlineSession
import kotlinx.coroutines.CancellationException
import timber.log.Timber

class CommentPagingSource(
    private val repository: SharedOnlineSession,
    private val songId: String,
    private val sortType: CommentSortType,
    private val onTotalReceived: (Int) -> Unit = {}
) : PagingSource<Int, CommentX>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CommentX> {
        val pageNo = params.key ?: 1
        return try {
            val cursor = if (pageNo > 1) lastCursor else ""
            val result = repository.getComment(
                id = songId,
                sortType = sortType,
                pageNo = pageNo,
                pageSize = params.loadSize,
                cursor = cursor
            )

            when (result) {
                is Resource.Success -> {
                    val data = result.data
                    val comments = data.comments
                    val hasMore = data.hasMore
                    if (pageNo == 1) onTotalReceived(data.totalCount)
                    if (data.cursor != null || sortType == CommentSortType.TIME) {
                        lastCursor = data.cursor?.takeIf(String::isNotBlank)
                            ?: comments.lastOrNull()?.time?.toString() ?: lastCursor
                    }
                    LoadResult.Page(
                        data = comments,
                        prevKey = null,
                        nextKey = if (hasMore) pageNo + 1 else null
                    )
                }
                is Resource.Error -> LoadResult.Error(Exception(result.message))
                Resource.Loading -> LoadResult.Error(IllegalStateException("Comment request did not finish"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, CommentX>): Int? = null

    private var lastCursor: String = "0"
}
