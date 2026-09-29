package com.ljyh.mei.data.repository

import com.ljyh.mei.data.model.api.SongLike
import com.ljyh.mei.data.model.api.SongLikeIds
import com.ljyh.mei.data.model.api.SongLikeResult
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerLikesTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
    private val owner = sessions.snapshot()
    private var readResponse = SongLikeIds(200, listOf(10))
    private var writeResponse = SongLikeResult(200, 100)
    private var afterCall: () -> Unit = {}
    private val reads = mutableListOf<SessionStamp>()
    private val writes = mutableListOf<SongLike>()
    private inline fun <reified T> proxy(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T

    private val repository = PlayerRepository(
        proxy<QQMusicUApiService> { _, _ -> error("Unexpected QQ request") },
        proxy<ApiService> { name, args ->
            when (name) {
                "songLikeIds" -> {
                    assertEquals(owner, args[0])
                    reads += args[0] as SessionStamp
                    readResponse.also { afterCall() }
                }
                "like" -> {
                    assertEquals(owner, args[1])
                    writes += args[0] as SongLike
                    writeResponse.also { afterCall() }
                }
                else -> error("Unexpected $name")
            }
        },
        proxy<WeApiService> { _, _ -> error("Unexpected WEAPI request") }, sessions,
    )

    @Test fun officialSnapshotDistinguishesLikedUnlikedAndNullIds() = runBlocking {
        assertEquals(Resource.Success(true), repository.checkSongLike(10, owner))
        assertEquals(Resource.Success(false), repository.checkSongLike(20, owner))
        readResponse = SongLikeIds(200, null)
        assertEquals(Resource.Success(false), repository.checkSongLike(10, owner))
        assertEquals(3, reads.size)
    }

    @Test fun businessFailuresAndInvalidIdentitiesNeverMeanNotLiked() = runBlocking {
        for (response in listOf(SongLikeIds(301, emptyList()), SongLikeIds(500, null), SongLikeIds(200, listOf(0)))) {
            readResponse = response
            assertTrue(repository.checkSongLike(10, owner) is Resource.Error)
        }
    }

    @Test fun catalogMutationUsesZeroCloudUserAndNoInventedFmContext() = runBlocking {
        assertEquals(Resource.Success(true), repository.like(10, true, owner))
        assertEquals(Resource.Success(false), repository.like(10, false, owner))
        assertEquals(listOf(SongLike(10, true, 0), SongLike(10, false, 0)), writes)
        assertTrue(reads.isEmpty())
    }

    @Test fun duplicateAndMissingResponsesReconcileWithAnAuthoritativeRead() = runBlocking {
        for (code in listOf(502, 404)) {
            writeResponse = SongLikeResult(code, null)
            assertEquals(Resource.Success(true), repository.like(10, false, owner))
            assertEquals(Resource.Success(false), repository.like(20, true, owner))
        }
        assertEquals(4, reads.size)
    }

    @Test fun rejectedAndMalformedAcknowledgmentsNeverToggleState() = runBlocking {
        for (response in listOf(SongLikeResult(200, null), SongLikeResult(200, 0), SongLikeResult(505, null), SongLikeResult(512, null))) {
            writeResponse = response
            assertTrue(repository.like(10, true, owner) is Resource.Error)
        }
    }

    @Test fun failedDuplicateReconciliationIsNotReportedAsSuccess() = runBlocking {
        writeResponse = SongLikeResult(502, null)
        readResponse = SongLikeIds(500, null)
        assertTrue(repository.like(10, true, owner) is Resource.Error)
    }

    @Test fun guestStaleAndInvalidTrackActionsNeverDispatch() = runBlocking {
        val guest = owner.copy(identity = SessionIdentity(0, false, true))
        assertTrue(repository.like(10, true, guest) is Resource.Error)
        assertTrue(repository.checkSongLike(10, guest) is Resource.Error)
        assertTrue(repository.like(0, true, owner) is Resource.Error)
        sessions.invalidate()
        assertTrue(repository.like(10, true, owner) is Resource.Error)
        assertTrue(repository.checkSongLike(10, owner) is Resource.Error)
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun accountInvalidationAfterResponseRejectsTheResult() = runBlocking {
        afterCall = sessions::invalidate
        assertTrue(repository.like(10, true, owner) is Resource.Error)
    }

    @Test fun cancellationIsPropagatedWithoutAnOptimisticResult() = runBlocking {
        afterCall = { throw CancellationException() }
        assertTrue(runCatching { repository.like(10, true, owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { repository.checkSongLike(10, owner) }.exceptionOrNull() is CancellationException)
    }
}
