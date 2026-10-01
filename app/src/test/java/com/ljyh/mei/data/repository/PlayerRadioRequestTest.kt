package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.weapi.ExtTransMap
import com.ljyh.mei.data.model.weapi.Radio
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
import retrofit2.http.POST
import retrofit2.http.Tag

class PlayerRadioRequestTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val requests = mutableListOf<List<Any?>>()
    private var code = 200
    private var afterRequest: () -> Unit = { }
    private val api = Proxy.newProxyInstance(WeApiService::class.java.classLoader, arrayOf(WeApiService::class.java)) { _, method, args ->
        check(method.name == "getRadio")
        requests += args!!.dropLast(1)
        afterRequest()
        Radio(code, emptyList(), ExtTransMap(), false, "")
    } as WeApiService
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        _, _, _ -> error("Unrelated backend")
    } as T
    private val repository = PlayerRepository(unused<QQMusicUApiService>(), unused<ApiService>(), api,
        sessions, unused<SongFavoritesBackend>())

    @Test fun originalEmptyPayloadCarriesTheExplicitOwner() = runBlocking {
        assertTrue(repository.getRadio(owner) is Resource.Success)
        assertEquals(listOf(emptyMap<String, String>(), owner), requests.single())
        val method = WeApiService::class.java.methods.single { it.name == "getRadio" }
        assertEquals("/weapi/v1/radio/get", method.getAnnotation(POST::class.java).value)
        assertTrue(method.parameterAnnotations[1].any { it is Tag })
    }

    @Test fun sameAccountReauthorizationRejectsOldRequestBeforeDispatch() = runBlocking {
        sessions.invalidate()
        assertTrue(repository.getRadio(owner) is Resource.Error)
        assertTrue(requests.isEmpty())
    }

    @Test fun guestAndRecoveryNeverDispatchPersonalizedRequests() = runBlocking {
        identity = SessionIdentity(0, false, true)
        assertTrue(repository.getRadio(sessions.snapshot()) is Resource.Error)
        identity = owner.identity
        sessions.setRecoveryRequired(true)
        assertTrue(repository.getRadio(owner) is Resource.Error)
        assertTrue(requests.isEmpty())
    }

    @Test fun lateAccountResponseIsNotPublished() = runBlocking {
        afterRequest = { identity = SessionIdentity(18, true, false); sessions.invalidate() }
        assertTrue(repository.getRadio(owner) is Resource.Error)
    }

    @Test fun recoveryDuringResponseAndBusinessRejectionAreErrors() = runBlocking {
        afterRequest = { sessions.setRecoveryRequired(true) }
        assertTrue(repository.getRadio(owner) is Resource.Error)
        afterRequest = { }
        sessions.setRecoveryRequired(false)
        code = 403
        assertTrue(repository.getRadio(owner) is Resource.Error)
    }

    @Test fun cancellationRemainsCancellation() = runBlocking {
        afterRequest = { throw CancellationException("Retired FM") }
        try { repository.getRadio(owner); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }
}
