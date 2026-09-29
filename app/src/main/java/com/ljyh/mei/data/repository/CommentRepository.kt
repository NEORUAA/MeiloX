package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.api.GetComment
import com.ljyh.mei.data.model.api.GetFloorComment
import com.ljyh.mei.data.model.weapi.Comment
import com.ljyh.mei.data.model.weapi.FloorComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.safeApiCall
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CommentSource {
    suspend fun getComment(session: SessionStamp, id: String, resourceType: CommentResourceType,
        sortType: CommentSortType, pageNo: Int, pageSize: Int, cursor: String): Resource<Comment>
    suspend fun getFloorComment(session: SessionStamp, parentCommentId: Long, id: String,
        resourceType: CommentResourceType, limit: Int, time: Long): Resource<FloorComment>
}

class CommentRepository(
    private val apiService: ApiService,
    private val weApiService: WeApiService
) : CommentSource {
    override suspend fun getComment(
        session: SessionStamp,
        id: String,
        resourceType: CommentResourceType,
        sortType: CommentSortType,
        pageNo: Int,
        pageSize: Int,
        cursor: String,
    ): Resource<Comment> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                require(id.isNotBlank() && pageNo > 0 && pageSize in 1..100)
                apiService.getComment(
                    GetComment(
                        threadId = resourceType.threadId(id),
                        pageNo = pageNo,
                        pageSize = pageSize,
                        sortType = sortType.value,
                        cursor = cursor
                    ), expectedSession = session,
                ).also { response ->
                    check(response.code == 200) { "Comment request failed (${response.code})" }
                    val data = checkNotNull(response.data) { "Comment data is missing" }
                    checkNotNull(data.comments) { "Comment rows are missing" }
                    checkNotNull(data.hasMore) { "Comment continuation is missing" }
                }
            }
        }
    }

    override suspend fun getFloorComment(
        session: SessionStamp,
        parentCommentId: Long,
        id: String,
        resourceType: CommentResourceType,
        limit: Int,
        time: Long,
    ): Resource<FloorComment> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                require(id.isNotBlank() && parentCommentId > 0 && limit in 1..100)
                weApiService.getFloorComment(
                    GetFloorComment(
                        parentCommentId = parentCommentId,
                        threadId = resourceType.threadId(id),
                        limit = limit,
                        time = time
                    ), expectedSession = session,
                ).also { response ->
                    check(response.code == 200) { "Comment replies failed (${response.code})" }
                    val data = checkNotNull(response.data) { "Reply data is missing" }
                    checkNotNull(data.comments) { "Reply rows are missing" }
                    checkNotNull(data.hasMore) { "Reply continuation is missing" }
                }
            }
        }
    }
}
