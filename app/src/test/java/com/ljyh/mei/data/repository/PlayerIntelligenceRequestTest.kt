package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetIntelligence
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerIntelligenceRequestTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val requests = mutableListOf<Pair<String, List<Any?>>>()
    private var code = 200
    private var afterRequest: () -> Unit = { }
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        requests += method.name to args!!.dropLast(1)
        afterRequest()
        when (method.name) {
            "getSongDetail" -> Tracks(code, emptyList(), emptyList())
            "getIntelligenceList" -> Intelligence(code, emptyList(), "")
            else -> error("Unrelated request")
        }
    } as ApiService
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        _, _, _ -> error("Unrelated backend")
    } as T
    private val repository = PlayerRepository(unused<QQMusicUApiService>(), api, unused<WeApiService>(), sessions,
        unused<SongFavoritesBackend>())

    @Test fun retainedSeedAndRecommendationPayloadsCarryTheSameStamp() = runBlocking {
        assertTrue(repository.getSongDetail("33", owner) is Resource.Success)
        assertTrue(repository.getIntelligenceList("11", "22", "33", owner) is Resource.Success)
        assertEquals(listOf("getSongDetail", "getIntelligenceList"), requests.map { it.first })
        assertEquals(listOf(GetSongDetails("33"), owner), requests[0].second)
        assertEquals(listOf(GetIntelligence("11", playlistId = "22", startMusicId = "33"), owner), requests[1].second)
    }

    @Test fun staleAuthorizationNeverDispatchesEitherStep() = runBlocking {
        sessions.invalidate()
        assertTrue(repository.getSongDetail("33", owner) is Resource.Error)
        assertTrue(repository.getIntelligenceList("11", "22", "33", owner) is Resource.Error)
        assertTrue(requests.isEmpty())
    }

    @Test fun guestRecoveryAndMalformedIdsNeverDispatch() = runBlocking {
        identity = SessionIdentity(0, false, true)
        assertTrue(repository.getSongDetail("33", sessions.snapshot()) is Resource.Error)
        identity = owner.identity
        sessions.setRecoveryRequired(true)
        assertTrue(repository.getIntelligenceList("11", "22", "33", owner) is Resource.Error)
        sessions.setRecoveryRequired(false)
        assertTrue(repository.getSongDetail("x", owner) is Resource.Error)
        assertTrue(repository.getIntelligenceList("11", "0", "33", owner) is Resource.Error)
        assertTrue(requests.isEmpty())
    }

    @Test fun lateSeedResponseIsRejected() = runBlocking {
        afterRequest = { sessions.invalidate() }
        assertTrue(repository.getSongDetail("33", owner) is Resource.Error)
    }

    @Test fun lateRecommendationResponseIsRejected() = runBlocking {
        afterRequest = { identity = SessionIdentity(18, true, false); sessions.invalidate() }
        assertTrue(repository.getIntelligenceList("11", "22", "33", owner) is Resource.Error)
    }

    @Test fun businessRejectionDoesNotBecomeASuccessfulQueue() = runBlocking {
        code = 403
        assertTrue(repository.getSongDetail("33", owner) is Resource.Error)
        assertTrue(repository.getIntelligenceList("11", "22", "33", owner) is Resource.Error)
    }

    @Test fun cancellationIsNotConvertedToABusinessError() = runBlocking {
        afterRequest = { throw CancellationException("Retired") }
        try { repository.getSongDetail("33", owner); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        try { repository.getIntelligenceList("11", "22", "33", owner); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }
}
