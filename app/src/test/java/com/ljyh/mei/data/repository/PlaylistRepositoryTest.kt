package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistRepositoryTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
    private val owner = sessions.snapshot()
    private inline fun <reified T> api(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
    private fun repository(invoke: (String, Array<out Any?>) -> Any?) = PlaylistRepository(
        api<ApiService>(invoke), api<WeApiService> { _, _ -> error("Unexpected WEAPI") }, api<PlaylistCollectionBackend>(invoke),
        sessions, api<CatalogCollectionBackend> { _, _ -> error("Unused catalog") }, api<PlaylistTracksBackend>(invoke),
        com.ljyh.mei.playback.DownloadSourceBackend { _, _, _ -> error("Unused downloads") },
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

    @Test fun collectionActionsDelegateToTheBackendAndCheckBusinessCodes() = runBlocking {
        for (subscribe in listOf(true, false)) for (code in listOf(200, 301, 506)) {
            val source = repository { name, args ->
                assertEquals("setCollected", name)
                assertEquals(10L, args[0])
                assertEquals(subscribe, args[1])
                assertEquals(owner, args[2])
                BaseResponse(code)
            }
            val result = if (subscribe) source.subscribePlaylist("10", owner) else source.unSubscribePlaylist("10", owner)
            assertEquals(code == 200, result is Resource.Success)
        }
    }

    @Test fun invalidGuestAndStaleCollectionActionsDoNotReachEitherBackend() = runBlocking {
        val source = repository { _, _ -> error("Must not dispatch") }
        for (id in listOf("bad", "0", "-1")) {
            assertTrue(source.subscribePlaylist(id, owner) is Resource.Error)
            assertTrue(source.unSubscribePlaylist(id, owner) is Resource.Error)
        }
        val guests = listOf(SessionIdentity(0, false, true), SessionIdentity(1, true, true))
        for (identity in guests) {
            val guestSessions = SessionStore().apply { bind { identity } }
            val guestSource = PlaylistRepository(api { _, _ -> error("Unused API") },
                api { _, _ -> error("Unused WEAPI") }, api { _, _ -> error("Must not dispatch") }, guestSessions,
                api { _, _ -> error("Unused catalog") }, api { _, _ -> error("Unused tracks") }, api { _, _ -> error("Unused downloads") })
            assertTrue(guestSource.subscribePlaylist("10", guestSessions.snapshot()) is Resource.Error)
            assertTrue(guestSource.unSubscribePlaylist("10", guestSessions.snapshot()) is Resource.Error)
        }
        sessions.invalidate()
        assertTrue(source.subscribePlaylist("10", owner) is Resource.Error)
        assertTrue(source.unSubscribePlaylist("10", owner) is Resource.Error)
    }

    @Test fun lateSuccessfulCollectionResponsesCannotCrossSessionGenerations() = runBlocking {
        for (collected in listOf(true, false)) {
            val stamp = sessions.snapshot()
            val source = repository { _, _ -> sessions.invalidate(); BaseResponse(200) }
            val result = if (collected) source.subscribePlaylist("10", stamp) else source.unSubscribePlaylist("10", stamp)
            assertTrue(result is Resource.Error)
        }
    }

    @Test fun omittedCollectionOwnerIsCapturedAndAnUnreadySessionIsAnError() = runBlocking {
        val source = repository { _, args -> assertEquals(owner, args[2]); BaseResponse(200) }
        assertTrue(source.subscribePlaylist("10") is Resource.Success)
        assertTrue(source.unSubscribePlaylist("10") is Resource.Success)
        val unready = PlaylistRepository(api { _, _ -> error("Unused API") },
            api { _, _ -> error("Unused WEAPI") }, api { _, _ -> error("Must not dispatch") }, SessionStore(),
            api { _, _ -> error("Unused catalog") }, api { _, _ -> error("Unused tracks") }, api { _, _ -> error("Unused downloads") })
        assertTrue(unready.subscribePlaylist("10") is Resource.Error)
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
            if (name == "modify") {
                assertEquals("add", args[0])
                assertEquals(10L, args[1])
                assertEquals(listOf(1L, 2L), args[2])
                assertEquals(owner, args[3])
                com.ljyh.mei.data.model.api.ManipulateTrackResult(502)
            } else {
                assertEquals(owner, args[1])
                com.ljyh.mei.data.model.api.BaseMessageResponse(500, "Rejected", "Rejected", "")
            }
        }
        assertEquals(502, (source.manipulateTrack("add", "10", "1, 2,1", owner) as Resource.Success).data.code)
        assertTrue(source.deletePlaylist("10", owner) is Resource.Error)
        assertTrue(source.manipulateTrack("add", "10", "1,,2", owner) is Resource.Error)
        assertTrue(source.manipulateTrack("other", "10", "1", owner) is Resource.Error)
        val guest = owner.copy(identity = SessionIdentity(0, false, true))
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

    @Test fun trackMutationsRejectInvalidPlaylistsTracksAndStaleOwners() = runBlocking {
        val source = repository { _, _ -> error("Must not dispatch") }
        for (pid in listOf("bad", "0", "-1")) assertTrue(source.manipulateTrack("del", pid, "1", owner) is Resource.Error)
        for (ids in listOf("", "1,", "0", "-1", "bad", "1,,2")) assertTrue(source.manipulateTrack("add", "10", ids, owner) is Resource.Error)
        sessions.invalidate()
        assertTrue(source.manipulateTrack("add", "10", "1", owner) is Resource.Error)
        assertTrue(source.manipulateTrack("del", "10", "1", owner) is Resource.Error)
    }

    @Test fun trackMutationResultsRemainBusinessOutcomesAndCannotCrossAccounts() = runBlocking {
        for (code in listOf(200, 502, 500)) {
            val source = repository { name, args ->
                assertEquals("modify", name)
                assertEquals("del", args[0])
                assertEquals(owner, args[3])
                com.ljyh.mei.data.model.api.ManipulateTrackResult(code)
            }
            assertEquals(code, (source.manipulateTrack("del", "10", "1", owner) as Resource.Success).data.code)
        }
        val stale = repository { _, _ -> sessions.invalidate(); com.ljyh.mei.data.model.api.ManipulateTrackResult(200) }
        assertTrue(stale.manipulateTrack("add", "10", "1", owner) is Resource.Error)
    }

    @Test fun dailyUsesOfficialParametersAndCapturedOwnerAndAcceptsEmptyRecommendations() = runBlocking {
        var calls = 0
        val source = PlaylistRepository(api { _, _ -> error("Unused API") }, api<WeApiService> { name, args ->
            calls++
            assertEquals("getEveryDayRecommendSongs", name)
            assertEquals(mapOf("ispush" to "false", "limit" to "30", "trialMode" to "1"), args[0])
            assertEquals(owner, args[1])
            Gson().fromJson("""{"code":200,"data":{"dailySongs":[]}}""", com.ljyh.mei.data.model.weapi.EveryDaySongs::class.java)
        }, api { _, _ -> error("Unused EAPI") }, com.ljyh.mei.data.session.SessionStore(), api { _, _ -> error("Unused catalog") }, api { _, _ -> error("Unused tracks") }, api { _, _ -> error("Unused downloads") })
        assertTrue(source.getEveryDayRecommendSongs(owner) is Resource.Success)
        assertTrue(source.getEveryDayRecommendSongs(owner.copy(identity = SessionIdentity(0, false, true))) is Resource.Error)
        assertEquals(1, calls)
    }

    @Test fun dailyRejectsBusinessErrorsMissingListsAndMalformedSongs() = runBlocking {
        listOf("""{"code":301}""", """{"code":200,"data":{}}""",
            """{"code":200,"data":{"dailySongs":[{"id":0}]}}""").forEach { json ->
            val source = PlaylistRepository(api { _, _ -> error("Unused API") }, api<WeApiService> { _, _ ->
                Gson().fromJson(json, com.ljyh.mei.data.model.weapi.EveryDaySongs::class.java)
            }, api { _, _ -> error("Unused EAPI") }, com.ljyh.mei.data.session.SessionStore(), api { _, _ -> error("Unused catalog") }, api { _, _ -> error("Unused tracks") }, api { _, _ -> error("Unused downloads") })
            assertTrue(source.getEveryDayRecommendSongs(owner) is Resource.Error)
        }
    }

    @Test fun dailyCancellationPropagates() = runBlocking {
        val source = PlaylistRepository(api { _, _ -> error("Unused API") },
            api<WeApiService> { _, _ -> throw CancellationException() }, api { _, _ -> error("Unused EAPI") },
            com.ljyh.mei.data.session.SessionStore(), api { _, _ -> error("Unused catalog") }, api { _, _ -> error("Unused tracks") }, api { _, _ -> error("Unused downloads") })
        assertTrue(runCatching { source.getEveryDayRecommendSongs(owner) }.exceptionOrNull() is CancellationException)
    }
}
