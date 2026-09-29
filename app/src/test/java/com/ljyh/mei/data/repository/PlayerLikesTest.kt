package com.ljyh.mei.data.repository

import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayerLikesTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private var read: (Long) -> Boolean = { it == 10L }
    private var write: (Boolean) -> Boolean = { it }
    private var afterCall: () -> Unit = {}
    private val reads = mutableListOf<Pair<Long, SessionStamp>>()
    private val writes = mutableListOf<Pair<Long, Boolean>>()
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, _, _ -> error("Unexpected direct request") } as T

    private val repository = PlayerRepository(unused<QQMusicUApiService>(), unused<ApiService>(), unused<WeApiService>(), sessions,
        object : SongFavoritesBackend {
            override suspend fun isLiked(id: Long, owner: SessionStamp): Boolean {
                assertEquals(sessions.snapshot(), owner)
                reads += id to owner
                return read(id).also { afterCall() }
            }
            override suspend fun setLiked(id: Long, liked: Boolean, owner: SessionStamp): Boolean {
                assertEquals(sessions.snapshot(), owner)
                writes += id to liked
                return write(liked).also { afterCall() }
            }
        })

    @Test fun likedStateComesFromTheSelectedRuntimeUnderTheCapturedOwner() = runBlocking {
        assertEquals(Resource.Success(true), repository.checkSongLike(10, owner))
        assertEquals(Resource.Success(false), repository.checkSongLike(20, owner))
        assertEquals(listOf(10L to owner, 20L to owner), reads)
    }

    @Test fun bothWriteDirectionsUseTheSelectedRuntime() = runBlocking {
        assertEquals(Resource.Success(true), repository.like(10, true, owner))
        assertEquals(Resource.Success(false), repository.like(10, false, owner))
        assertEquals(listOf(10L to true, 10L to false), writes)
        assertTrue(reads.isEmpty())
    }

    @Test fun backendReconciliationIsReturnedWithoutAnOptimisticToggle() = runBlocking {
        write = { !it }
        assertEquals(Resource.Success(false), repository.like(10, true, owner))
        assertEquals(Resource.Success(true), repository.like(10, false, owner))
    }

    @Test fun readAndWriteFailuresNeverBecomeFalseOrSuccess() = runBlocking {
        read = { throw IOException("Unknown state") }
        write = { throw IOException("Rejected write") }
        assertTrue(repository.checkSongLike(10, owner) is Resource.Error)
        assertTrue(repository.like(10, true, owner) is Resource.Error)
    }

    @Test fun guestStaleAndInvalidTrackActionsNeverDispatch() = runBlocking {
        for (guest in listOf(SessionIdentity(0, false, true), SessionIdentity(1, true, true), SessionIdentity(0, true, false))) {
            identity = guest
            assertTrue(repository.like(10, true, sessions.snapshot()) is Resource.Error)
            assertTrue(repository.checkSongLike(10, sessions.snapshot()) is Resource.Error)
        }
        identity = owner.identity
        assertTrue(repository.like(0, true, owner) is Resource.Error)
        assertTrue(repository.checkSongLike(-1, owner) is Resource.Error)
        sessions.invalidate()
        assertTrue(repository.like(10, true, owner) is Resource.Error)
        assertTrue(repository.checkSongLike(10, owner) is Resource.Error)
        assertTrue(reads.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun accountInvalidationAfterReadOrWriteRejectsTheResult() = runBlocking {
        afterCall = sessions::invalidate
        assertTrue(repository.like(10, true, owner) is Resource.Error)
        assertTrue(repository.checkSongLike(10, sessions.snapshot()) is Resource.Error)
    }

    @Test fun cancellationIsPropagatedWithoutAnOptimisticResult() = runBlocking {
        afterCall = { throw CancellationException() }
        assertTrue(runCatching { repository.like(10, true, owner) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { repository.checkSongLike(10, owner) }.exceptionOrNull() is CancellationException)
    }
}
