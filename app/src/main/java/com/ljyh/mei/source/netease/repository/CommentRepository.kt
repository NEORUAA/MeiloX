package com.ljyh.mei.source.netease.repository

import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.api.GetComment
import com.ljyh.mei.data.model.api.GetFloorComment
import com.ljyh.mei.data.model.weapi.Comment
import com.ljyh.mei.data.model.weapi.FloorComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.google.gson.JsonObject
import com.ljyh.mei.data.network.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommentRepository(
    private val apiService: ApiService,
    private val weApiService: WeApiService,
    private val directEapi: MeloXDirectService,
    private val directWeapi: MeloXDirectService,
) {
    suspend fun getComment(
        id: String,
        resourceType: CommentResourceType = CommentResourceType.SONG,
        sortType: CommentSortType = CommentSortType.RECOMMEND,
        pageNo: Int = 1,
        pageSize: Int = 20,
        cursor: String = ""
    ): Resource<Comment> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                apiService.getComment(
                    GetComment(
                        threadId = resourceType.threadId(id),
                        pageNo = pageNo,
                        pageSize = pageSize,
                        sortType = sortType.value,
                        cursor = cursor
                    )
                ).also { response ->
                    check(response.code == 200) {
                        response.message?.takeIf(String::isNotBlank) ?: "NetEase request failed (${response.code})"
                    }
                    val data = checkNotNull(response.data) { "Comment data is missing" }
                    checkNotNull(data.comments) { "Comment rows are missing" }
                    checkNotNull(data.hasMore) { "Comment continuation is missing" }
                }
            }
        }
    }

    suspend fun getFloorComment(
        parentCommentId: Long,
        id: String,
        resourceType: CommentResourceType = CommentResourceType.SONG,
        limit: Int = 20,
        time: Long = -1
    ): Resource<FloorComment> {
        return withContext(Dispatchers.IO) {
            safeApiCall {
                weApiService.getFloorComment(
                    GetFloorComment(
                        parentCommentId = parentCommentId,
                        threadId = resourceType.threadId(id),
                        limit = limit,
                        time = time
                    )
                ).also { response ->
                    check(response.code == 200) {
                        response.message?.takeIf(String::isNotBlank) ?: "NetEase request failed (${response.code})"
                    }
                    val data = checkNotNull(response.data) { "Reply data is missing" }
                    checkNotNull(data.comments) { "Reply rows are missing" }
                    checkNotNull(data.hasMore) { "Reply continuation is missing" }
                }
            }
        }
    }

    suspend fun publishComment(
        id: String,
        content: String,
        expectedSession: String,
        replyToCommentId: Long? = null,
        resourceType: CommentResourceType = CommentResourceType.SONG,
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        safeApiCall {
            require(id.isNotBlank()) { "A comment resource is required" }
            require(content.isNotBlank()) { "A comment cannot be empty" }
            require(expectedSession.isNotBlank()) { "An authenticated session is required" }
            require(replyToCommentId == null || replyToCommentId > 0) { "Invalid reply target" }
            val body = mutableMapOf<String, Any>(
                "threadId" to resourceType.threadId(id),
                "content" to content.trim(),
            )
            replyToCommentId?.let { body["commentId"] = it }
            val action = if (replyToCommentId == null) "add" else "reply"
            // Mutations use one transport attempt; retrying can publish duplicate comments.
            requireCommentSuccess(
                directEapi.post(
                    "/eapi/resource/comments/$action",
                    body,
                    headers = mapOf(NETEASE_EXPECTED_SESSION_HEADER to expectedSession),
                ),
            )
        }
    }

    suspend fun setCommentLiked(
        id: String,
        commentId: Long,
        liked: Boolean,
        expectedSession: String,
        resourceType: CommentResourceType = CommentResourceType.SONG,
    ): Resource<Unit> = withContext(Dispatchers.IO) {
        safeApiCall {
            require(id.isNotBlank() && commentId > 0) { "Invalid comment resource" }
            require(expectedSession.isNotBlank()) { "An authenticated session is required" }
            val action = if (liked) "like" else "unlike"
            requireCommentSuccess(
                directWeapi.post(
                    "/weapi/v1/comment/$action",
                    mapOf("threadId" to resourceType.threadId(id), "commentId" to commentId),
                    headers = mapOf(NETEASE_EXPECTED_SESSION_HEADER to expectedSession),
                ),
            )
        }
    }
}

internal fun requireCommentSuccess(response: JsonObject) {
    val code = response.get("code")?.takeUnless { it.isJsonNull }?.asInt
    check(code == 200) {
        sequenceOf("message", "msg")
            .mapNotNull { response.get(it)?.takeUnless { value -> value.isJsonNull }?.asString }
            .firstOrNull(String::isNotBlank)
            ?: "NetEase request failed (${code ?: "missing code"})"
    }
}
