package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.model.AlbumPhoto
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.api.GetAlbumList
import com.ljyh.mei.data.model.api.GetUserPlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionIdentity
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Tag

class UserRepositoryPagingTest {
    private val owner = SessionStamp(1, SessionIdentity(42, true, false))
    private val owners = mutableListOf<SessionStamp>()
    @Test fun photoRouteRequiresTheTriggeringSessionTag() {
        val method = ApiService::class.java.methods.single { it.name == "getUserPhotoAlbum" }
        assertTrue(method.parameterTypes.indices.any { index ->
            method.parameterTypes[index] == SessionStamp::class.java &&
                method.parameterAnnotations[index].any { it is Tag }
        })
    }

    private inline fun <reified T> api(noinline invoke: (String, Any?) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args ->
        owners += args.orEmpty().filterIsInstance<SessionStamp>()
        invoke(method.name, args?.firstOrNull())
    } as T

    private fun repository(invoke: (String, Any?) -> Any?) = UserRepository(
        api<ApiService>(invoke), api<EApiService> { _, _ -> error("Unexpected EAPI") },
        api<WeApiService> { _, _ -> error("Unexpected WEAPI") },
    )

    private fun playlists(ids: List<Long>, more: Boolean, code: Int = 200) = Gson().fromJson(
        """{"code":$code,"more":$more,"playlist":[${ids.joinToString { "{\"id\":$it}" }}]}""", UserPlaylist::class.java,
    )
    private fun albums(ids: List<Long>, more: Boolean) = Gson().fromJson(
        """{"code":200,"hasMore":$more,"data":[${ids.joinToString { "{\"id\":$it}" }}]}""", UserAlbumList::class.java,
    )

    @Test fun playlistsReadAllPagesAndDeduplicateWithoutRepeatingTheOffset() = runBlocking {
        val offsets = mutableListOf<String>()
        val repository = repository { name, body ->
            assertEquals("getUserPlaylist", name)
            val request = body as GetUserPlaylist
            assertEquals("42", request.uid)
            offsets += request.offset
            if (request.offset == "0") playlists(listOf(1, 2), true) else playlists(listOf(2, 3), false)
        }
        val result = repository.getAllUserPlaylists("42") as Resource.Success
        assertEquals(listOf(1L, 2L, 3L), result.data.playlist.map { it.id })
        assertEquals(listOf("0", "2"), offsets)
        assertEquals(false, result.data.more)
    }

    @Test fun stalledPlaylistPaginationFailsInsteadOfReplacingTheCacheWithAPartialList() = runBlocking {
        val repository = repository { _, _ -> playlists(listOf(1), true) }
        assertTrue(repository.getAllUserPlaylists("42") is Resource.Error)
    }

    @Test fun serverFailureOnALaterPlaylistPageIsNotSuccess() = runBlocking {
        val repository = repository { _, body ->
            if ((body as GetUserPlaylist).offset == "0") playlists(listOf(1), true)
            else playlists(emptyList(), false, 401)
        }
        assertTrue(repository.getAllUserPlaylists("42") is Resource.Error)
    }

    @Test fun albumCollectionsReadBeyondTheOriginalSinglePage() = runBlocking {
        val offsets = mutableListOf<String>()
        val repository = repository { name, body ->
            assertEquals("getCollectAlbumList", name)
            val request = body as GetAlbumList
            offsets += request.offset
            if (request.offset == "0") albums(listOf(1, 2), true) else albums(listOf(3), false)
        }
        val result = repository.getAlbumList(owner) as Resource.Success
        assertEquals(listOf(1L, 2L, 3L), result.data.data.map { it.id })
        assertEquals(listOf("0", "2"), offsets)
        assertEquals(listOf(owner, owner), owners)
    }

    @Test fun stalledAlbumPaginationFails() = runBlocking {
        val repository = repository { _, _ -> albums(emptyList(), true) }
        assertTrue(repository.getAlbumList(owner) is Resource.Error)
    }

    @Test fun requestCancellationPropagatesRatherThanBecomingAnErrorResource() = runBlocking {
        val repository = repository { _, _ -> throw CancellationException("Canceled") }
        val result = runCatching { repository.getAllUserPlaylists("42") }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun sessionValidationStopsBeforeFollowingAStalePaginationCursor() = runBlocking {
        var valid = true
        var requests = 0
        val repository = repository { _, _ ->
            requests++
            valid = false
            playlists(listOf(1), true)
        }
        val result = repository.getAllUserPlaylists("42") { check(valid) { "Session changed" } }
        assertTrue(result is Resource.Error)
        assertEquals(1, requests)
    }

    @Test fun rejectedPhotoResponsesDoNotExposeMissingDataToComposition() = runBlocking {
        val repository = repository { _, _ -> Gson().fromJson("{\"code\":403}", AlbumPhoto::class.java) }
        assertTrue(repository.getPhotoAlbum("42", owner) is Resource.Error)
        assertEquals(listOf(owner), owners)
    }

    @Test fun photoValidationRejectsAnOldResponseAndBlocksTheNextDispatch() = runBlocking {
        var valid = true
        var requests = 0
        val repository = repository { _, _ ->
            requests++
            valid = false
            Gson().fromJson("""{"code":200,"data":{"records":[]}}""", AlbumPhoto::class.java)
        }
        repeat(2) {
            assertTrue(repository.getPhotoAlbum("42", owner) { check(valid) { "Session changed" } } is Resource.Error)
        }
        assertEquals(1, requests)
        assertEquals(listOf(owner), owners)
    }

    @Test fun photoCancellationIsNotConvertedToAnErrorResource() = runBlocking {
        val repository = repository { _, _ -> throw CancellationException("Canceled photo") }
        assertTrue(runCatching { repository.getPhotoAlbum("42", owner) }.exceptionOrNull() is CancellationException)
    }
}
