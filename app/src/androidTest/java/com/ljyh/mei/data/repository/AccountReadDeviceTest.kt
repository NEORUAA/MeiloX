package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Production account/library reads with synthetic sessions and transports; no server or DB writes. */
class AccountReadDeviceTest {
    @Test fun dynamicAccountReadsKeepOriginalBusinessFieldsAndTheTriggeringOwner() = runBlocking {
        val f = DynamicFixture()
        for (action in listOf("detail", "playlists", "week", "all", "recent")) f.read(action)
        val prefix = if (BuildConfig.FLAVOR == "standalone") "/weapi" else "/api"
        assertEquals(listOf("/weapi/v1/user/detail/17", "$prefix/user/playlist", "$prefix/v1/play/record",
            "$prefix/v1/play/record", "$prefix/play-record/song/list"), f.requests.map { it.url.encodedPath })
        assertEquals(listOf(emptySet<String>(), setOf("uid", "limit", "offset", "includeVideo"),
            setOf("uid", "type"), setOf("uid", "type"), setOf("limit")), f.requests.map { fields(it).keySet() })
        assertEquals(17L, fields(f.requests[1]).get("uid").asLong)
        assertEquals(2000, fields(f.requests[1]).get("limit").asInt)
        assertEquals(0, fields(f.requests[1]).get("offset").asInt)
        assertTrue(fields(f.requests[1]).get("includeVideo").asBoolean)
        assertEquals(1, fields(f.requests[2]).get("type").asInt)
        assertEquals(0, fields(f.requests[3]).get("type").asInt)
        assertEquals(100, fields(f.requests[4]).get("limit").asInt)
        assertTrue(f.created.all { it.tag(SessionStamp::class.java) == f.owner })
        assertTrue(f.requests.all { it.tag(SessionStamp::class.java) == f.owner && it.method == "POST" &&
            it.header("Cookie") == null && it.header("Authorization") == null && it.url.query == null })
    }

    @Test fun originalDetailFallbackRetainsTheOwnerAndSupplementalBody() = runBlocking {
        val f = DynamicFixture()
        f.reply = { if (it.url.encodedPath.startsWith("/weapi/")) """{"code":403}""" else f.success() }
        f.read("detail")
        assertEquals(listOf("/weapi/v1/user/detail/17",
            if (BuildConfig.FLAVOR == "standalone") "/eapi/w/v1/user/detail/17" else "/api/w/v1/user/detail/17"),
            f.requests.map { it.url.encodedPath })
        assertEquals(setOf("all", "userId"), fields(f.requests.last()).keySet())
        assertEquals(17L, fields(f.requests.last()).get("userId").asLong)
        assertTrue(fields(f.requests.last()).get("all").asBoolean)
        assertTrue(f.requests.all { it.tag(SessionStamp::class.java) == f.owner })
    }

    @Test fun staleRecoveringAndTransitioningDynamicAccountReadsNeverCreateRoutes() = runBlocking {
        for (action in listOf("detail", "playlists", "week", "recent")) for (state in listOf("stale", "recovery", "transition")) {
            val f = DynamicFixture()
            val transition = if (state == "transition") f.sessions.beginTransition() else null
            try {
                if (state == "stale") f.sessions.invalidate()
                if (state == "recovery") f.sessions.setRecoveryRequired(true)
                assertTrue(runCatching { f.read(action) }.isFailure)
                assertTrue(f.created.isEmpty())
                assertTrue(f.requests.isEmpty())
            } finally { transition?.close() }
        }
    }

