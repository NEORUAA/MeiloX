package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionIdentity
import com.google.gson.Gson
import com.ljyh.mei.data.model.api.GetUserPhotoAlbum
import com.ljyh.mei.data.model.api.GetUserPlaylist
import com.ljyh.mei.data.model.api.GetSearch
import com.ljyh.mei.data.model.api.GetSearchSuggest
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.repository.submitPlaybackHistoryLog
import com.ljyh.mei.data.repository.diagnosticSummary
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HostCallFactoryTest {
    @Test fun commentsAndRepliesUseHostTransportWithExplicitSessionOwnership() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val retrofit = retrofit(HostCallFactory(bridge))
        val api = retrofit.create(ApiService::class.java)
        val weapi = retrofit.create(com.ljyh.mei.data.network.api.WeApiService::class.java)
        val owner = bridge.sessions.snapshot()
        api.getComment(com.ljyh.mei.data.model.api.GetComment("R_SO_4_10", 2, 20, 3, "1000"), owner)
        assertEquals("v2/resource/comments", backend.path)
        assertEquals(mapOf("threadId" to "R_SO_4_10", "pageNo" to "2", "pageSize" to "20",
            "sortType" to "3", "cursor" to "1000", "showInner" to "true"), backend.parameters)
        weapi.getFloorComment(com.ljyh.mei.data.model.api.GetFloorComment(99, "R_SO_4_10", 20, 1000), owner)
        assertEquals("resource/comment/floor/get", backend.path)
        assertEquals(mapOf("parentCommentId" to "99", "threadId" to "R_SO_4_10", "limit" to "20", "time" to "1000"), backend.parameters)
        assertEquals(2, backend.executions.get())
    }

    @Test fun obsoleteCommentSessionCannotDispatchEitherEndpoint() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val retrofit = retrofit(HostCallFactory(bridge))
        val api = retrofit.create(ApiService::class.java)
        val weapi = retrofit.create(com.ljyh.mei.data.network.api.WeApiService::class.java)
        val owner = bridge.sessions.snapshot()
        bridge.sessions.invalidate()
        assertTrue(runCatching { api.getComment(com.ljyh.mei.data.model.api.GetComment("R_SO_4_10"), owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { weapi.getFloorComment(com.ljyh.mei.data.model.api.GetFloorComment(99, "R_SO_4_10"), owner) }.exceptionOrNull() is SessionChangedException)
        assertEquals(0, backend.executions.get())
    }

    @Test fun convertsTypedJsonWithoutLosingNestedValuesOrIntegerPrecision() {
        val backend = Backend()
        val call = factory(backend).newCall(post("""{"id":9223372036854775806,"flag":true,"text":"a+b&c","ids":[1,2],"object":{"x":1},"nil":null}"""))
        call.execute().use {
            assertEquals("official-json", it.header("X-MeiloX-Transport"))
            assertNotNull(it.request.tag(SessionStamp::class.java))
            assertEquals("{\"code\":200}", it.body!!.string())
        }
        assertEquals("search/get", backend.path)
        assertEquals(mapOf("id" to "9223372036854775806", "flag" to "true", "text" to "a+b&c",
            "ids" to "[1,2]", "object" to "{\"x\":1}", "nil" to "null"), backend.parameters)
        assertThrows(IllegalStateException::class.java) { call.execute() }
    }

    @Test fun convertsGetQueriesAndAcceptsEmptyPostBodies() {
        val backend = Backend()
        val factory = factory(backend)
        factory.newCall(Request.Builder().url("https://interface.music.163.com/api/music/audio/match?rawdata=a%2Bb%2Fc%3D&duration=5").build()).execute().close()
        assertEquals(mapOf("rawdata" to "a+b/c=", "duration" to "5"), backend.parameters)
        factory.newCall(Request.Builder().url("https://music.163.com/api/nuser/account/get").post(ByteArray(0).toRequestBody()).build()).execute().close()
        assertTrue(backend.parameters.isEmpty())
        listOf("weapi", "eapi").forEach { prefix ->
            factory.newCall(Request.Builder().url("https://music.163.com/$prefix/subcount").build()).execute().close()
            assertEquals("subcount", backend.path)
        }
    }

    @Test fun maintainsCallTagsAndListenerLifetimeWithoutInventingNetworkEvents() {
        val call = factory(Backend()).newCall(post().newBuilder().tag(String::class.java, "seed").build())
        assertEquals("seed", call.tag(String::class))
        assertEquals(42, call.tag(Int::class) { 42 })
        assertEquals(42, call.tag(Int::class.java))
        val copy = call.clone()
        assertNull(copy.tag(Int::class))
        assertEquals("seed", copy.tag(String::class))
        val events = mutableListOf<String>()
        call.addEventListener(object : EventListener() {
            override fun callStart(call: Call) { events += "start" }
            override fun callEnd(call: Call) { events += "end" }
        })
        call.execute().use { it.body.string() }
        copy.execute().close()
        assertEquals(listOf("start", "end"), events)
    }

    @Test fun callbackExceptionsDoNotTriggerAnotherCallback() {
        val failures = AtomicInteger()
        val call = factory(Backend()).newCall(post())
        assertThrows(RejectedExecutionException::class.java) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failures.incrementAndGet() }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    throw RejectedExecutionException("callback")
                }
            })
        }
        assertEquals(0, failures.get())
    }

    @Test fun rejectsUnknownOriginsCredentialsUploadsAndAmbiguousParameters() {
        val factory = factory(Backend())
        listOf("https://example.org/api/test", "http://music.163.com/api/test", "https://music.163.com:8443/api/test",
            "https://user@music.163.com/api/test", "https://music.163.com/api/test#part",
            "https://music.163.com/unknown/test", "https://music.163.com/api/a%2Fb", "https://music.163.com/api/test?a=1&a=2").forEach {
            assertThrows(IllegalArgumentException::class.java) { factory.newCall(Request.Builder().url(it).build()) }
        }
        listOf("Cookie", "Authorization").forEach {
            assertThrows(IllegalArgumentException::class.java) { factory.newCall(post().newBuilder().header(it, "test").build()) }
        }
        assertThrows(IllegalArgumentException::class.java) { factory.newCall(post("[]")) }
        assertThrows(IllegalArgumentException::class.java) { factory.newCall(post("{\"header\":{}}")) }
        assertThrows(IllegalArgumentException::class.java) {
            factory.newCall(post("{\"a\":1}").newBuilder().url("https://music.163.com/api/test?a=2").build())
        }
        assertThrows(IllegalArgumentException::class.java) {
            factory.newCall(post().newBuilder().post("file".toRequestBody("application/octet-stream".toMediaType())).build())
        }
    }

    @Test fun cancellationBeforeDispatchAndClonesPreserveOwnership() {
        val backend = Backend()
        val bridge = bridge(backend)
        val factory = HostCallFactory(bridge, Executor { it.run() })
        val original = factory.newCall(post())
        val copy = original.clone()
        original.cancel()
        assertThrows(IOException::class.java) { original.execute() }
        assertEquals(0, backend.executions.get())
        bridge.sessions.invalidate()
        assertThrows(SessionChangedException::class.java) { copy.execute() }
        factory.newCall(post()).execute().close()
        assertEquals(1, backend.executions.get())
    }

    @Test fun doesNotReadAResponseFromAnOldSession() {
        val backend = Backend()
        val bridge = bridge(backend)
        val response = HostCallFactory(bridge, Executor { it.run() }).newCall(post()).execute()
        bridge.sessions.invalidate()
        response.use { assertThrows(SessionChangedException::class.java) { it.body!!.string() } }
    }

    @Test fun expectedSessionTagsRejectAnActionBeforeItsCallIsCreated() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(MeloXDirectService::class.java)
        val owner = bridge.sessions.snapshot()
        bridge.sessions.invalidate()
        val failure = runCatching {
            service.post("/api/djradio/sub", mapOf("id" to 1), expectedSession = owner)
        }.exceptionOrNull()
        assertTrue(failure is SessionChangedException)
        assertEquals(0, backend.executions.get())
    }

    @Test fun expectedSessionTagsAreTransportMetadataNotBusinessParameters() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(MeloXDirectService::class.java)
        val owner = bridge.sessions.snapshot()
        service.post("/api/djradio/sub", mapOf("id" to 1), expectedSession = owner)
        assertEquals("djradio/sub", backend.path)
        assertEquals(mapOf("id" to "1"), backend.parameters)
        assertEquals(1, backend.executions.get())
    }

    @Test fun searchAndSuggestionsCarryTheirOwnerWithoutSerializingIt() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        service.search(GetSearch("music", type = 10, offset = 30), owner)
        assertEquals("search/get", backend.path)
        assertEquals(mapOf("s" to "music", "type" to "10", "limit" to "30", "offset" to "30"), backend.parameters)
        service.searchSuggest(GetSearchSuggest("music"), owner)
        assertEquals("search/suggest/web", backend.path)
        assertEquals(mapOf("s" to "music", "type" to "mobile"), backend.parameters)
    }

    @Test fun obsoleteSearchOwnerCannotCreateEitherRequest() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        bridge.sessions.invalidate()
        assertTrue(runCatching { service.search(GetSearch("music"), owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { service.searchSuggest(GetSearchSuggest("music"), owner) }.exceptionOrNull() is SessionChangedException)
        assertEquals(0, backend.executions.get())
    }

    @Test fun albumReadCollectionWritesAndUrlsKeepTheirOwnerOutOfBusinessParameters() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        service.getAlbumDetail(id = "10", expectedSession = owner)
        assertEquals("v1/album/10", backend.path)
        assertTrue(backend.parameters.isEmpty())
        retrofit(HostCallFactory(bridge)).create(HostCatalogCollectionApi::class.java)
            .album(mapOf("request" to "{\"albumId\":\"10\"}"), owner)
        assertEquals("tv-artist-page/album/get", backend.path)
        assertEquals(mapOf("request" to "{\"albumId\":\"10\"}"), backend.parameters)
        service.subscribeAlbum(com.ljyh.mei.data.model.api.SubscribePlaylist("10"), owner)
        assertEquals("album/sub", backend.path)
        assertEquals(mapOf("id" to "10"), backend.parameters)
        service.unsubscribeAlbum(com.ljyh.mei.data.model.api.SubscribePlaylist("10"), owner)
        assertEquals("album/unsub", backend.path)
        assertEquals(mapOf("id" to "10"), backend.parameters)
        service.getSongUrlV1(com.ljyh.mei.data.model.api.GetSongUrlV1("[1]", "standard"), owner)
        assertEquals("song/enhance/player/url/v1", backend.path)
        assertEquals(mapOf("ids" to "[1]", "level" to "standard", "encodeType" to "flac"), backend.parameters)
        service.getCollectAlbumList(com.ljyh.mei.data.model.api.GetAlbumList(), owner)
        assertEquals("album/sublist", backend.path)
        assertFalse(backend.parameters.containsKey("expectedSession"))
    }

    @Test fun artistRoutesPreserveSupplementalCatalogParametersAndOfficialCollectionContracts() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        val reads: List<suspend () -> Unit> = listOf(
            { service.getArtistDetail(com.ljyh.mei.data.model.api.GetArtistDetail("10"), owner) },
            { service.getArtistAlbums(com.ljyh.mei.data.model.api.GetArtistAlbum(), "10", owner) },
            { service.getArtistSongs(com.ljyh.mei.data.model.api.GetArtistSong(), "10", owner) },
            { service.getAllArtistSongs(com.ljyh.mei.data.model.api.GetAllArtistSongs("10", 100), owner) },
            { retrofit(HostCallFactory(bridge)).create(HostCatalogCollectionApi::class.java).artist(mapOf("artistId" to "10"), owner) },
            { retrofit(HostCallFactory(bridge)).create(HostCatalogCollectionApi::class.java).subscribeArtist(mapOf("artistId" to "10"), owner) },
            { retrofit(HostCallFactory(bridge)).create(HostCatalogCollectionApi::class.java).unsubscribeArtist(mapOf("artistIds" to "[10]"), owner) },
        )
        val paths = listOf("artist/head/info/get", "artist/albums/10", "v1/artist/10", "v1/artist/songs", "tv-artist-page/artistdetail", "v1/artist/sub", "artist/unsub")
        reads.forEachIndexed { index, read ->
            read()
            assertEquals(paths[index], backend.path)
            assertFalse(backend.parameters.containsKey("expectedSession"))
            if (index == 3) assertEquals(mapOf("id" to "10", "offset" to "100", "limit" to "100", "order" to "hot", "private_cloud" to "true", "work_type" to "1"), backend.parameters)
            if (index in 4..5) assertEquals(mapOf("artistId" to "10"), backend.parameters)
            if (index == 6) assertEquals(mapOf("artistIds" to "[10]"), backend.parameters)
        }
        val before = backend.executions.get()
        bridge.sessions.invalidate()
        reads.forEach { read -> assertTrue(runCatching { read() }.exceptionOrNull() is SessionChangedException) }
        assertEquals(before, backend.executions.get())
    }

    @Test fun obsoleteAlbumOwnerCannotDispatchReadsWritesOrUrlRequests() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        bridge.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { service.getAlbumDetail(id = "10", expectedSession = owner) },
            { retrofit(HostCallFactory(bridge)).create(HostCatalogCollectionApi::class.java).album(mapOf("request" to "{}"), owner) },
            { service.subscribeAlbum(com.ljyh.mei.data.model.api.SubscribePlaylist("10"), owner) },
            { service.unsubscribeAlbum(com.ljyh.mei.data.model.api.SubscribePlaylist("10"), owner) },
            { service.getSongUrlV1(com.ljyh.mei.data.model.api.GetSongUrlV1("[1]", "standard"), owner) },
            { service.getDownloadUrl(com.ljyh.mei.data.model.api.GetDownloadUrl("1_0", "standard"), owner) },
            { service.getCollectAlbumList(com.ljyh.mei.data.model.api.GetAlbumList(), owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertEquals(0, backend.executions.get())
    }

    @Test fun downloadRouteHasOneTupleAndDoesNotSerializeSessionOrPlaybackParameters() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val service = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        service.getDownloadUrl(com.ljyh.mei.data.model.api.GetDownloadUrl("1_0", "sky"), owner)
        assertEquals("song/enhance/download/url/v1", backend.path)
        assertEquals(mapOf("id" to "1_0", "level" to "sky", "immerseType" to "c51"), backend.parameters)
        assertEquals(1, backend.executions.get())
    }

    @Test fun expectedSessionTagsRemainBoundAcrossQueuedExecutionAndClone() {
        val backend = Backend()
        val bridge = bridge(backend)
        val request = post().newBuilder().tag(SessionStamp::class.java, bridge.sessions.snapshot()).build()
        var queued: Runnable? = null
        val call = HostCallFactory(bridge, Executor { queued = it }).newCall(request)
        val copy = call.clone()
        val callback = ResultCallback()
        call.enqueue(callback)
        bridge.sessions.invalidate()
        queued!!.run()
        assertEquals(1, callback.failures)
        assertThrows(SessionChangedException::class.java) { copy.execute() }
        assertEquals(0, backend.executions.get())
    }

    @Test fun playlistReadsAndCollectionsUseOfficialRoutesAndCapturedOwners() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val retrofit = retrofit(HostCallFactory(bridge))
        val api = retrofit.create(ApiService::class.java)
        val collections = HostPlaylistCollectionBackend(retrofit)
        val owner = bridge.sessions.snapshot()
        api.getPlaylistDetail(com.ljyh.mei.data.model.api.GetPlaylistDetail("10"), owner)
        assertEquals("v6/playlist/detail", backend.path)
        assertEquals("10", backend.parameters["id"])
        assertFalse(backend.parameters.containsKey("expectedSession"))
        api.getSongDetail(com.ljyh.mei.data.model.api.GetSongDetails("1,2"), owner)
        assertEquals("v3/song/detail", backend.path)
        assertEquals(mapOf("c" to "[{\"id\":\"1\"},{\"id\":\"2\"}]"), backend.parameters)
        collections.setCollected(10, true, owner)
        assertEquals("multi/terminal/playlist/subscribe", backend.path)
        assertEquals(mapOf("id" to "10"), backend.parameters)
        collections.setCollected(10, false, owner)
        assertEquals("multi/terminal/playlist/unsubscribe", backend.path)
        assertEquals(mapOf("id" to "10"), backend.parameters)
    }

    @Test fun obsoletePlaylistOwnerCannotDispatchReadsOrCollections() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val retrofit = retrofit(HostCallFactory(bridge))
        val api = retrofit.create(ApiService::class.java)
        val collections = HostPlaylistCollectionBackend(retrofit)
        val owner = bridge.sessions.snapshot()
        bridge.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { api.getPlaylistDetail(com.ljyh.mei.data.model.api.GetPlaylistDetail("10"), owner) },
            { api.getSongDetail(com.ljyh.mei.data.model.api.GetSongDetails("1,2"), owner) },
            { collections.setCollected(10, true, owner) },
            { collections.setCollected(10, false, owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertEquals(0, backend.executions.get())
    }

    @Test fun playlistMutationsUseOfficialParametersWithoutSerializingOwners() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val api = retrofit(HostCallFactory(bridge)).create(ApiService::class.java)
        val owner = bridge.sessions.snapshot()
        api.manipulateTracks(com.ljyh.mei.data.model.api.ManipulateTrack("add", "10", "1,2"), owner)
        assertEquals("v1/playlist/manipulate/tracks", backend.path)
        assertEquals(mapOf("op" to "add", "pid" to "10", "trackIds" to "[\"1\",\"2\"]", "reverse" to "true"), backend.parameters)
        api.createPlaylist(com.ljyh.mei.data.model.api.CreatePlaylist("Test", "10"), owner)
        assertEquals("playlist/create", backend.path)
        assertEquals(mapOf("name" to "Test", "privacy" to "10", "type" to "NORMAL"), backend.parameters)
        api.deletePlaylist(com.ljyh.mei.data.model.api.DeletePlaylist("[10]"), owner)
        assertEquals("playlist/remove", backend.path)
        assertEquals(mapOf("ids" to "[10]"), backend.parameters)
        val before = backend.executions.get()
        bridge.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { api.manipulateTracks(com.ljyh.mei.data.model.api.ManipulateTrack("del", "10", "1"), owner) },
            { api.createPlaylist(com.ljyh.mei.data.model.api.CreatePlaylist("Test", "10"), owner) },
            { api.deletePlaylist(com.ljyh.mei.data.model.api.DeletePlaylist("[10]"), owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertEquals(before, backend.executions.get())
    }

    @Test fun dailyRequestsKeepOwnerTagsAndOfficialRecommendationParameters() = runBlocking {
        val backend = Backend()
        val bridge = bridge(backend)
        val api = retrofit(HostCallFactory(bridge)).create(com.ljyh.mei.data.network.api.WeApiService::class.java)
        val owner = bridge.sessions.snapshot()
        val parameters = mapOf("ispush" to "false", "limit" to "30", "trialMode" to "1")
        api.getEveryDayRecommendSongs(parameters, owner)
        assertEquals("v3/discovery/recommend/songs", backend.path)
        assertEquals(parameters, backend.parameters)
        val before = backend.executions.get()
        bridge.sessions.invalidate()
        assertTrue(runCatching { api.getEveryDayRecommendSongs(parameters, owner) }.exceptionOrNull() is SessionChangedException)
        assertEquals(before, backend.executions.get())
    }

    @Test fun queuedCancellationDeliversExactlyOneFailure() {
        val backend = Backend()
        var queued: Runnable? = null
        val factory = HostCallFactory(bridge(backend), Executor { queued = it })
        val callback = ResultCallback()
        val call = factory.newCall(post())
        call.enqueue(callback)
        call.cancel()
        queued!!.run()
        assertEquals(1, callback.failures)
        assertEquals(0, callback.responses)
        assertEquals(0, backend.executions.get())
    }

    @Test fun reportsQueueRejectionAndBackendFailuresOnce() {
        val backend = Backend()
        val rejected = ResultCallback()
        HostCallFactory(bridge(backend), Executor { throw RejectedExecutionException() })
            .newCall(post()).enqueue(rejected)
        assertEquals(1, rejected.failures)
        val failure = ResultCallback()
        backend.action = { throw IOException("test") }
        factory(backend).newCall(post()).enqueue(failure)
        assertEquals(1, failure.failures)
        assertEquals(1, backend.closed.get())
    }

    @Test fun timeoutCancelsTheHostAndReleasesResources() {
        val backend = Backend()
        val canceled = CountDownLatch(1)
        backend.action = { check(canceled.await(5, TimeUnit.SECONDS)); "{}" }
        backend.onCancel = { canceled.countDown() }
        val call = factory(backend).newCall(post())
        call.timeout().timeout(30, TimeUnit.MILLISECONDS)
        assertThrows(InterruptedIOException::class.java) { call.execute() }
        assertTrue(call.isCanceled())
        assertEquals(1, backend.closed.get())
    }

    @Test fun coroutineCancellationReachesTheHost() = runBlocking {
        val backend = Backend()
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        backend.action = { started.countDown(); check(canceled.await(5, TimeUnit.SECONDS)); "{}" }
        backend.onCancel = { canceled.countDown() }
        backend.onClose = { closed.countDown() }
        val service = retrofit(HostCallFactory(bridge(backend))).create(MeloXDirectService::class.java)
        val job = launch(Dispatchers.Default) { service.post("/api/search/get") }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
            assertEquals(0L, canceled.count)
        } finally { canceled.countDown(); job.cancelAndJoin() }
    }

    @Test fun typedRetrofitAndPlaybackDiagnosticsUseTheHostEnvelope() = runBlocking {
        val backend = Backend()
        val service = retrofit(factory(backend)).create(MeloXDirectService::class.java)
        assertEquals(200, service.post("/api/search/get", mapOf("limit" to 1))["code"].asInt)
        val result = submitPlaybackHistoryLog(service, "startplay", mapOf("id" to 1L))
        assertTrue(result.hostAccepted && result.businessAccepted)
        assertFalse(result.httpAccepted)
        assertNull(result.httpStatus)
        assertEquals("feedback/weblog", backend.path)
        val photoBody = Gson().toJsonTree(GetUserPhotoAlbum("1")).asJsonObject
        assertFalse(photoBody.has("header") || photoBody.has("e_r"))
    }

    @Test fun convertsTheExistingTypedPlaylistService() = runBlocking {
        val backend = Backend().apply { action = { "{\"code\":200,\"more\":false,\"playlist\":[]}" } }
        val service = retrofit(factory(backend)).create(ApiService::class.java)
        assertEquals(200, service.getUserPlaylist(GetUserPlaylist("1", limit = "1")).code)
        assertEquals("user/playlist", backend.path)
        assertEquals("1", backend.parameters["uid"])
    }

    @Test fun ordinarySongLikesUseTaggedOfficialRoutesWithoutLegacyFmFields() = runBlocking {
        val backend = Backend().apply { action = { """{"code":200,"ids":[10],"playlistId":100}""" } }
        val sessions = HostSessionBridge()
        val bridge = HostRequestBridge(sessions).apply { bind(backend) }
        val service = retrofit(HostCallFactory(bridge, Executor { it.run() })).create(ApiService::class.java)
        val owner = sessions.snapshot()
        assertEquals(listOf(10L), service.songLikeIds(owner).ids)
        assertEquals("song/like/get", backend.path)
        assertTrue(backend.parameters.isEmpty())
        service.like(com.ljyh.mei.data.model.api.SongLike(10, true), owner)
        assertEquals("song/like", backend.path)
        assertEquals(mapOf("trackId" to "10", "like" to "true", "userid" to "0"), backend.parameters)
        sessions.invalidate()
        val before = backend.executions.get()
        assertTrue(runCatching { service.songLikeIds(owner) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { service.like(com.ljyh.mei.data.model.api.SongLike(10, false), owner) }.exceptionOrNull() is IOException)
        assertEquals(before, backend.executions.get())
    }

    @Test fun syntheticEnvelopeDoesNotTurnABusinessRejectionIntoSuccess() = runBlocking {
        val backend = Backend().apply { action = { "{\"code\":500}" } }
        val service = retrofit(factory(backend)).create(MeloXDirectService::class.java)
        val result = submitPlaybackHistoryLog(service, "play", mapOf("id" to 1L))
        assertTrue(result.hostAccepted)
        assertFalse(result.httpAccepted || result.businessAccepted)
        assertNull(result.httpStatus)
        assertTrue(result.diagnosticSummary().contains("business response rejected"))
    }

    private fun bridge(backend: Backend) = HostRequestBridge(HostSessionBridge()).apply { bind(backend) }
    private fun factory(backend: Backend) = HostCallFactory(bridge(backend), Executor { it.run() })
    private fun retrofit(factory: HostCallFactory) = Retrofit.Builder().baseUrl("https://music.163.com")
        .callFactory(factory).addConverterFactory(GsonConverterFactory.create()).build()
    private fun post(json: String = "{}") = Request.Builder().url("https://music.163.com/api/search/get/")
        .post(json.toRequestBody("application/json".toMediaType())).build()

    private class ResultCallback : Callback {
        var failures = 0
        var responses = 0
        override fun onFailure(call: Call, e: IOException) { failures++ }
        override fun onResponse(call: Call, response: Response) { responses++; response.close() }
    }

    private class Backend : HostRequestBackend {
        var path = ""
        var parameters = emptyMap<String, String>()
        var action: () -> String = { "{\"code\":200}" }
        var onCancel: () -> Unit = {}
        var onClose: () -> Unit = {}
        val executions = AtomicInteger()
        val closed = AtomicInteger()
        override fun sessionIdentity() = SessionIdentity(1, true, false)
        override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
            this.path = path
            this.parameters = parameters
            return object : HostPendingRequest {
                override fun execute(): String { executions.incrementAndGet(); return action() }
                override fun cancel() = onCancel()
                override fun close() { closed.incrementAndGet(); onClose() }
            }
        }
    }
}
