package com.ljyh.mei.ui.component.player.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Closed favorite substitutes only; never sends account or playlist mutations. */
@RunWith(AndroidJUnit4::class)
class CloudFavoriteOwnershipDeviceTest {
    private val cloud = SongSourceIdentity(999, 88, 7, 17)
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun ready(f: Fixture, source: SongSourceIdentity): PlayerLikeSnapshot = withTimeout(5_000) {
        f.state.state.first { it.sourceKey == source.key && it.liked != null && !it.busy }
    }

    @Test fun currentCloudFavoriteReadsAndWritesKeepTheCompleteSource() = runBlocking {
        Fixture().use { f ->
            main { f.state.selectSource(cloud) }
            val displayed = ready(f, cloud)
            assertEquals(17L, displayed.songId)
            assertEquals(true, displayed.liked)
            assertEquals(listOf(cloud), f.reads)
            main { f.state.toggle(displayed) }
            withTimeout(5_000) { f.state.state.first { it.liked == false && !it.busy } }
            assertEquals(listOf(cloud to false), f.writes)
            assertEquals(listOf(f.sessions.snapshot()), f.changes)
        }
    }

    @Test fun replacingTheCloudFileRejectsLateReadAndOldClickWithTheSameEntry() = runBlocking {
        Fixture().use { f ->
            val gate = f.gate()
            val entered = CompletableDeferred<Unit>()
            val second = cloud.copy(songId = 1000, cloudOwnerId = 89)
            f.read = { source ->
                if (source == cloud) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    true
                } else false
            }
            main { f.state.selectSource(cloud) }
            withTimeout(5_000) { entered.await() }
            val stale = f.state.state.value
            main { f.state.selectSource(second) }
            assertEquals(false, ready(f, second).liked)
            gate.complete(Unit)
            main { f.state.toggle(stale) }
            assertTrue(f.writes.isEmpty())
            assertEquals(second.key, f.state.state.value.sourceKey)
            assertEquals(listOf(cloud, second), f.reads)
        }
    }

    @Test fun foreignAccountsAndMalformedSourcesNeverDispatch() = runBlocking {
        Fixture().use { f ->
            for (source in listOf(cloud.copy(accountId = 8), cloud.copy(accountId = 0), cloud.copy(songId = 0))) {
                assertTrue(f.repository.checkSongLike(source, f.sessions.snapshot()) is Resource.Error)
                assertTrue(f.repository.like(source, true, f.sessions.snapshot()) is Resource.Error)
            }
            main { f.state.selectSource(cloud.copy(accountId = 8)) }
            withTimeout(5_000) { f.state.state.first { it.owner != null && it.error != null } }
            main { f.state.toggle(f.state.state.value) }
            assertTrue(f.reads.isEmpty())
            assertTrue(f.writes.isEmpty())
            assertNull(f.state.state.value.liked)
            assertFalse(f.state.state.value.busy)
        }
    }

    @Test fun recoveryAndAccountChangesClearImmediatelyAndDoNotReuseOldCloudSelection() = runBlocking {
        Fixture().use { f ->
            main { f.state.selectSource(cloud) }
            val stale = ready(f, cloud)
            main {
                f.sessions.setRecoveryRequired(true)
                f.sessions.invalidate()
                assertNull(f.state.state.value.liked)
                f.state.toggle(stale)
            }
            assertTrue(f.writes.isEmpty())
            main { f.sessions.setRecoveryRequired(false) }
            ready(f, cloud)
            assertEquals(2, f.reads.size)
            main {
                f.identity = SessionIdentity(8, true, false)
                f.sessions.invalidate()
                assertNull(f.state.state.value.liked)
            }
            withTimeout(5_000) { f.state.state.first { it.owner?.identity?.userId == 8L } }
            assertEquals(2, f.reads.size)
            assertFalse(f.state.state.value.busy)
        }
    }

    @Test fun accountChangeAfterClosedWriteCannotPublishSuccess() = runBlocking {
        Fixture().use { f ->
            val owner = f.sessions.snapshot()
            f.write = { _, liked -> f.sessions.invalidate(); liked }
            assertTrue(f.repository.like(cloud, true, owner) is Resource.Error)
            assertEquals(listOf(cloud to true), f.writes)
            assertTrue(f.changes.isEmpty())
        }
    }

    @Test fun pendingRecoveryCannotDispatchOrReturnSuccessWithoutAnAccountChange() = runBlocking {
        Fixture().use { f ->
            val owner = f.sessions.snapshot()
            f.sessions.setRecoveryRequired(true)
            assertTrue(f.repository.checkSongLike(cloud, owner) is Resource.Error)
            assertTrue(f.repository.like(cloud, true, owner) is Resource.Error)
            assertTrue(f.reads.isEmpty())
            assertTrue(f.writes.isEmpty())
            f.sessions.setRecoveryRequired(false)
            f.read = { f.sessions.setRecoveryRequired(true); true }
            assertTrue(f.repository.checkSongLike(cloud, owner) is Resource.Error)
            f.sessions.setRecoveryRequired(false)
            f.write = { _, liked -> f.sessions.setRecoveryRequired(true); liked }
            assertTrue(f.repository.like(cloud, true, owner) is Resource.Error)
            assertEquals(listOf(cloud), f.reads)
            assertEquals(listOf(cloud to true), f.writes)
            assertTrue(f.changes.isEmpty())
        }
    }

    private class Fixture : Closeable {
        @Volatile var identity = SessionIdentity(7, true, false)
        val sessions = SessionStore().apply { bind { identity } }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reads = CopyOnWriteArrayList<SongSourceIdentity>()
        val writes = CopyOnWriteArrayList<Pair<SongSourceIdentity, Boolean>>()
        val changes = CopyOnWriteArrayList<SessionStamp>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        var read: suspend (SongSourceIdentity) -> Boolean = { true }
        var write: suspend (SongSourceIdentity, Boolean) -> Boolean = { _, liked -> liked }
        val repository = PlayerRepository(unused<QQMusicUApiService>(), unused<ApiService>(), unused<WeApiService>(), sessions,
            object : SongFavoritesBackend {
                override suspend fun isLiked(id: Long, owner: SessionStamp): Boolean = error("Source was discarded")
                override suspend fun setLiked(id: Long, liked: Boolean, owner: SessionStamp): Boolean = error("Source was discarded")
                override suspend fun isLiked(source: SongSourceIdentity, owner: SessionStamp): Boolean {
                    assertEquals(sessions.snapshot(), owner)
                    reads += source
                    return read(source)
                }
                override suspend fun setLiked(source: SongSourceIdentity, liked: Boolean, owner: SessionStamp): Boolean {
                    assertEquals(sessions.snapshot(), owner)
                    writes += source to liked
                    return write(source, liked)
                }
            })
        val state = PlayerLikeState(scope, sessions, repository, changes::add)
        fun gate() = CompletableDeferred<Unit>().also(gates::add)
        override fun close() {
            gates.forEach { it.complete(Unit) }
            state.close()
            scope.cancel()
        }
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java),
        ) { _, _, _ -> error("Closed fixture must not reach the network") } as T
    }
}
