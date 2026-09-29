package com.ljyh.mei.ui.screen.comment

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.weapi.Comment
import com.ljyh.mei.data.model.weapi.FloorComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.CommentSource
import com.ljyh.mei.data.session.SessionStamp
import org.junit.Assert.assertEquals

internal class CommentTestSource : CommentSource {
    data class PageRequest(val session: SessionStamp, val song: String, val sort: CommentSortType,
        val page: Int, val size: Int, val cursor: String)
    data class ReplyRequest(val session: SessionStamp, val song: String, val parent: Long, val time: Long)
    val pages = mutableListOf<PageRequest>()
    val replies = mutableListOf<ReplyRequest>()
    var page: suspend (PageRequest) -> Resource<Comment> = { Resource.Success(commentPage(listOf(1))) }
    var floor: suspend (ReplyRequest) -> Resource<FloorComment> = { Resource.Success(replyPage(listOf(1))) }

    override suspend fun getComment(session: SessionStamp, id: String, resourceType: CommentResourceType,
        sortType: CommentSortType, pageNo: Int, pageSize: Int, cursor: String): Resource<Comment> {
        assertEquals(CommentResourceType.SONG, resourceType)
        return PageRequest(session, id, sortType, pageNo, pageSize, cursor).let { pages += it; page(it) }
    }

    override suspend fun getFloorComment(session: SessionStamp, parentCommentId: Long, id: String,
        resourceType: CommentResourceType, limit: Int, time: Long): Resource<FloorComment> {
        assertEquals(CommentResourceType.SONG, resourceType)
        assertEquals(20, limit)
        return ReplyRequest(session, id, parentCommentId, time).let { replies += it; floor(it) }
    }
}

internal fun commentRows(ids: List<Long>) = ids.joinToString(",") {
    """{"commentId":$it,"content":"Comment $it","time":${it * 1000},"timeStr":"Today","user":{"userId":1,"nickname":"Reader","avatarUrl":""}}"""
}

internal fun commentPage(ids: List<Long>, more: Boolean = false, cursor: String? = null, total: Int = ids.size): Comment =
    Gson().fromJson("""{"code":200,"data":{"comments":[${commentRows(ids)}],"hasMore":$more,"cursor":${Gson().toJson(cursor)},"totalCount":$total}}""", Comment::class.java)

internal fun replyPage(ids: List<Long>, more: Boolean = false, time: Long? = null): FloorComment =
    Gson().fromJson("""{"code":200,"data":{"comments":[${commentRows(ids)}],"hasMore":$more,"time":$time}}""", FloorComment::class.java)
