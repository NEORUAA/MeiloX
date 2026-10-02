package com.ljyh.mei.parasite

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.AppComponent
import com.ljyh.mei.di.RetrofitModule
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production probe/providers/factory/bridge with synthetic identity and host JSON only. */
@RunWith(AndroidJUnit4::class)
class HostRetrofitProbeDeviceTest {
    @Test fun completeProbePinsEveryRequestToItsCapturedOwner() {
        val f = Fixture()
        f.run()

        assertEquals(REPORTS + "retrofit_complete session_unchanged=true", f.reports)
        assertEquals(PATHS, f.backend.dispatched.map { it.path })
        assertEquals(WIRE_PATHS, f.created.map { it.url.encodedPath })
        assertEquals(PATHS.size, f.backend.executed)
        f.created.forEach { request ->
            assertEquals(f.owner, request.tag(SessionStamp::class.java))
            assertEquals("POST", request.method)
            assertNull(request.url.query)
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
        }
        assertEquals(emptyMap<String, String>(), f.backend.dispatched[0].parameters)
        assertEquals(mapOf("uid" to "41", "limit" to "1", "offset" to "0", "includeVideo" to "false"),
            f.backend.dispatched[1].parameters)
        assertEquals(mapOf("s" to "music", "type" to "1", "limit" to "1", "offset" to "0"),
            f.backend.dispatched[2].parameters)
        assertEquals(mapOf("c" to "[{\"id\":\"77\"}]"), f.backend.dispatched[3].parameters)
        assertEquals(mapOf("id" to "77", "cp" to "false", "tv" to "0", "lv" to "0", "rv" to "0",
            "kv" to "0", "yv" to "0", "ytv" to "0", "yrv" to "0"), f.backend.dispatched[4].parameters)
        assertEquals(mapOf("ids" to "[77]", "level" to "standard", "encodeType" to "flac"),
            f.backend.dispatched[5].parameters)
        assertEquals(emptyMap<String, String>(), f.backend.dispatched[6].parameters)
        assertEquals(setOf("pageCode", "isFirstScreen", "cursor", "refresh", "widthDp", "heightDp",
            "loadedPositionCodes", "clientCacheBlockCode", "callbackParameters", "extJson", "pageStyleType",
            "adExtJson", "reqTimeStamp", "clientTime", "ruleJson", "algDemoteBlockCodeOrderList"),
            f.backend.dispatched[7].parameters.keys)
        assertEquals("false", f.backend.dispatched[7].parameters["refresh"])
        f.backend.dispatched.forEach { dispatch ->
            assertFalse(dispatch.parameters.keys.any { it in setOf("expectedSession", "session", "generation") })
        }
    }

    @Test fun legacyAccountAndSubcountCallsStillUseTheCurrentSession() = runBlocking {
        val f = Fixture()
        assertEquals(200, f.api.getAccountDetail().code)
        assertEquals(200, f.weapi.getUserSubcount().code)
        assertEquals(listOf(PATHS[0], PATHS[6]), f.backend.dispatched.map { it.path })
        assertEquals(2, f.backend.executed)
        f.created.forEach { assertNull(it.tag(SessionStamp::class.java)) }
        f.backend.dispatched.forEach { assertTrue(it.parameters.isEmpty()) }
    }

    @Test fun accountRequestCreationCannotBorrowAReplacementOrReauthorizedSession() {
        for (replaceAccount in listOf(false, true)) {
            val f = Fixture()
            f.onCreate = {
                if (replaceAccount) f.backend.identity = SessionIdentity(42, true, false)
                f.bridge.sessions.invalidate()
            }
            f.run()

            assertEquals(WIRE_PATHS.take(1), f.created.map { it.url.encodedPath })
            assertEquals(f.owner, f.created.single().tag(SessionStamp::class.java))
            assertTrue(f.backend.dispatched.isEmpty())
            assertEquals(0, f.backend.executed)
            assertFalse(f.reports.any { it.startsWith("retrofit=") || it.startsWith("retrofit_complete") })
            assertEquals(listOf("retrofit_aborted type=${SessionChangedException::class.java.name}"),
                f.reports.filter { it.startsWith("retrofit_aborted") })
        }
    }

    @Test fun sameAccountReauthorizationBetweenReportsStopsTheNextDispatch() {
        assertSessionChangeStopsTheNextDispatch(replaceAccount = false)
    }

    @Test fun replacementAccountBetweenReportsStopsTheNextDispatch() {
        assertSessionChangeStopsTheNextDispatch(replaceAccount = true)
    }

