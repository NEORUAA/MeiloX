package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.RetrofitModule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Production Repository/Retrofit/body builder, isolated cache and synthetic session/transport. */
class HomeRequestDeviceTest {
    @Test fun originalHomePayloadCarriesTheOwnerOnlyAsALocalTag() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.repository.getHomePageResourceShow(f.owner) is Resource.Success)
            assertTrue(f.repository.getHomePageResourceShow(f.owner, refresh = true) is Resource.Success)
            assertEquals(2, f.requests.size)
            f.requests.forEachIndexed { index, request ->
                assertEquals("POST", request.method)
                assertEquals("/eapi/link/page/rcmd/resource/show", request.url.encodedPath)
                assertNull(request.url.query)
                assertEquals(f.owner, request.tag(SessionStamp::class.java))
                assertNull(request.header("Cookie"))
                assertNull(request.header("Authorization"))
                val buffer = Buffer()
                requireNotNull(request.body).writeTo(buffer)
                val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                assertEquals(setOf("pageCode", "isFirstScreen", "cursor", "refresh", "widthDp",
                    "heightDp", "loadedPositionCodes", "clientCacheBlockCode", "callbackParameters",
                    "extJson", "pageStyleType", "adExtJson", "reqTimeStamp", "clientTime", "ruleJson",
                    "algDemoteBlockCodeOrderList"), body.keySet())
                assertEquals("HOME_RECOMMEND_PAGE", body.get("pageCode").asString)
                assertEquals("true", body.get("isFirstScreen").asString)
                assertEquals("0", body.get("cursor").asString)
                assertEquals((index == 1).toString(), body.get("refresh").asString)
                assertEquals("cutBlock", body.get("pageStyleType").asString)
            }
            assertEquals(listOf("user_17.json"), f.directory.list()!!.toList())
        } finally { f.close() }
    }

    @Test fun staleRecoveringAndTransitioningSessionsNeverCreateARoute() = runBlocking {
        for (state in listOf("stale", "recovery", "transition")) {
            val f = Fixture()
            val transition = if (state == "transition") f.sessions.beginTransition() else null
            try {
                if (state == "stale") f.sessions.invalidate()
                if (state == "recovery") f.sessions.setRecoveryRequired(true)
                assertTrue(runCatching { f.repository.getHomePageResourceShow(f.owner) }.isFailure)
                assertTrue(f.created.isEmpty())
                assertTrue(f.requests.isEmpty())
                assertTrue(f.directory.list()!!.isEmpty())
            } finally { transition?.close(); f.close() }
        }
    }

    @Test fun routeCreationCannotBorrowAReplacementOrReauthorizedSession() = runBlocking {
        for (replaceAccount in listOf(true, false)) {
            val f = Fixture()
            try {
                f.onCreate = {
                    if (replaceAccount) f.identity = SessionIdentity(18, true, false)
                    f.sessions.invalidate()
                }
                assertTrue(runCatching { f.repository.getHomePageResourceShow(f.owner) }
                    .exceptionOrNull() is SessionChangedException)
                assertEquals(f.owner, f.created.single().tag(SessionStamp::class.java))
                assertTrue(f.requests.isEmpty())
                assertTrue(f.directory.list()!!.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun recoveryDuringTheResponseRejectsItsDataAndAllowsRetry() = runBlocking {
        val f = Fixture()
        try {
            f.onEnqueue = { call, callback ->
                f.sessions.setRecoveryRequired(true)
                callback.onResponse(call, f.response(call.request()))
            }
            assertTrue(runCatching { f.repository.getHomePageResourceShow(f.owner) }.isFailure)
            assertEquals(f.owner, f.created.single().tag(SessionStamp::class.java))
            assertEquals(1, f.requests.size)
            assertTrue(f.directory.list()!!.isEmpty())
            f.sessions.setRecoveryRequired(false)
            f.onEnqueue = { call, callback -> callback.onResponse(call, f.response(call.request())) }
            assertTrue(f.repository.getHomePageResourceShow(f.owner, refresh = true) is Resource.Success)
            assertEquals(2, f.requests.size)
        } finally { f.close() }
    }

    @Test fun invalidatedResponseCannotWriteTheOldAccountCache() = runBlocking {
        val f = Fixture()
        try {
            f.onEnqueue = { call, callback ->
                f.identity = SessionIdentity(18, true, false)
                f.sessions.invalidate()
                callback.onResponse(call, f.response(call.request()))
            }
            assertTrue(runCatching { f.repository.getHomePageResourceShow(f.owner) }
                .exceptionOrNull() is SessionChangedException)
            assertEquals(f.owner, f.requests.single().tag(SessionStamp::class.java))
            assertTrue(f.directory.list()!!.isEmpty())
        } finally { f.close() }
    }

    @Test fun rejectedRefreshRetainsTheCacheAndAFreshRequestCanRecover() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.repository.getHomePageResourceShow(f.owner) is Resource.Success)
            val file = f.directory.listFiles()!!.single()
            val previous = file.readText()
            for (httpFailure in listOf(false, true)) {
                f.httpCode = if (httpFailure) 503 else 200
                f.body = """{"code":403}"""
                assertTrue(f.repository.getHomePageResourceShow(f.owner, refresh = true) is Resource.Error)
                assertEquals(previous, file.readText())
            }
            f.httpCode = 200
            f.body = """{"code":200,"data":{"blocks":[{"positionCode":"retry-fixture"}]}}"""
            val result = f.repository.getHomePageResourceShow(f.owner, refresh = true) as Resource.Success
            assertEquals("retry-fixture", result.data.single().positionCode)
            assertTrue(file.readText().contains("retry-fixture"))
            assertEquals(4, f.requests.size)
            assertTrue(f.requests.all { it.tag(SessionStamp::class.java) == f.owner })
        } finally { f.close() }
    }

    @Test fun cancellationDiscardsALateCallbackWithoutPublishingACache() = runBlocking {
        val f = Fixture()
        try {
            val started = CompletableDeferred<Unit>()
            var pending: Pair<Call, Callback>? = null
            f.onEnqueue = { call, callback -> pending = call to callback; started.complete(Unit) }
            val result = async { f.repository.getHomePageResourceShow(f.owner) }
            started.await()
            result.cancel()
            result.join()
            val (call, callback) = requireNotNull(pending)
            callback.onResponse(call, f.response(call.request()))
            assertTrue(result.isCancelled)
            assertTrue(f.directory.list()!!.isEmpty())
            assertEquals(f.owner, f.requests.single().tag(SessionStamp::class.java))
        } finally { f.close() }
    }

    private class Fixture {
        var identity = SessionIdentity(17, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        val owner = sessions.snapshot()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "home-request-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val created = mutableListOf<Request>()
        val requests = mutableListOf<Request>()
        var onCreate: (Request) -> Unit = {}
        var httpCode = 200
        var body = """{"code":200,"data":{"blocks":[{"positionCode":"fixture"}]}}"""
        var onEnqueue: (Call, Callback) -> Unit = { call, callback ->
            callback.onResponse(call, response(call.request()))
        }
        private val client = OkHttpClient()
        private val bound = SessionCallFactory(sessions, Call.Factory { request ->
            requests += request
            object : Call by client.newCall(request) {
                override fun execute(): Response = error("Unexpected blocking request")
                override fun enqueue(responseCallback: Callback) = onEnqueue(this, responseCallback)
            }
        })
        private val api = RetrofitModule.provideEApiService(RetrofitModule.provideRetrofit(Call.Factory { request ->
            created += request
            onCreate(request)
            bound.newCall(request)
        }))
        val repository = HomeRepository(api, sessions, directory)

        fun response(request: Request): Response = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(httpCode).message("Synthetic response")
            .body(body.toResponseBody("application/json".toMediaType())).build()

        fun close() { directory.deleteRecursively() }
    }
}
