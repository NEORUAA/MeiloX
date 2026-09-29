package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.AlbumCollectionResponse
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AlbumRepositoryTest {
    private val owner = SessionStamp(3, SessionIdentity(1, true, false))
    private inline fun <reified T> api(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    private fun repository(invoke: (String, Array<out Any?>) -> Any?) = PlaylistRepository(
        api<ApiService>(invoke), api<WeApiService> { _, _ -> error("Unexpected WEAPI") },
        api<EApiService> { _, _ -> error("Unexpected EAPI") },
        com.ljyh.mei.data.session.SessionStore(),
    )
    private fun collection(json: String) = Gson().fromJson(json, AlbumCollectionResponse::class.java)

    @Test fun readsAndValidatesTheTvCollectionContract() = runBlocking {
        for (collected in listOf(false, true)) {
            val source = repository { name, args ->
                assertEquals("getAlbumCollection", name)
                assertEquals(mapOf("request" to "{\"albumId\":\"10\"}"), args[0])
                assertEquals(owner, args[1])
                collection("""{"code":200,"data":{"id":10,"collect":$collected}}""")
            }
            assertEquals(Resource.Success(collected), source.getAlbumCollection("10", owner))
        }
    }

    @Test fun missingFlagWrongIdentityAndFailureAreNotAnUncollectedAlbum() = runBlocking {
        listOf(
            """{"code":200,"data":{"id":10}}""",
            """{"code":200,"data":{"id":20,"collect":true}}""",
            """{"code":200,"data":null}""",
            """{"code":301}""",
        ).forEach { json ->
            val result = repository { _, _ -> collection(json) }.getAlbumCollection("10", owner)
            assertTrue(result is Resource.Error)
        }
    }

    @Test fun guestReadsDoNotCallTheCollectionEndpointAndWritesAreRejected() = runBlocking {
        val guest = owner.copy(identity = SessionIdentity(0, false, true))
        val source = repository { _, _ -> error("Guest must not dispatch") }
        assertEquals(Resource.Success(false), source.getAlbumCollection("10", guest))
        assertTrue(source.setAlbumCollection("10", true, guest) is Resource.Error)
    }

    @Test fun mutationsCheckBusinessCodesInBothDirections() = runBlocking {
        for (collected in listOf(false, true)) {
            for (code in listOf(200, 301, 500)) {
                val source = repository { name, args ->
                    assertEquals(if (collected) "subscribeAlbum" else "unsubscribeAlbum", name)
                    assertEquals(owner, args[1])
                    BaseResponse(code)
                }
                assertEquals(code == 200, source.setAlbumCollection("10", collected, owner) is Resource.Success)
            }
        }
    }

    @Test fun cancellationRemainsCancellation() = runBlocking {
        val source = repository { _, _ -> throw CancellationException("Canceled") }
        assertTrue(runCatching { source.getAlbumCollection("10", owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { source.setAlbumCollection("10", true, owner) }.exceptionOrNull() is CancellationException)
    }
}
