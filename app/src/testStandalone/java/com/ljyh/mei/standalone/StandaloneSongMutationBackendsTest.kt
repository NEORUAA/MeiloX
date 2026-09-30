package com.ljyh.mei.standalone

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.session.SessionCallFactory
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.NeteaseInterceptor
import com.ljyh.mei.di.RetrofitModule
import com.ljyh.mei.runtime.RuntimeBackendModule
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StandaloneSongMutationBackendsTest {
    @Test fun favoriteReadsKeepTheOriginalSingleTrackQueryAndCapturedCookie() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        for (liked in listOf(true, false)) {
            wire.response = { """{"code":200,"ids":[${if (liked) "10" else ""}]}""" }
            assertEquals(liked, wire.favorites.isLiked(10, owner))
            val request = wire.originals.last()
            assertEquals("/api/song/like/check", request.url.encodedPath)
            assertEquals(owner, request.tag(SessionStamp::class.java))
            assertEquals(JsonParser.parseString("""{"trackIds":"[10]"}"""), wire.bodies.last())
            assertTrue(wire.signed.last().header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie"))
        }
    }

    @Test fun missingIdsWrongTracksAndBusinessErrorsAreNotFalse() = runBlocking {
        for (json in listOf(
            """{"code":301,"ids":[]}""", """{"code":200}""", """{"code":200,"ids":null}""",
            """{"code":200,"ids":[null]}""", """{"code":200,"ids":[0]}""", """{"code":200,"ids":[11]}""",
        )) {
            val wire = fixture()
            wire.response = { json }
            assertTrue(runCatching { wire.favorites.isLiked(10, wire.sessions.snapshot()) }.isFailure)
        }
    }

    @Test fun favoriteWritesKeepTheBaselineRadioRouteAndCompatibilityFields() = runBlocking {
        val wire = fixture()
        wire.response = { """{"code":200,"playlistId":100,"songs":[]}""" }
        for (liked in listOf(true, false)) {
            assertEquals(liked, wire.favorites.setLiked(10, liked, wire.sessions.snapshot()))
            assertEquals("/api/radio/like", wire.originals.last().url.encodedPath)
            assertEquals(JsonParser.parseString("""{"alg":"itembased","trackId":"10","like":$liked,"time":"3"}"""), wire.bodies.last())
        }
        assertEquals(2, wire.originals.size)
    }

    @Test fun favoriteFailuresDoNotBorrowHostReconciliationOrToggleOptimistically() = runBlocking {
        val wire = fixture()
        for (json in listOf(
            """{"code":200}""", """{"code":200,"playlistId":0}""",
            """{"code":502}""", """{"code":404}""", """{"code":512}""",
        )) {
            wire.response = { json }
            assertTrue(runCatching { wire.favorites.setLiked(10, true, wire.sessions.snapshot()) }.isFailure)
        }
        assertEquals(5, wire.originals.size)
        assertTrue(wire.originals.all { it.url.encodedPath == "/api/radio/like" })
    }

    @Test fun cloudFavoritesKeepCookieRoutesButUseAudioRatherThanTheEntryId() = runBlocking {
        val wire = fixture()
        val source = SongSourceIdentity(999, 88, 7, 17)
        val owner = wire.sessions.snapshot()
        wire.response = { """{"code":200,"ids":[999],"playlistId":100}""" }
        assertTrue(wire.favorites.isLiked(source, owner))
        assertEquals("/api/song/like/check", wire.originals.last().url.encodedPath)
        assertEquals(JsonParser.parseString("""{"trackIds":"[999]"}"""), wire.bodies.last())
        for (liked in listOf(true, false)) {
            assertEquals(liked, wire.favorites.setLiked(source, liked, owner))
            assertEquals("/api/radio/like", wire.originals.last().url.encodedPath)
            assertEquals(owner, wire.originals.last().tag(SessionStamp::class.java))
            assertEquals(JsonParser.parseString("""{"alg":"itembased","trackId":"999","like":$liked,"time":"3"}"""),
                wire.bodies.last())
        }
    }

    @Test fun cloudEntryCollisionIsNotAConfirmedFavorite() = runBlocking {
        val wire = fixture()
        wire.response = { """{"code":200,"ids":[17]}""" }
        assertTrue(runCatching {
            wire.favorites.isLiked(SongSourceIdentity(999, 88, 7, 17), wire.sessions.snapshot())
        }.isFailure)
    }

    @Test fun invalidForeignAndStaleCloudFavoritesDoNotReachCookieSigning() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        for (source in listOf(SongSourceIdentity(999, 88, 8, 17), SongSourceIdentity(999, 88, 0, 17),
            SongSourceIdentity(0, 88, 7, 17))) {
            assertTrue(runCatching { wire.favorites.isLiked(source, owner) }.isFailure)
            assertTrue(runCatching { wire.favorites.setLiked(source, true, owner) }.isFailure)
        }
        wire.sessions.invalidate()
        val source = SongSourceIdentity(999, 88, 7, 17)
        assertTrue(runCatching { wire.favorites.isLiked(source, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { wire.favorites.setLiked(source, true, owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(wire.originals.isEmpty())
        assertTrue(wire.signed.isEmpty())
    }

    @Test fun playlistTrackChangesUseTheOriginalRouteAndImmeWithoutReverse() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        for (op in listOf("add", "del")) {
            assertEquals(200, wire.tracks.modify(op, 10, listOf(1, 2), owner).code)
            assertEquals("/api/playlist/manipulate/tracks", wire.originals.last().url.encodedPath)
            assertEquals(owner, wire.originals.last().tag(SessionStamp::class.java))
            val body = wire.bodies.last()
            assertEquals(setOf("op", "pid", "trackIds", "imme"), body.keySet())
            assertEquals(op, body["op"].asString)
            assertTrue(body["pid"].asJsonPrimitive.isString)
            assertEquals("10", body["pid"].asString)
            assertEquals("[\"1\",\"2\"]", body["trackIds"].asString)
            assertTrue(body["imme"].asBoolean)
        }
    }

    @Test fun trackPartialAndBusinessOutcomesRemainAvailableToTheSharedCaller() = runBlocking {
        val wire = fixture()
        wire.response = { """{"code":200,"count":2,"offlineIds":[2],"trackIds":"[1]"}""" }
        val partial = wire.tracks.modify("add", 10, listOf(1, 2), wire.sessions.snapshot())
        assertEquals(2, partial.count)
        assertEquals(listOf(2L), partial.offlineIds)
        assertEquals("[1]", partial.trackIds?.asString)
        for (code in listOf(502, 500)) {
            wire.response = { """{"code":$code}""" }
            assertEquals(code, wire.tracks.modify("del", 10, listOf(1), wire.sessions.snapshot()).code)
        }
    }

    @Test fun cloudTracksKeepCookieRouteAndImmeWithoutSendingEntryOrFileOwner() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        val cloud = SongSourceIdentity(999, 88, 7, 17)
        for (op in listOf("add", "del")) {
            assertEquals(200, wire.tracks.modifySources(op, 10,
                listOf(cloud, SongSourceIdentity(2), cloud.copy(entryId = 18, cloudOwnerId = 89)), owner).code)
            assertEquals("/api/playlist/manipulate/tracks", wire.originals.last().url.encodedPath)
            assertEquals(owner, wire.originals.last().tag(SessionStamp::class.java))
            assertEquals(JsonParser.parseString("""{"op":"$op","pid":"10","trackIds":"[\"999\",\"2\"]","imme":true}"""), wire.bodies.last())
        }
    }

    @Test fun invalidForeignOrRecoveringCloudTrackBatchesNeverReachCookieSigning() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        val cloud = SongSourceIdentity(999, 88, 7, 17)
        for (source in listOf(cloud.copy(accountId = 8), cloud.copy(accountId = 0), cloud.copy(songId = 0))) {
            assertTrue(runCatching { wire.tracks.modifySources("add", 10, listOf(SongSourceIdentity(2), source), owner) }.isFailure)
        }
        assertTrue(runCatching { wire.tracks.modifySources("add", 10, emptyList(), owner) }.isFailure)
        wire.sessions.setRecoveryRequired(true)
        assertTrue(runCatching { wire.tracks.modifySources("add", 10, listOf(cloud), owner) }.exceptionOrNull() is SessionChangedException)
        assertTrue(wire.originals.isEmpty())
        assertTrue(wire.signed.isEmpty())
    }

    @Test fun cloudTrackSuccessCannotCrossCookieRecoveryOrAccountGeneration() = runBlocking {
        for (recover in listOf(true, false)) {
            val wire = fixture()
            val owner = wire.sessions.snapshot()
            wire.response = {
                if (recover) wire.sessions.setRecoveryRequired(true) else wire.sessions.invalidate()
                """{"code":200}"""
            }
            assertTrue(runCatching { wire.tracks.modifySources("add", 10, listOf(SongSourceIdentity(999, 88, 7, 17)), owner) }.isFailure)
            assertEquals(1, wire.signed.size)
        }
    }

    @Test fun staleOwnersCannotDispatchFavoriteOrTrackOperations() = runBlocking {
        val wire = fixture()
        val owner = wire.sessions.snapshot()
        wire.sessions.invalidate()
        val calls: List<suspend () -> Any> = listOf(
            { wire.favorites.isLiked(10, owner) }, { wire.favorites.setLiked(10, true, owner) },
            { wire.tracks.modify("add", 10, listOf(1), owner) }, { wire.tracks.modify("del", 10, listOf(1), owner) },
        )
        calls.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is SessionChangedException) }
        assertTrue(wire.originals.isEmpty())
    }

    @Test fun accountChangesBeforeResponseCannotReturnSuccessfulState() = runBlocking {
        for (write in listOf(true, false)) {
            val wire = fixture()
            wire.response = { wire.sessions.invalidate(); """{"code":200,"playlistId":100,"ids":[10]}""" }
            val error = runCatching {
                if (write) wire.favorites.setLiked(10, true, wire.sessions.snapshot())
                else wire.favorites.isLiked(10, wire.sessions.snapshot())
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(1, wire.originals.size)
        }
    }

    @Test fun subsequentSessionsUseOnlyTheirOwnCookie() = runBlocking {
        val wire = fixture()
        wire.tracks.modify("add", 10, listOf(1), wire.sessions.snapshot())
        wire.sessions.commitLogin(wire.sessions.beginLogin(), StoredAccount("next-cookie", 8))
        wire.tracks.modify("del", 10, listOf(1), wire.sessions.snapshot())
        assertTrue(wire.signed[0].header("Cookie").orEmpty().contains("MUSIC_U=fixture-cookie"))
        assertTrue(wire.signed[1].header("Cookie").orEmpty().contains("MUSIC_U=next-cookie"))
        assertFalse(wire.signed[1].header("Cookie").orEmpty().contains("fixture-cookie"))
    }

    @Test fun uncertainWritesNeverFallBackToOtherRoutes() = runBlocking {
        val wire = fixture()
        wire.response = { throw IOException("Unknown server result") }
        assertTrue(runCatching { wire.favorites.setLiked(10, true, wire.sessions.snapshot()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { wire.tracks.modify("add", 10, listOf(1), wire.sessions.snapshot()) }.exceptionOrNull() is IOException)
        assertEquals(2, wire.originals.size)
    }

    private suspend fun fixture(): Wire {
        val sessions = StandaloneSessionStore(object : StandaloneAccountPersistence {
            override suspend fun read() = StoredAccount("", 0)
            override suspend fun write(account: StoredAccount) = Unit
        })
        sessions.initialize()
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("fixture-cookie", 7))
        return Wire(sessions)
    }

    private class Wire(val sessions: StandaloneSessionStore) {
        val originals = mutableListOf<Request>()
        val bodies = mutableListOf<JsonObject>()
        val signed = mutableListOf<Request>()
        var response: () -> String = { """{"code":200}""" }
        private val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                originals += chain.request()
                bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
                chain.proceed(chain.request())
            }
            .addInterceptor(NeteaseInterceptor { "fixture-device" })
            .addInterceptor { chain ->
                signed += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("synthetic").body(response().toResponseBody()).build()
            }.build()
        private val calls = SessionCallFactory(sessions, client) { request, owner ->
            request.newBuilder().tag(StandaloneCredentials::class.java, sessions.credentials(owner)).build()
        }
        private val retrofit = RetrofitModule.provideRetrofit(calls)
        val favorites = RuntimeBackendModule.songFavorites(StandaloneSongFavoritesBackend(retrofit))
        val tracks = RuntimeBackendModule.playlistTracks(StandalonePlaylistTracksBackend(retrofit, sessions))
    }
}
