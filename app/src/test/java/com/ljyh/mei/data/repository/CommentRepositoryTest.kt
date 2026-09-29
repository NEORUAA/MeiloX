package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.CommentResourceType
import com.ljyh.mei.data.model.api.CommentSortType
import com.ljyh.mei.data.model.api.GetComment
import com.ljyh.mei.data.model.api.GetFloorComment
import com.ljyh.mei.data.model.weapi.Comment
import com.ljyh.mei.data.model.weapi.FloorComment
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CommentRepositoryTest {
    private val owner = SessionStamp(3, SessionIdentity(1, true, false))
    private inline fun <reified T> api(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    private fun repository(invoke: (String, Array<out Any?>) -> Any?) = CommentRepository(api<ApiService>(invoke), api<WeApiService>(invoke))
    private suspend fun CommentSource.comments() = getComment(owner, "10", CommentResourceType.SONG, CommentSortType.TIME, 2, 20, "1000")
    private suspend fun CommentSource.replies() = getFloorComment(owner, 99, "10", CommentResourceType.SONG, 20, 1000)
    private val valid = """{"code":200,"data":{"comments":[],"hasMore":false}}"""

    @Test fun bothOperationsKeepTheExpectedSessionTagAndExactBody() = runBlocking {
        val names = mutableListOf<String>()
        val source = repository { name, args ->
            names += name
            assertEquals(owner, args[1])
            when (name) {
                "getComment" -> {
                    assertEquals(GetComment("R_SO_4_10", 2, 20, 3, "1000", true), args[0])
                    Gson().fromJson(valid, Comment::class.java)
                }
                "getFloorComment" -> {
                    assertEquals(GetFloorComment(99, "R_SO_4_10", 20, 1000), args[0])
                    Gson().fromJson(valid, FloorComment::class.java)
                }
                else -> error("Unexpected request")
            }
        }
        assertTrue(source.comments() is Resource.Success)
        assertTrue(source.replies() is Resource.Success)
        assertEquals(listOf("getComment", "getFloorComment"), names)
    }

    @Test fun missingRowsContinuationAndBusinessFailureAreErrorsForBothOperations() = runBlocking {
        listOf("""{"code":301}""", """{"code":200,"data":null}""",
            """{"code":200,"data":{"comments":[],"hasMore":null}}""",
            """{"code":200,"data":{"comments":null,"hasMore":false}}""").forEach { json ->
            val source = repository { name, _ -> if (name == "getComment") Gson().fromJson(json, Comment::class.java)
                else Gson().fromJson(json, FloorComment::class.java) }
            assertTrue(source.comments() is Resource.Error)
            assertTrue(source.replies() is Resource.Error)
        }
    }

    @Test fun invalidInputDoesNotDispatch() = runBlocking {
        val source = repository { _, _ -> error("Must not dispatch") }
        assertTrue(source.getComment(owner, "", CommentResourceType.SONG, CommentSortType.TIME, 1, 20, "0") is Resource.Error)
        assertTrue(source.getComment(owner, "10", CommentResourceType.SONG, CommentSortType.TIME, 0, 20, "0") is Resource.Error)
        assertTrue(source.getFloorComment(owner, 0, "10", CommentResourceType.SONG, 20, -1) is Resource.Error)
    }

    @Test fun cancellationPropagatesForBothOperations() = runBlocking {
        val source = repository { _, _ -> throw CancellationException("Canceled") }
        assertTrue(runCatching { source.comments() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { source.replies() }.exceptionOrNull() is CancellationException)
    }
}
