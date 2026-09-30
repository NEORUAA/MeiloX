package com.ljyh.mei.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.api.ManipulateTrackResult
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.ui.component.player.OverlayState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Closed playlist substitutes; no account, cloud file or real playlist is modified. */
@RunWith(AndroidJUnit4::class)
class CloudPlaylistOwnershipDeviceTest {
    private val cloud = SongSourceIdentity(999, 88, 7, 17)

    @Test fun sharedOverlayRetainsTheEntryAudioFileOwnerAndAccount() {
        val track = MediaMetadata(17, "Fixture", "", emptyList(), 1000, MediaMetadata.Album(0, ""), source = cloud)
        val overlay = OverlayState.AddToPlaylist(track)
        assertSame(track, overlay.track)
        assertEquals(cloud.key, overlay.track.source?.key)
        val replacement = OverlayState.AddToPlaylist(track.copy(source = cloud.copy(songId = 1000, cloudOwnerId = 89)))
        assertNotEquals(overlay, replacement)
        assertEquals(overlay.track.id, replacement.track.id)
    }

    @Test fun closedAddsAndDeletesRetainFullSourceBatches() = runBlocking {
        val f = Fixture()
        val second = cloud.copy(entryId = 18, cloudOwnerId = 89)
        for (op in listOf("add", "del")) {
            assertTrue(f.repository.manipulateTrack(op, "10", "${cloud.key},2,${cloud.key},${second.key}", f.owner) is Resource.Success)
        }
        assertEquals(listOf(listOf(cloud, SongSourceIdentity(2), second), listOf(cloud, SongSourceIdentity(2), second)), f.sources)
        assertEquals(listOf(f.owner, f.owner), f.owners)
    }

    @Test fun invalidOrForeignSourcesRejectTheWholeBatch() = runBlocking {
        val f = Fixture()
        for (key in listOf(cloud.copy(accountId = 8).key, "meilox-cloud-v1:17:999:88:0",
            "meilox-cloud-v1:17:0999:88:7", "meilox-cloud-v1:17:999:88", "0", "")) {
            assertTrue(f.repository.manipulateTrack("add", "10", "2,$key", f.owner) is Resource.Error)
        }
        assertTrue(f.sources.isEmpty())
    }

    @Test fun pendingRecoveryAndStaleSessionsNeverDispatch() = runBlocking {
        val f = Fixture()
        f.sessions.setRecoveryRequired(true)
        assertTrue(f.repository.manipulateTrack("add", "10", cloud.key, f.owner) is Resource.Error)
        f.sessions.setRecoveryRequired(false)
        f.sessions.invalidate()
        assertTrue(f.repository.manipulateTrack("del", "10", cloud.key, f.owner) is Resource.Error)
        assertTrue(f.sources.isEmpty())
    }

    @Test fun recoveryOrReauthorizationAfterClosedWriteCannotReturnSuccess() = runBlocking {
        for (recover in listOf(true, false)) {
            val f = Fixture()
            f.respond = {
                if (recover) f.sessions.setRecoveryRequired(true) else f.sessions.invalidate()
                ManipulateTrackResult(200)
            }
            assertTrue(f.repository.manipulateTrack("add", "10", cloud.key, f.owner) is Resource.Error)
            assertEquals(listOf(listOf(cloud)), f.sources)
        }
    }

    @Test fun cancellationPropagatesAndBusinessOutcomesStayDistinct() = runBlocking {
        val f = Fixture()
        for (code in listOf(502, 506, 511, 515)) {
            f.respond = { ManipulateTrackResult(code) }
            assertEquals(code, (f.repository.manipulateTrack("add", "10", cloud.key, f.owner) as Resource.Success).data.code)
        }
        f.respond = { throw CancellationException() }
        assertTrue(runCatching { f.repository.manipulateTrack("add", "10", cloud.key, f.owner) }.exceptionOrNull() is CancellationException)
    }

    private class Fixture {
        val sessions = SessionStore().apply { bind { SessionIdentity(7, true, false) } }
        val owner = sessions.snapshot()
        val sources = mutableListOf<List<SongSourceIdentity>>()
        val owners = mutableListOf<SessionStamp>()
        var respond: () -> ManipulateTrackResult = { ManipulateTrackResult(200) }
        private val tracks = PlaylistTracksBackend { op, pid, batch, stamp ->
            check(op == "add" || op == "del")
            assertEquals(10L, pid)
            sources += batch
            owners += stamp
            respond()
        }
        val repository = PlaylistRepository(unused(), unused(), unused(), sessions, unused(), tracks,
            com.ljyh.mei.playback.DownloadSourceBackend { _, _, _ -> error("Closed fixture") })
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
            arrayOf(T::class.java)) { _, _, _ -> error("Closed fixture must not reach the network") } as T
    }
}
