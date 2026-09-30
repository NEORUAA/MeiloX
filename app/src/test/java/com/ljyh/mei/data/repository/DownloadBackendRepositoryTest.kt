package com.ljyh.mei.data.repository

import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.DownloadSources
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.playback.DownloadSourceBackend
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadBackendRepositoryTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
    private val owner = sessions.snapshot()
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        _, _, _ -> error("Download preflight must use only the selected backend")
    } as T
    private fun repository(store: SessionStore = sessions, backend: DownloadSourceBackend) =
        PlaylistRepository(unused(), unused(), unused(), store, unused(), unused(), backend)

    @Test fun preflightUsesOnlyTheSelectedBackendWithTheCapturedInputs() = runBlocking {
        val result = DownloadSources(emptyList(), mapOf("1" to -105))
        val source = repository { ids, quality, stamp ->
            assertEquals(listOf("1", "2"), ids)
            assertEquals(MusicQuality.SKY, quality)
            assertEquals(owner, stamp)
            result
        }
        assertEquals(Resource.Success(result), source.getDownloadSources(listOf("1", "2"), MusicQuality.SKY, owner))
    }

    @Test fun staleGuestAndRecoveringOwnersCannotReachTheBackend() = runBlocking {
        val backend = DownloadSourceBackend { _, _, _ -> error("Must not dispatch") }
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { repository(backend = backend).getDownloadSources(listOf("1"), MusicQuality.STANDARD, owner) }.exceptionOrNull() is SessionChangedException)
        sessions.setRecoveryRequired(false)
        sessions.invalidate()
        assertTrue(runCatching { repository(backend = backend).getDownloadSources(listOf("1"), MusicQuality.STANDARD, owner) }.exceptionOrNull() is SessionChangedException)
        val guest = SessionStore().apply { bind { SessionIdentity(0, false, true) } }
        assertTrue(runCatching { repository(guest, backend).getDownloadSources(listOf("1"), MusicQuality.STANDARD, guest.snapshot()) }.exceptionOrNull() is SessionChangedException)
    }

    @Test fun aLateBackendResultCannotCrossTheAccountGeneration() = runBlocking {
        val source = repository { _, _, _ -> sessions.invalidate(); DownloadSources(emptyList()) }
        assertTrue(runCatching { source.getDownloadSources(listOf("1"), MusicQuality.STANDARD, owner) }.exceptionOrNull() is SessionChangedException)
    }

    @Test fun networkFailureAndCancellationRetainTheirExistingCallerContracts() = runBlocking {
        assertTrue(repository { _, _, _ -> throw IOException("Offline") }.getDownloadSources(listOf("1"), MusicQuality.STANDARD, owner) is Resource.Error)
        assertTrue(runCatching { repository { _, _, _ -> throw CancellationException() }.getDownloadSources(listOf("1"), MusicQuality.STANDARD, owner) }.exceptionOrNull() is CancellationException)
    }
}