    @Test fun dynamicAccountRouteCreationCannotBorrowANewGeneration() = runBlocking {
        for (action in listOf("detail", "playlists", "week", "recent")) for (replace in listOf(false, true)) {
            val f = DynamicFixture()
            f.onCreate = { if (replace) f.identity = SessionIdentity(18, true, false); f.sessions.invalidate() }
            assertTrue(runCatching { f.read(action) }.exceptionOrNull() is SessionChangedException)
            assertEquals(f.owner, f.created.single().tag(SessionStamp::class.java))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun lateDynamicAccountResponsesCannotSucceedOrReachAnAlternative() = runBlocking {
        for (action in listOf("detail", "playlists", "week", "recent")) for (change in listOf("replace", "reauthorize", "recovery")) {
            val f = DynamicFixture()
            f.onEnqueue = { call, callback ->
                if (change == "recovery") f.sessions.setRecoveryRequired(true)
                else { if (change == "replace") f.identity = SessionIdentity(18, true, false); f.sessions.invalidate() }
                callback.onResponse(call, f.response(call.request(), f.success()))
            }
            assertTrue(runCatching { f.read(action) }.isFailure)
            assertEquals(1, f.created.size)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun dynamicAccountHttpAndBusinessFailuresRetainRuntimeRetrySemantics() = runBlocking {
        for (action in listOf("detail", "playlists", "week", "recent")) for (httpFailure in listOf(false, true)) {
            val f = DynamicFixture()
            f.httpCode = if (httpFailure) 503 else 200
            f.reply = { """{"code":403}""" }
            assertTrue(runCatching { f.read(action) }.isFailure)
            val attempts = if (action == "detail" || BuildConfig.FLAVOR == "standalone") 2 else 1
            assertEquals(attempts, f.requests.size)
            f.httpCode = 200
            f.reply = { f.success() }
            f.read(action)
            assertEquals(attempts + 1, f.requests.size)
            assertTrue(f.requests.all { it.tag(SessionStamp::class.java) == f.owner })
        }
    }

    @Test fun canceledDynamicAccountReadsDiscardLateCallbacksWithoutRetry() = runBlocking {
        for (action in listOf("detail", "playlists", "week", "recent")) {
            val f = DynamicFixture()
            val started = CompletableDeferred<Unit>()
            var pending: Pair<Call, Callback>? = null
            f.onEnqueue = { call, callback -> pending = call to callback; started.complete(Unit) }
            val result = async { f.read(action) }
            started.await()
            result.cancel()
            result.join()
            val (call, callback) = requireNotNull(pending)
            callback.onResponse(call, f.response(call.request(), f.success()))
            assertTrue(result.isCancelled)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun profileAndItsOriginalFallbackKeepTheSameOwner() = runBlocking {
        val f = ProfileFixture()
        assertEquals(17L, f.repository.accountProfile(f.owner).id)
        assertEquals(listOf("/api/w/nuser/account/get" to f.owner), f.requests)
        f.requests.clear()
        f.reply = { path ->
            if (path == "/api/w/nuser/account/get") json("""{"code":403}""") else f.profile()
        }
        assertEquals(17L, f.repository.accountProfile(f.owner).id)
        assertEquals(listOf("/api/w/nuser/account/get" to f.owner, "/api/nuser/account/get" to f.owner), f.requests)
    }

    @Test fun replacementAndReauthorizationDoNotDispatchAProfileFallback() = runBlocking {
        for (replace in listOf(false, true)) {
            val f = ProfileFixture()
            f.reply = {
                if (replace) f.identity = SessionIdentity(18, true, false)
                f.sessions.invalidate()
                f.profile()
            }
            assertTrue(runCatching { f.repository.accountProfile(f.owner) }.exceptionOrNull() is SessionChangedException)
            assertEquals(listOf("/api/w/nuser/account/get" to f.owner), f.requests)
        }
    }

    @Test fun staleGuestAndRecoveringOwnersNeverReadAProfile() = runBlocking {
        for (state in listOf("stale", "guest", "recovery")) {
            val f = ProfileFixture()
            if (state == "stale") f.sessions.invalidate()
            if (state == "guest") f.identity = SessionIdentity(0, false, true)
            if (state == "recovery") f.sessions.setRecoveryRequired(true)
            val owner = if (state == "guest") f.sessions.snapshot() else f.owner
            assertTrue(runCatching { f.repository.accountProfile(owner) }.isFailure)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun recoveryDuringTheProfileResponseRejectsItAndAllowsRetry() = runBlocking {
        val f = ProfileFixture()
        f.reply = { f.sessions.setRecoveryRequired(true); f.profile() }
        assertTrue(runCatching { f.repository.accountProfile(f.owner) }.isFailure)
        assertEquals(1, f.requests.size)
        f.sessions.setRecoveryRequired(false)
        f.reply = { f.profile() }
        assertEquals(17L, f.repository.accountProfile(f.owner).id)
        assertEquals(2, f.requests.size)
    }

    @Test fun rejectedProfileFallbackIsNotAUsableAccount() = runBlocking {
        val f = ProfileFixture()
        f.reply = { json("""{"code":403,"message":"Fixture denial"}""") }
        assertTrue(runCatching { f.repository.accountProfile(f.owner) }.isFailure)
        assertEquals(2, f.requests.size)
    }

    @Test fun cancellationDoesNotRetryOrPublishALateProfile() = runBlocking {
        val f = ProfileFixture()
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<JsonObject>()
        f.reply = { started.complete(Unit); withContext(NonCancellable) { pending.await() } }
        val result = async { f.repository.accountProfile(f.owner) }
        started.await()
        result.cancel()
        pending.complete(f.profile())
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(1, f.requests.size)
    }

    @Test fun originalAlbumPagesAndPhotoBodyCarryOnlyALocalOwner() = runBlocking {
        val f = LibraryFixture()
        f.reply = { request ->
            if (request.url.encodedPath == "/api/album/sublist" && fields(request).get("offset").asString == "0") {
                """{"code":200,"hasMore":true,"data":[{"id":1}]}"""
            } else f.success(request)
        }
        assertTrue(f.repository.albums(f.owner) is Resource.Success)
        assertTrue(f.repository.photos(f.owner) is Resource.Success)
        assertEquals(listOf("/api/album/sublist", "/api/album/sublist", "/api/user/photo/album/get"),
            f.requests.map { it.url.encodedPath })
        f.requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals(f.owner, request.tag(SessionStamp::class.java))
            assertNull(request.url.query)
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
        }
        for (index in 0..1) {
            val body = fields(f.requests[index])
            assertEquals(setOf("limit", "offset", "total"), body.keySet())
            assertEquals("100", body.get("limit").asString)
            assertEquals(index.toString(), body.get("offset").asString)
            assertEquals("true", body.get("total").asString)
        }
        val photo = fields(f.requests.last())
        assertEquals(setOf("userId", "page"), photo.keySet())
        assertEquals("17", photo.get("userId").asString)
        val page = JsonParser.parseString(photo.get("page").asString).asJsonObject
        assertEquals(setOf("size"), page.keySet())
        assertFalse(page.has("cursor"))
        assertEquals(10, page.get("size").asInt)
    }

    @Test fun staleGuestAndRecoveryLibraryReadsNeverCreateARoute() = runBlocking {
        for (state in listOf("stale", "guest", "recovery")) {
            val f = LibraryFixture()
            if (state == "stale") f.sessions.invalidate()
            if (state == "guest") f.identity = SessionIdentity(0, false, true)
            if (state == "recovery") f.sessions.setRecoveryRequired(true)
            val owner = if (state == "guest") f.sessions.snapshot() else f.owner
            assertTrue(f.repository.albums(owner) is Resource.Error)
            assertTrue(f.repository.photos(owner) is Resource.Error)
            assertTrue(f.created.isEmpty())
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun libraryRouteCreationCannotBorrowAnotherAccount() = runBlocking {
        for (photos in listOf(false, true)) {
            val f = LibraryFixture()
            f.onCreate = { f.identity = SessionIdentity(18, true, false); f.sessions.invalidate() }
            val result = if (photos) f.repository.photos(f.owner) else f.repository.albums(f.owner)
            assertTrue(result is Resource.Error)
            assertEquals(f.owner, f.created.single().tag(SessionStamp::class.java))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test fun invalidatedOrRecoveringLibraryResponsesCannotAdvanceOrSucceed() = runBlocking {
        for (recover in listOf(false, true)) for (photos in listOf(false, true)) {
            val f = LibraryFixture()
            f.onEnqueue = { call, callback ->
                if (recover) f.sessions.setRecoveryRequired(true) else f.sessions.invalidate()
                callback.onResponse(call, f.response(call.request(), if (photos) f.success(call.request())
                    else """{"code":200,"hasMore":true,"data":[{"id":1}]}"""))
            }
            val result = if (photos) f.repository.photos(f.owner) else f.repository.albums(f.owner)
            assertTrue(result is Resource.Error)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun libraryHttpAndBusinessFailuresCanRetryWithoutChangingOwners() = runBlocking {
        val f = LibraryFixture()
        for (httpFailure in listOf(false, true)) {
            f.httpCode = if (httpFailure) 503 else 200
            f.reply = { """{"code":403}""" }
            assertTrue(f.repository.albums(f.owner) is Resource.Error)
            assertTrue(f.repository.photos(f.owner) is Resource.Error)
        }
        f.httpCode = 200
        f.reply = f::success
        assertTrue(f.repository.albums(f.owner) is Resource.Success)
        assertTrue(f.repository.photos(f.owner) is Resource.Success)
        assertEquals(6, f.requests.size)
        assertTrue(f.requests.all { it.tag(SessionStamp::class.java) == f.owner })
    }

    @Test fun canceledLibraryReadsDiscardLateResponses() = runBlocking {
        for (photos in listOf(false, true)) {
            val f = LibraryFixture()
            val started = CompletableDeferred<Unit>()
            var pending: Pair<Call, Callback>? = null
            f.onEnqueue = { call, callback -> pending = call to callback; started.complete(Unit) }
            val result = async { if (photos) f.repository.photos(f.owner) else f.repository.albums(f.owner) }
            started.await()
            result.cancel()
            result.join()
            val (call, callback) = requireNotNull(pending)
            callback.onResponse(call, f.response(call.request(), f.success(call.request())))
            assertTrue(result.isCancelled)
            assertEquals(1, f.requests.size)
        }
    }

    private class DynamicFixture {
        var identity = SessionIdentity(17, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val owner = sessions.snapshot()
        val created = mutableListOf<Request>()
        val requests = mutableListOf<Request>()
        var onCreate: () -> Unit = {}
        var httpCode = 200
        var reply: (Request) -> String = { success() }
        var onEnqueue: (Call, Callback) -> Unit = { call, callback -> callback.onResponse(call, response(call.request(), reply(call.request()))) }
        private val client = OkHttpClient()
        private val bound = SessionCallFactory(sessions, Call.Factory { request ->
            requests += request
            object : Call by client.newCall(request) {
                override fun execute(): Response = error("Unexpected blocking request")
                override fun enqueue(responseCallback: Callback) = onEnqueue(this, responseCallback)
            }
        })
        private val calls = Call.Factory { request -> created += request; onCreate(); bound.newCall(request) }
        private val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(calls))
        private val weapi = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
        val repository = MeloXRepository(eapi, weapi, InstrumentationRegistry.getInstrumentation().targetContext, sessions,
            CloudUploadCoordinator(eapi, weapi, sessions, unused<CloudBinaryUploader>()), CloudLibraryBackend(weapi, sessions, eapi))
        suspend fun read(action: String) {
            when (action) {
                "detail" -> assertEquals(17L, repository.accountDetail(17, owner).profile.id)
                "playlists" -> assertTrue(repository.accountPlaylists(17, owner).isEmpty())
                "week", "all" -> assertTrue(repository.userPlayRecords(17, action == "all", owner).isEmpty())
                "recent" -> assertTrue(repository.recentSongs(owner).isEmpty())
                else -> error("Unknown closed fixture")
            }
        }
        fun success() = """{"code":200,"profile":{"userId":17,"nickname":"Fixture"},"playlist":[],"weekData":[],"allData":[],"data":{"list":[]}}"""
        fun response(request: Request, text: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(httpCode).message("Fixture").body(text.toResponseBody("application/json".toMediaType())).build()
    }

    private class ProfileFixture {
        var identity = SessionIdentity(17, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val owner = sessions.snapshot()
        val requests = mutableListOf<Pair<String, SessionStamp?>>()
        var reply: suspend (String) -> JsonObject = { profile() }
        val eapi = object : MeloXDirectService {
            override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>,
                expectedSession: SessionStamp?): JsonObject {
                assertTrue(body.isEmpty())
                assertTrue(headers.isEmpty())
                requests += path to expectedSession
                return reply(path)
            }
            override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>):
                retrofit2.Response<ResponseBody> = error("Unrelated playback report")
        }
        val weapi = unused<MeloXDirectService>()
        val repository = MeloXRepository(eapi, weapi, InstrumentationRegistry.getInstrumentation().targetContext, sessions,
            CloudUploadCoordinator(eapi, weapi, sessions, unused<CloudBinaryUploader>()), CloudLibraryBackend(weapi, sessions, eapi))
        fun profile() = json("""{"code":200,"profile":{"userId":17,"nickname":"Fixture"}}""")
    }

    private class LibraryFixture {
        var identity = SessionIdentity(17, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val owner = sessions.snapshot()
        val created = mutableListOf<Request>()
        val requests = mutableListOf<Request>()
        var onCreate: () -> Unit = {}
        var httpCode = 200
        var reply: (Request) -> String = ::success
        var onEnqueue: (Call, Callback) -> Unit = { call, callback ->
            callback.onResponse(call, response(call.request(), reply(call.request())))
        }
        private val client = OkHttpClient()
        private val bound = SessionCallFactory(sessions, Call.Factory { request ->
            requests += request
            object : Call by client.newCall(request) {
                override fun execute(): Response = error("Unexpected blocking request")
                override fun enqueue(responseCallback: Callback) = onEnqueue(this, responseCallback)
            }
        })
        private val api = RetrofitModule.provideApiService(RetrofitModule.provideRetrofit(Call.Factory { request ->
            created += request
            onCreate()
            bound.newCall(request)
        }))
        private val weapi = unused<WeApiService>()
        val repository = AccountLibraryRepository(UserRepository(api, unused<EApiService>(), weapi),
            LocalPlaylistRepository(unused<PlaylistDao>()), PlaylistRepository(api, weapi, unused<PlaylistCollectionBackend>(),
                sessions, unused<CatalogCollectionBackend>(), unused<PlaylistTracksBackend>(),
                unused<com.ljyh.mei.playback.DownloadSourceBackend>()), sessions)

        fun success(request: Request) = if (request.url.encodedPath == "/api/album/sublist")
            """{"code":200,"hasMore":false,"data":[{"id":2}],"count":1}"""
        else """{"code":200,"data":{"page":{"cursor":"","more":false,"size":0},"records":[],"totalSize":0},"message":""}"""
        fun response(request: Request, body: String): Response = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(httpCode).message("Synthetic response")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    companion object {
        private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
        private fun fields(request: Request): JsonObject {
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            return json(buffer.readUtf8())
        }
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
            arrayOf(T::class.java)) { _, _, _ -> error("Unrelated fixture operation") } as T
    }
}