    private fun assertSessionChangeStopsTheNextDispatch(replaceAccount: Boolean) {
        REPORTS.indices.forEach { boundary ->
            val f = Fixture()
            f.onReport = { report ->
                if (report == REPORTS[boundary]) {
                    if (replaceAccount) f.backend.identity = SessionIdentity(42, true, false)
                    f.bridge.sessions.invalidate()
                }
            }
            f.run()

            assertEquals("boundary=$boundary", REPORTS.take(boundary + 1),
                f.reports.filter { it.startsWith("retrofit=") })
            assertEquals("boundary=$boundary", PATHS.take(boundary + 1),
                f.backend.dispatched.map { it.path })
            assertEquals("boundary=$boundary", boundary + 1, f.backend.executed)
            assertEquals("boundary=$boundary", WIRE_PATHS.take(minOf(boundary + 2, PATHS.size)),
                f.created.map { it.url.encodedPath })
            f.created.forEach { assertEquals(f.owner, it.tag(SessionStamp::class.java)) }
            assertEquals(listOf("retrofit_aborted type=${SessionChangedException::class.java.name}"),
                f.reports.filter { it.startsWith("retrofit_aborted") })
            assertFalse(f.reports.any { it.startsWith("retrofit_complete") })
            assertTrue(f.reports.count { it.startsWith("retrofit_frame=") } in 1..8)
            val current = f.bridge.sessions.snapshot()
            assertNotEquals(f.owner.generation, current.generation)
            assertEquals(if (replaceAccount) 42L else 41L, current.identity.userId)
        }
    }

    private class Fixture {
        val backend = Backend()
        val bridge = HostRequestBridge(HostSessionBridge()).apply { bind(backend) }
        val owner = bridge.sessions.snapshot()
        val created = mutableListOf<Request>()
        val reports = mutableListOf<String>()
        var onReport: (String) -> Unit = {}
        var onCreate: (Request) -> Unit = {}
        private val hostCalls = HostCallFactory(bridge, Executor { it.run() })
        private val calls = Call.Factory { request ->
            created += request
            onCreate(request)
            hostCalls.newCall(request)
        }
        private val retrofit = RetrofitModule.provideRetrofit(calls)
        val api = RetrofitModule.provideApiService(retrofit)
        val weapi = RetrofitModule.provideWeApiService(RetrofitModule.provideWeApiRetrofit(calls))
        private val eapi = RetrofitModule.provideEApiService(retrofit)
        private val component = Proxy.newProxyInstance(AppComponent::class.java.classLoader,
            arrayOf(AppComponent::class.java)) { _, method, _ ->
            when (method.name) {
                "hostRequests" -> bridge
                "apiService" -> api
                "weapiService" -> weapi
                "eapiService" -> eapi
                else -> error("Unexpected component accessor: ${method.name}")
            }
        } as AppComponent

        fun run() = HostRetrofitProbe(component) { report ->
            reports += report
            onReport(report)
        }.run()
    }

    private data class Dispatch(val path: String, val parameters: Map<String, String>)

    private class Backend : HostRequestBackend {
        var identity = SessionIdentity(41, true, false)
        val dispatched = mutableListOf<Dispatch>()
        var executed = 0
        override fun sessionIdentity() = identity
        override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
            dispatched += Dispatch(path, parameters.toMap())
            return object : HostPendingRequest {
                override fun execute(): String {
                    executed++
                    return when (path) {
                        "nuser/account/get" -> """{"code":200,"profile":{"userId":41}}"""
                        "user/playlist" -> """{"code":200,"playlist":[]}"""
                        "search/get" -> """{"code":200,"result":{"songs":[{"id":77}]}}"""
                        "v3/song/detail" -> """{"code":200,"songs":[]}"""
                        "song/lyric/v1", "subcount" -> """{"code":200}"""
                        "song/enhance/player/url/v1" -> """{"code":200,"data":[]}"""
                        "link/page/rcmd/resource/show" -> """{"code":200,"data":{"blocks":[]}}"""
                        else -> error("Unexpected host business path: $path")
                    }
                }
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }

    companion object {
        private val PATHS = listOf("nuser/account/get", "user/playlist", "search/get", "v3/song/detail",
            "song/lyric/v1", "song/enhance/player/url/v1", "subcount", "link/page/rcmd/resource/show")
        private val WIRE_PATHS = listOf("/api/nuser/account/get", "/api/user/playlist", "/api/search/get/",
            "/api/v3/song/detail", "/api/song/lyric/v1", "/api/song/enhance/player/url/v1",
            "/weapi/subcount", "/eapi/link/page/rcmd/resource/show")
        private val REPORTS = listOf("retrofit=account code=200 matches_session=true",
            "retrofit=playlists code=200 data_present=false", "retrofit=search code=200",
            "retrofit=song_detail code=200", "retrofit=lyrics code=200",
            "retrofit=playback_url code=200 data_present=false", "retrofit=subcount code=200",
            "retrofit=home code=200 blocks_present=false")
    }
}
