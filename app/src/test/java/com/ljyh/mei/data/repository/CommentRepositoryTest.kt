package com.ljyh.mei.data.repository

import com.ljyh.mei.source.netease.repository.*

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.ui.screen.comment.CommentComposerState
import com.ljyh.mei.ui.screen.comment.CommentLikeState
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CommentRepositoryTest {
    private val expectedSession = "a".repeat(64)
    private val requests = mutableListOf<Request>()
    private var responseBody = """{"code":200}"""
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://example.test/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("synthetic")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }.build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val direct = retrofit.create(MeloXDirectService::class.java)
    private val repository = CommentRepository(
        retrofit.create(ApiService::class.java),
        retrofit.create(WeApiService::class.java),
        direct,
        direct,
    )

    @Test
    fun publishingAndReplyingPreserveResourceAndExactReplyTarget() = runBlocking {
        assertTrue(repository.publishComment("123", "  A new comment\nSecond line  ", expectedSession) is Resource.Success)
        assertTrue(repository.publishComment("123", "Reply", expectedSession, 9_876_543_210L) is Resource.Success)

        assertEquals(listOf("/eapi/resource/comments/add", "/eapi/resource/comments/reply"), requests.map { it.url.encodedPath })
        requests.forEach { assertEquals(expectedSession, it.header(NETEASE_EXPECTED_SESSION_HEADER)) }
        val publish = body(0)
        assertEquals("R_SO_4_123", publish.get("threadId").asString)
        assertEquals("A new comment\nSecond line", publish.get("content").asString)
        assertFalse(publish.has("commentId"))
        assertEquals(9_876_543_210L, body(1).get("commentId").asLong)
    }

    @Test
    fun likeAndUnlikeUseSeparateExplicitActions() = runBlocking {
        repository.setCommentLiked("123", 45L, true, expectedSession, CommentResourceType.ALBUM)
        repository.setCommentLiked("123", 45L, false, expectedSession, CommentResourceType.ALBUM)

        assertEquals(listOf("/weapi/v1/comment/like", "/weapi/v1/comment/unlike"), requests.map { it.url.encodedPath })
        requests.forEach { assertEquals(expectedSession, it.header(NETEASE_EXPECTED_SESSION_HEADER)) }
        assertEquals("R_AL_3_123", body(0).get("threadId").asString)
        assertEquals(45L, body(1).get("commentId").asLong)
    }

    @Test
    fun rejectedOrMalformedSuccessDoesNotRetryMutation() = runBlocking {
        responseBody = """{"code":301,"message":"Please sign in"}"""
        val rejected = repository.publishComment("123", "Keep this draft", expectedSession)
        assertEquals(Resource.Error("Please sign in"), rejected)
        assertEquals(1, requests.size)

        responseBody = """{"message":"Missing result"}"""
        assertTrue(repository.setCommentLiked("123", 45, true, expectedSession) is Resource.Error)
        assertEquals(2, requests.size)
    }

    @Test
    fun blankCommentsAndInvalidTargetsNeverReachTransport() = runBlocking {
        assertTrue(repository.publishComment("123", " \n ", expectedSession) is Resource.Error)
        assertTrue(repository.publishComment("123", "Reply", expectedSession, 0) is Resource.Error)
        assertTrue(repository.setCommentLiked("", 45, true, expectedSession) is Resource.Error)
        assertTrue(repository.publishComment("123", "Comment", "") is Resource.Error)
        assertTrue(repository.setCommentLiked("123", 45, true, "") is Resource.Error)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun rejectedReadDoesNotBecomeAnEmptyPage() = runBlocking {
        responseBody = """{"code":301,"message":"Please sign in"}"""
        assertTrue(repository.getComment("123") is Resource.Error)
        assertTrue(repository.getFloorComment(45, "123") is Resource.Error)
    }

    @Test
    fun incompleteSuccessfulReadsRemainRetryableErrors() = runBlocking {
        listOf(
            """{"code":200}""",
            """{"code":200,"data":null}""",
            """{"code":200,"data":{"hasMore":false}}""",
            """{"code":200,"data":{"comments":[]}}""",
        ).forEach { body ->
            responseBody = body
            assertTrue(repository.getComment("123") is Resource.Error)
            assertTrue(repository.getFloorComment(45, "123") is Resource.Error)
        }
    }

    @Test
    fun validEmptyReadsAndServerErrorsKeepTheirMeaning() = runBlocking {
        responseBody = """{"code":200,"data":{"comments":[],"hasMore":false}}"""
        assertTrue(repository.getComment("123") is Resource.Success)
        assertTrue(repository.getFloorComment(45, "123") is Resource.Success)

        responseBody = """{"code":200,"data":{"comments":[],"hasMore":true}}"""
        val continued = repository.getComment("123") as Resource.Success
        assertEquals(true, continued.data.data?.hasMore)

        responseBody = """{"code":403,"message":"Comments are unavailable"}"""
        assertEquals(Resource.Error("Comments are unavailable"), repository.getComment("123"))
        assertEquals(Resource.Error("Comments are unavailable"), repository.getFloorComment(45, "123"))
    }

    @Test
    fun composerAndAcceptedLikeStatesPreventInvalidActions() {
        assertFalse(CommentComposerState(draft = " \n ").canSend)
        assertFalse(CommentComposerState(draft = "Comment", isSending = true).canSend)
        assertTrue(CommentComposerState(draft = "Comment").canSend)
        assertEquals(CommentLikeState(true, 11), CommentLikeState(false, 10).toggled())
        assertEquals(CommentLikeState(false, 10), CommentLikeState(false, 10).toggled().toggled())
        assertEquals(CommentLikeState(false, 0), CommentLikeState(true, 0).toggled())
    }

    private fun body(index: Int) = JsonParser.parseString(
        Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8(),
    ).asJsonObject
}
