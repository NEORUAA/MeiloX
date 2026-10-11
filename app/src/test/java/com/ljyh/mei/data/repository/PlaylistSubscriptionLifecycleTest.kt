package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.EApiSubscribePlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistSubscriptionLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        fixture = Fixture(dispatcher)
    }

    @After
    fun tearDown() {
        fixture.scope.cancel()
    }

    @Test(timeout = 10_000)
    fun acceptedWriteSurvivesCallerCancellationDuringTokenGeneration() = runTest(dispatcher) {
        fixture.tokenGate = CompletableDeferred()
        val caller = launch { fixture.repository.subscribePlaylist("42") }
        runCurrent()
        assertEquals(1, fixture.tokenCalls)
        assertTrue(fixture.writes.isEmpty())

        caller.cancel()
        runCurrent()
        fixture.tokenGate!!.complete(Unit)
        runCurrent()
        assertTrue(caller.isCancelled)
        assertEquals(1, fixture.writes.size)
        fixture.writes.single().response.complete(200)
        runCurrent()
        assertTrue(fixture.serverSubscribed)
    }

    @Test(timeout = 10_000)
    fun newDetailReaderWaitsForTheAcceptedWriteBeforeReadingServerState() = runTest(dispatcher) {
        fixture.tokenGate = CompletableDeferred()
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.subscribePlaylist("42")
        }
        caller.cancel()
        val detail = async { fixture.repository.getPlaylistDetail("42") }
        runCurrent()
        assertEquals(0, fixture.detailCalls)
        assertFalse(detail.isCompleted)

        fixture.tokenGate!!.complete(Unit)
        runCurrent()
        assertEquals(0, fixture.detailCalls)
        fixture.writes.single().response.complete(200)
        runCurrent()
        assertTrue(detail.await().subscribed())
        assertEquals(1, fixture.detailCalls)
    }

    @Test(timeout = 10_000)
    fun detailReadStartedBeforeMutationRetriesItsStaleResponse() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<PlaylistDetail>()
        fixture.detailResponses.addLast(oldResponse)
        val detail = async { fixture.repository.getPlaylistDetail("42") }
        runCurrent()
        assertEquals(1, fixture.detailCalls)

        val write = async { fixture.repository.subscribePlaylist("42") }
        runCurrent()
        fixture.writes.single().response.complete(200)
        runCurrent()
        assertEquals(200, write.await().code())

        oldResponse.complete(Companion.detail(subscribed = false))
        runCurrent()
        assertTrue(detail.await().subscribed())
        assertEquals(2, fixture.detailCalls)
    }

    @Test(timeout = 10_000)
    fun businessFailureReleasesReadersAndAllowsAnotherAttempt() = runTest(dispatcher) {
        val write = async { fixture.repository.subscribePlaylist("42") }
        val waitingDetail = async { fixture.repository.getPlaylistDetail("42") }
        runCurrent()
        fixture.writes.single().response.complete(405)
        runCurrent()
        assertEquals(405, write.await().code())
        assertFalse(waitingDetail.await().subscribed())
        assertFalse(fixture.serverSubscribed)

        val retry = async { fixture.repository.subscribePlaylist("42") }
        runCurrent()
        assertEquals(2, fixture.writes.size)
        fixture.writes.last().response.complete(200)
        runCurrent()
        assertEquals(200, retry.await().code())
        assertTrue(fixture.serverSubscribed)
    }

    @Test(timeout = 10_000)
    fun transportFailureClearsPendingWithoutInventingConfirmedState() = runTest(dispatcher) {
        val write = async { fixture.repository.subscribePlaylist("42") }
        val waitingDetail = async { fixture.repository.getPlaylistDetail("42") }
        runCurrent()
        fixture.writes.single().response.completeExceptionally(IOException("offline"))
        runCurrent()
        assertTrue(write.await() is Resource.Error)
        assertFalse(waitingDetail.await().subscribed())

        fixture.serverSubscribed = true
        assertTrue(fixture.repository.getPlaylistDetail("42").subscribed())
        assertEquals(2, fixture.detailCalls)
    }

    @Test(timeout = 10_000)
    fun duplicatePendingTargetsShareOneRequestAndIndependentWaiters() = runTest(dispatcher) {
        fixture.tokenGate = CompletableDeferred()
        val first = async { fixture.repository.subscribePlaylist("42") }
        val second = async { fixture.repository.subscribePlaylist("42") }
        runCurrent()
        assertEquals(1, fixture.tokenCalls)
        first.cancel()
        fixture.tokenGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.writes.size)
        fixture.writes.single().response.complete(200)
        runCurrent()
        assertEquals(200, second.await().code())
        assertTrue(first.isCancelled)
        assertEquals(2, fixture.tokenCalls)
    }

    @Test(timeout = 10_000)
    fun oppositeTargetsExecuteInOrderInsteadOfDroppingTheSecondRequest() = runTest(dispatcher) {
        val subscribe = async { fixture.repository.subscribePlaylist("42") }
        val unsubscribe = async { fixture.repository.unSubscribePlaylist("42") }
        val detail = async { fixture.repository.getPlaylistDetail("42") }
        runCurrent()
        assertEquals(1, fixture.writes.size)
        assertTrue(fixture.writes.first().subscribed)
        fixture.writes.first().response.complete(200)
        runCurrent()
        assertEquals(2, fixture.writes.size)
        assertFalse(fixture.writes.last().subscribed)
        assertNull(fixture.writes.last().body.checkToken)
        assertEquals(0, fixture.detailCalls)

        fixture.writes.last().response.complete(200)
        runCurrent()
        assertEquals(200, subscribe.await().code())
        assertEquals(200, unsubscribe.await().code())
        assertFalse(detail.await().subscribed())
        assertEquals(3, fixture.tokenCalls)
    }

    @Test(timeout = 10_000)
    fun accountSwitchDuringTokenGenerationRejectsTheOldAccountWrite() = runTest(dispatcher) {
        fixture.tokenGate = CompletableDeferred()
        val write = async { fixture.repository.subscribePlaylist("42") }
        runCurrent()
        fixture.account = "replacement-session"
        fixture.tokenGate!!.complete(Unit)
        runCurrent()
        assertTrue(write.await() is Resource.Error)
        assertTrue(fixture.writes.isEmpty())
        assertFalse(fixture.repository.getPlaylistDetail("42").subscribed())
    }

    @Test(timeout = 10_000)
    fun successfulUnsubscribeCleansLocalPlaylistAfterCallerCancellation() = runTest(dispatcher) {
        fixture.serverSubscribed = true
        fixture.tokenGate = CompletableDeferred()
        fixture.cleanupGate = CompletableDeferred()
        val caller = launch { fixture.repository.unSubscribePlaylist("42") }
        runCurrent()
        caller.cancel()
        fixture.tokenGate!!.complete(Unit)
        runCurrent()
        fixture.writes.single().response.complete(200)
        runCurrent()
        val detail = async { fixture.repository.getPlaylistDetail("42") }
        runCurrent()
        assertTrue(fixture.cleanedPlaylists.isEmpty())
        assertFalse(detail.isCompleted)

        fixture.cleanupGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("42"), fixture.cleanedPlaylists)
        assertFalse(detail.await().subscribed())
        assertTrue(caller.isCancelled)
    }

    @Test(timeout = 10_000)
    fun failedLocalCleanupPreservesSuccessfulServerResult() = runTest(dispatcher) {
        fixture.serverSubscribed = true
        fixture.cleanupFailure = IOException("local database unavailable")
        val write = async { fixture.repository.unSubscribePlaylist("42") }
        runCurrent()
        fixture.writes.single().response.complete(200)
        runCurrent()
        assertEquals(200, write.await().code())
        assertFalse(fixture.repository.getPlaylistDetail("42").subscribed())
    }

    private class Fixture(dispatcher: TestDispatcher) {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var account = "test-session"
        var tokenGate: CompletableDeferred<Unit>? = null
        var tokenCalls = 0
        var detailCalls = 0
        var serverSubscribed = false
        var cleanupGate: CompletableDeferred<Unit>? = null
        var cleanupFailure: Exception? = null
        val cleanedPlaylists = mutableListOf<String>()
        val writes = mutableListOf<PendingWrite>()
        val detailResponses = ArrayDeque<CompletableDeferred<PlaylistDetail>>()

        private val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, arguments ->
            check(method.name == "getPlaylistDetail") { "Unexpected API request: ${method.name}" }
            detailCalls++
            val response = detailResponses.removeFirstOrNull()
            if (response == null) {
                detail(serverSubscribed)
            } else {
                suspendResponse(arguments!!.last()) { response.await() }
            }
        } as ApiService

        private val eapi = Proxy.newProxyInstance(
            EApiService::class.java.classLoader,
            arrayOf(EApiService::class.java),
        ) { _, method, arguments ->
            check(method.name in setOf("subscribePlaylist", "unSubscribePlaylist")) {
                "Unexpected EAPI request: ${method.name}"
            }
            val write = PendingWrite(
                subscribed = method.name == "subscribePlaylist",
                body = arguments!![0] as EApiSubscribePlaylist,
            )
            writes += write
            suspendResponse(arguments.last()) {
                val code = write.response.await()
                if (code == 200) serverSubscribed = write.subscribed
                BaseResponse(code)
            }
        } as EApiService

        private val weapi = Proxy.newProxyInstance(
            WeApiService::class.java.classLoader,
            arrayOf(WeApiService::class.java),
        ) { _, method, _ -> error("Unexpected WEAPI request: ${method.name}") } as WeApiService

        val repository = PlaylistRepository(
            api,
            weapi,
            eapi,
            freshCheckToken = {
                tokenCalls++
                tokenGate?.await()
                "fresh-token-$tokenCalls"
            },
            subscriptionAccount = { account },
            onUnsubscribed = { id ->
                cleanupGate?.await()
                cleanupFailure?.let { throw it }
                cleanedPlaylists += id
            },
            subscriptionScope = scope,
            ioDispatcher = dispatcher,
        )
    }

    private class PendingWrite(val subscribed: Boolean, val body: EApiSubscribePlaylist) {
        val response = CompletableDeferred<Int>()
    }

    private fun Resource<BaseResponse>.code(): Int = (this as Resource.Success<BaseResponse>).data.code

    private fun Resource<PlaylistDetail>.subscribed(): Boolean = (this as Resource.Success<PlaylistDetail>).data.playlist.subscribed

    companion object {
        private fun detail(subscribed: Boolean): PlaylistDetail = Gson().fromJson(
            """{"code":200,"playlist":{"id":42,"subscribed":$subscribed,"tracks":[],"trackIds":[]}}""",
            PlaylistDetail::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        private fun <T> suspendResponse(continuation: Any?, response: suspend () -> T): Any {
            response.startCoroutine(continuation as Continuation<T>)
            return COROUTINE_SUSPENDED
        }
    }
}
