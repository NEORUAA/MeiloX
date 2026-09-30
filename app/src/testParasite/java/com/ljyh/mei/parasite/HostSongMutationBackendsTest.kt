package com.ljyh.mei.parasite

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.runtime.RuntimeBackendModule
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostSongMutationBackendsTest {
    @Test fun fullFavoriteSnapshotKeepsNullAsAnEmptyHostSnapshot() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        wire.response = { """{"code":200,"ids":[10]}""" }
        assertTrue(wire.favorites.isLiked(10, owner))
        assertFalse(wire.favorites.isLiked(20, owner))
        wire.response = { """{"code":200,"ids":null}""" }
        assertFalse(wire.favorites.isLiked(10, owner))
        wire.requests.forEach { assertEquals("song/like/get" to emptyMap<String, String>(), it) }
    }

    @Test fun rejectedOrMalformedFavoriteSnapshotsDoNotBecomeFalse() = runBlocking {
        val wire = Wire()
        for (json in listOf("""{"code":301,"ids":[]}""", """{"code":500}""", """{"code":200,"ids":[0]}""")) {
            wire.response = { json }
            assertTrue(runCatching { wire.favorites.isLiked(10, wire.bridge.sessions.snapshot()) }.isFailure)
        }
    }

    @Test fun ordinaryFavoritesKeepZeroCloudOwnerAndNoFmParameters() = runBlocking {
        val wire = Wire()
        wire.response = { """{"code":200,"playlistId":100}""" }
        for (liked in listOf(true, false)) {
            assertEquals(liked, wire.favorites.setLiked(10, liked, wire.bridge.sessions.snapshot()))
            assertEquals("song/like" to mapOf("trackId" to "10", "like" to liked.toString(), "userid" to "0"), wire.requests.last())
        }
        assertEquals(2, wire.requests.size)
    }

    @Test fun duplicateAndMissingFavoriteResultsReconcileWithoutRepeatingWrites() = runBlocking {
        for (code in listOf(502, 404)) {
            val wire = Wire()
            wire.response = { path -> if (path == "song/like") """{"code":$code}""" else """{"code":200,"ids":[10]}""" }
            assertTrue(wire.favorites.setLiked(10, false, wire.bridge.sessions.snapshot()))
            assertFalse(wire.favorites.setLiked(20, true, wire.bridge.sessions.snapshot()))
            assertEquals(listOf("song/like", "song/like/get", "song/like", "song/like/get"), wire.requests.map { it.first })
        }
    }

    @Test fun rejectedAndMalformedFavoriteAcknowledgmentsAreNotSuccess() = runBlocking {
        val wire = Wire()
        for (json in listOf("""{"code":200}""", """{"code":200,"playlistId":0}""", """{"code":505}""", """{"code":512}""")) {
            wire.response = { json }
            assertTrue(runCatching { wire.favorites.setLiked(10, true, wire.bridge.sessions.snapshot()) }.isFailure)
        }
        assertEquals(4, wire.requests.size)
    }

    @Test fun failedReconciliationDoesNotAcceptTheFavoriteWrite() = runBlocking {
        val wire = Wire()
        wire.response = { path -> if (path == "song/like") """{"code":502}""" else """{"code":500}""" }
        assertTrue(runCatching { wire.favorites.setLiked(10, true, wire.bridge.sessions.snapshot()) }.isFailure)
        assertEquals(listOf("song/like", "song/like/get"), wire.requests.map { it.first })
    }

    @Test fun sessionChangeAfterWriteStopsReconciliation() = runBlocking {
        val wire = Wire()
        wire.response = { wire.bridge.sessions.invalidate(); """{"code":502}""" }
        assertTrue(runCatching { wire.favorites.setLiked(10, true, wire.bridge.sessions.snapshot()) }.exceptionOrNull() is IOException)
        assertEquals(listOf("song/like"), wire.requests.map { it.first })
    }

    @Test fun cloudFavoriteWritesUseTheAudioAndFileOwnerNotTheEntryOrAccount() = runBlocking {
        val wire = Wire()
        val source = SongSourceIdentity(999, 88, 1, 17)
        wire.response = { """{"code":200,"playlistId":100}""" }
        for (liked in listOf(true, false)) {
            assertEquals(liked, wire.favorites.setLiked(source, liked, wire.bridge.sessions.snapshot()))
            assertEquals("song/like" to mapOf("trackId" to "999", "like" to liked.toString(), "userid" to "88"),
                wire.requests.last())
        }
    }

    @Test fun cloudSnapshotAndDuplicateWriteReconciliationUseTheAudioId() = runBlocking {
        for (code in listOf(502, 404)) {
            val wire = Wire()
            val source = SongSourceIdentity(999, 88, 1, 17)
            wire.response = { path -> if (path == "song/like") """{"code":$code}"""
                else """{"code":200,"ids":[17]}""" }
            assertFalse(wire.favorites.isLiked(source, wire.bridge.sessions.snapshot()))
            assertFalse(wire.favorites.setLiked(source, true, wire.bridge.sessions.snapshot()))
            assertEquals(listOf("song/like/get", "song/like", "song/like/get"), wire.requests.map { it.first })
            wire.response = { """{"code":200,"ids":[999]}""" }
            assertTrue(wire.favorites.isLiked(source, wire.bridge.sessions.snapshot()))
        }
    }

    @Test fun foreignMalformedAndStaleCloudFavoritesCannotDispatch() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        for (source in listOf(SongSourceIdentity(999, 88, 2, 17), SongSourceIdentity(999, 88, 0, 17),
            SongSourceIdentity(0, 88, 1, 17))) {
            assertTrue(runCatching { wire.favorites.isLiked(source, owner) }.isFailure)
            assertTrue(runCatching { wire.favorites.setLiked(source, true, owner) }.isFailure)
        }
        wire.bridge.sessions.invalidate()
        val source = SongSourceIdentity(999, 88, 1, 17)
        assertTrue(runCatching { wire.favorites.isLiked(source, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { wire.favorites.setLiked(source, true, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun cloudAccountChangeAfterWriteCannotDispatchReconciliation() = runBlocking {
        val wire = Wire()
        wire.response = { wire.bridge.sessions.invalidate(); """{"code":502}""" }
        assertTrue(runCatching {
            wire.favorites.setLiked(SongSourceIdentity(999, 88, 1, 17), true, wire.bridge.sessions.snapshot())
        }.exceptionOrNull() is IOException)
        assertEquals(listOf("song/like"), wire.requests.map { it.first })
    }

    @Test fun pendingRecoveryCannotDispatchOrReconcileCloudFavorites() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        val source = SongSourceIdentity(999, 88, 1, 17)
        wire.bridge.sessions.setRecoveryRequired(true)
        assertTrue(runCatching { wire.favorites.isLiked(source, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { wire.favorites.setLiked(source, true, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(wire.requests.isEmpty())
        wire.bridge.sessions.setRecoveryRequired(false)
        wire.response = { wire.bridge.sessions.setRecoveryRequired(true); """{"code":502}""" }
        assertTrue(runCatching { wire.favorites.setLiked(source, true, owner) }.exceptionOrNull() is IOException)
        assertEquals(listOf("song/like"), wire.requests.map { it.first })
    }

    @Test fun trackChangesKeepTheTvRouteAndAddOnlyReverseOrdering() = runBlocking {
        val wire = Wire()
        for (op in listOf("add", "del")) {
            val response = wire.tracks.modify(op, 10, listOf(1, 2), wire.bridge.sessions.snapshot())
            assertEquals(200, response.code)
            val expected = mapOf("op" to op, "pid" to "10", "trackIds" to "[\"1\",\"2\"]") +
                if (op == "add") mapOf("reverse" to "true") else emptyMap()
            assertEquals("v1/playlist/manipulate/tracks" to expected, wire.requests.last())
        }
    }

    @Test fun trackBusinessAndPartialOutcomesStayAvailableToTheSharedCaller() = runBlocking {
        val wire = Wire()
        wire.response = { """{"code":200,"count":3,"offlineIds":[2]}""" }
        val partial = wire.tracks.modify("add", 10, listOf(1, 2), wire.bridge.sessions.snapshot())
        assertEquals(listOf(2L), partial.offlineIds)
        assertEquals(3, partial.count)
        for (code in listOf(502, 500)) {
            wire.response = { """{"code":$code}""" }
            assertEquals(code, wire.tracks.modify("add", 10, listOf(1), wire.bridge.sessions.snapshot()).code)
        }
    }

    @Test fun cloudTrackMutationsUseOnlyAudioIdsAndKeepTheTvOrderingContract() = runBlocking {
        val wire = Wire()
        val cloud = SongSourceIdentity(999, 88, 1, 17)
        for (op in listOf("add", "del")) {
            assertEquals(200, wire.tracks.modifySources(op, 10,
                listOf(cloud, SongSourceIdentity(2), cloud.copy(entryId = 18, cloudOwnerId = 89)), wire.bridge.sessions.snapshot()).code)
            val expected = mapOf("op" to op, "pid" to "10", "trackIds" to "[\"999\",\"2\"]") +
                if (op == "add") mapOf("reverse" to "true") else emptyMap()
            assertEquals("v1/playlist/manipulate/tracks" to expected, wire.requests.last())
        }
    }

    @Test fun cloudTrackValidationRejectsTheWholeBatchBeforeOfficialDispatch() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        val cloud = SongSourceIdentity(999, 88, 1, 17)
        for (source in listOf(cloud.copy(accountId = 2), cloud.copy(accountId = 0), cloud.copy(songId = 0))) {
            assertTrue(runCatching { wire.tracks.modifySources("add", 10, listOf(SongSourceIdentity(2), source), owner) }.isFailure)
        }
        assertTrue(runCatching { wire.tracks.modifySources("add", 10, emptyList(), owner) }.isFailure)
        wire.bridge.sessions.setRecoveryRequired(true)
        assertTrue(runCatching { wire.tracks.modifySources("add", 10, listOf(cloud), owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun cloudTrackSuccessCannotCrossRecoveryOrAccountGeneration() = runBlocking {
        for (recover in listOf(true, false)) {
            val wire = Wire()
            val owner = wire.bridge.sessions.snapshot()
            wire.response = {
                if (recover) wire.bridge.sessions.setRecoveryRequired(true) else wire.bridge.sessions.invalidate()
                """{"code":200}"""
            }
            assertTrue(runCatching { wire.tracks.modifySources("add", 10, listOf(SongSourceIdentity(999, 88, 1, 17)), owner) }.isFailure)
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun obsoleteOwnersCannotDispatchFavoriteOrTrackOperations() = runBlocking {
        val wire = Wire()
        val owner = wire.bridge.sessions.snapshot()
        wire.bridge.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { wire.favorites.isLiked(10, owner) }, { wire.favorites.setLiked(10, true, owner) },
            { wire.tracks.modify("add", 10, listOf(1), owner) }, { wire.tracks.modify("del", 10, listOf(1), owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertTrue(wire.requests.isEmpty())
    }

    private class Wire {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var response: (String) -> String = { """{"code":200}""" }
        private val transport = object : HostRequestBackend {
            override fun sessionIdentity() = SessionIdentity(1, true, false)
            override fun open(path: String, parameters: Map<String, String>): HostPendingRequest {
                requests += path to parameters
                return object : HostPendingRequest {
                    override fun execute() = response(path)
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        }
        val bridge = HostRequestBridge(HostSessionBridge()).apply { bind(transport) }
        private val retrofit = RetrofitModule.provideRetrofit(HostCallFactory(bridge, Executor { it.run() }))
        val favorites = RuntimeBackendModule.songFavorites(HostSongFavoritesBackend(retrofit, bridge.sessions))
        val tracks = RuntimeBackendModule.playlistTracks(HostPlaylistTracksBackend(retrofit, bridge.sessions))
    }
}
