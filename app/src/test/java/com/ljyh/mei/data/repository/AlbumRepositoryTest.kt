package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AlbumRepositoryTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private inline fun <reified T> api(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    private fun repository(invoke: (String, Array<out Any?>) -> Any?) = PlaylistRepository(
        api<ApiService>(invoke), api<WeApiService> { _, _ -> error("Unexpected WEAPI") },
        api<PlaylistCollectionBackend> { _, _ -> error("Unexpected playlist collection") },
        sessions, api<CatalogCollectionBackend>(invoke), api<PlaylistTracksBackend> { _, _ -> error("Unused tracks") },
    )

    @Test fun readsCurrentAccountStateThroughTheSelectedBackend() = runBlocking {
        for (collected in listOf(false, true)) {
            val source = repository { name, args ->
                assertEquals("albumCollected", name)
                assertEquals(10L, args[0])
                assertEquals(owner, args[1])
                collected
            }
            assertEquals(Resource.Success(collected), source.getAlbumCollection("10", owner))
        }
    }

    @Test fun backendFailuresAreNotAnUncollectedAlbum() = runBlocking {
        assertTrue(repository { _, _ -> throw IOException("Offline") }.getAlbumCollection("10", owner) is Resource.Error)
        assertTrue(repository { _, _ -> error("Missing collection flag") }.getAlbumCollection("10", owner) is Resource.Error)
    }

    @Test fun guestReadsDoNotCallTheCollectionEndpointAndWritesAreRejected() = runBlocking {
        identity = SessionIdentity(0, false, true)
        val guest = sessions.snapshot()
        val source = repository { _, _ -> error("Guest must not dispatch") }
        assertEquals(Resource.Success(false), source.getAlbumCollection("10", guest))
        assertTrue(source.setAlbumCollection("10", true, guest) is Resource.Error)
    }

    @Test fun invalidIdsAndStaleOwnersNeverDispatchCollectionReads() = runBlocking {
        val source = repository { _, _ -> error("Must not dispatch") }
        for (id in listOf("bad", "0", "-1")) assertTrue(source.getAlbumCollection(id, owner) is Resource.Error)
        sessions.invalidate()
        assertTrue(source.getAlbumCollection("10", owner) is Resource.Error)
    }

    @Test fun accountChangeAfterCollectionResponseRejectsTheOldResult() = runBlocking {
        val source = repository { _, _ -> sessions.invalidate(); true }
        assertTrue(source.getAlbumCollection("10", owner) is Resource.Error)
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
