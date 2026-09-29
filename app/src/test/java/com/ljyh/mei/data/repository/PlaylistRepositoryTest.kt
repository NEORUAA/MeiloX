package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistRepositoryTest {
    private val owner = HostSessionStamp(1, HostSessionIdentity(1, true, false))
    private inline fun <reified T> api(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    private fun repository(invoke: (String, Array<out Any?>) -> Any?) = PlaylistRepository(
        api<ApiService>(invoke), api<WeApiService> { _, _ -> error("Unexpected WEAPI") }, api<EApiService>(invoke),
    )
    private fun detail(id: Int = 10, code: Int = 200): PlaylistDetail = Gson().fromJson(
        """{"code":$code,"playlist":{"id":$id,"tracks":[],"trackIds":[{"id":1},{"id":2}]}}""", PlaylistDetail::class.java)
    private fun track(id: Int): PlaylistDetail.Playlist.Track = Gson().fromJson(
        """{"id":$id,"name":"Track","al":{"id":1,"name":"Album","picUrl":""},"ar":[],"dt":1000}""",
        PlaylistDetail.Playlist.Track::class.java)

    @Test fun detailsValidateBusinessCodeAndRequestedIdentity() = runBlocking {
        listOf(detail(10), detail(20), detail(10, 500)).forEach { response ->
            val source = repository { name, args ->
                assertEquals("getPlaylistDetail", name)
                assertEquals(owner, args[1])
                response
            }
            assertEquals(response.code == 200 && response.playlist.Id == 10L, source.getPlaylistDetail("10", owner) is Resource.Success)
        }
    }

    @Test fun songResponsesAreOrderedAndScopedWithoutAcceptingUnexpectedIds() = runBlocking {
        val source = repository { name, args ->
            assertEquals("getSongDetail", name)
            assertEquals(owner, args[1])
            Tracks(200, emptyList(), listOf(track(2), track(9), track(1), track(1)))
        }
        assertEquals(listOf(1L, 2L), source.getPlaylistTrackDetails(listOf("1", "2", "1"), owner).map { it.id })
    }

    @Test fun incompleteOrRejectedBulkReadsNeverReturnPartialSuccess() = runBlocking {
        listOf(Tracks(500, emptyList(), emptyList()), Tracks(200, emptyList(), listOf(track(1)))).forEach { response ->
            val source = repository { _, args -> assertEquals(owner, args[1]); response }
            assertTrue(runCatching { source.getCompletePlaylistTracks(detail(), owner) }.isFailure)
        }
    }

    @Test fun collectionRequestsDoNotSupplyModuleTokensAndCheckBusinessCodes() = runBlocking {
        for (subscribe in listOf(true, false)) for (code in listOf(200, 301, 506)) {
            val source = repository { name, args ->
                assertEquals(if (subscribe) "subscribePlaylist" else "unSubscribePlaylist", name)
                assertEquals(EApiSubscribePlaylist(10), args[0])
                assertEquals(owner, args[1])
                BaseResponse(code)
            }
            val result = if (subscribe) source.subscribePlaylist("10", owner) else source.unSubscribePlaylist("10", owner)
            assertEquals(code == 200, result is Resource.Success)
        }
    }

    @Test fun cancellationDoesNotBecomeAnEmptyTrackListOrBusinessError() = runBlocking {
        val source = repository { _, _ -> throw CancellationException() }
        assertTrue(runCatching { source.getPlaylistDetail("10", owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { source.getPlaylistTrackDetails(listOf("1"), owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { source.subscribePlaylist("10", owner) }.exceptionOrNull() is CancellationException)
    }

    @Test fun creationRequiresBusinessAcceptanceAndAnUnambiguousPlaylistId() = runBlocking {
        val fixtures = listOf(
            """{"code":200,"playlist":{"id":30}}""" to true,
            """{"code":200,"id":30}""" to true,
            """{"code":200,"playlist":{"id":30},"id":31}""" to false,
            """{"code":200}""" to false,
            """{"code":507,"message":"Limit"}""" to false,
        )
        fixtures.forEach { (json, accepted) ->
            val source = repository { name, args ->
                assertEquals("createPlaylist", name)
                assertEquals(owner, args[1])
                val body = args[0] as com.ljyh.mei.data.model.api.CreatePlaylist
                assertEquals("10", body.privacy)
                assertEquals("NORMAL", body.type)
                Gson().fromJson(json, com.ljyh.mei.data.model.api.CreatePlaylistResult::class.java)
            }
            assertEquals(accepted, source.createPlaylist("Test", true, "NORMAL", owner) is Resource.Success)
        }
    }

    @Test fun playlistMutationsKeepOwnerAndValidateBeforeDispatch() = runBlocking {
        var dispatched = 0
        val source = repository { name, args ->
            dispatched++
            assertEquals(owner, args[1])
            if (name == "manipulateTracks") {
                val body = args[0] as com.ljyh.mei.data.model.api.ManipulateTrack
                assertEquals("[\"1\",\"2\"]", body.trackIds)
                assertEquals(true, body.reverse)
                com.ljyh.mei.data.model.api.ManipulateTrackResult(502)
            } else com.ljyh.mei.data.model.api.BaseMessageResponse(500, "Rejected", "Rejected", "")
        }
        assertEquals(502, (source.manipulateTrack("add", "10", "1, 2,1", owner) as Resource.Success).data.code)
        assertTrue(source.deletePlaylist("10", owner) is Resource.Error)
        assertTrue(source.manipulateTrack("add", "10", "1,,2", owner) is Resource.Error)
        assertTrue(source.manipulateTrack("other", "10", "1", owner) is Resource.Error)
        val guest = owner.copy(identity = HostSessionIdentity(0, false, true))
        assertTrue(source.manipulateTrack("add", "10", "1", guest) is Resource.Error)
        assertTrue(source.createPlaylist("Test", true, "NORMAL", guest) is Resource.Error)
        assertTrue(source.deletePlaylist("10", guest) is Resource.Error)
        assertEquals(2, dispatched)
    }

    @Test fun mutationCancellationPropagates() = runBlocking {
        val source = repository { _, _ -> throw CancellationException() }
        assertTrue(runCatching { source.manipulateTrack("add", "10", "1", owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { source.createPlaylist("Test", true, "NORMAL", owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { source.deletePlaylist("10", owner) }.exceptionOrNull() is CancellationException)
    }
}
