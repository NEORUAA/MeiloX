package com.ljyh.mei.standalone

import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.repository.parseRecentHistorySong
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.di.RetrofitModule
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in server reads using the isolated app's existing login, never test-supplied credentials. */
class StandaloneLiveReadDeviceTest {
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test fun privateCloudSourcesUseTheCapturedAccountAndOriginalPlayerDownloadPolicy() = liveRead { owner ->
        val sessions = AppGraph.component.standaloneSessions()
        val calls = StandaloneTransport(sessions).business
        val api = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
        val page = com.ljyh.mei.data.repository.CloudLibraryBackend(api, sessions).songs(owner)
        assumeTrue("The account has no cloud entries", page.songs.isNotEmpty())
        assertTrue("Cloud entries lost their account affinity", page.songs.all { it.source?.accountId == owner.identity.userId })
        val song = page.songs.first()
        val identity = requireNotNull(song.source)
        val playback = com.ljyh.mei.playback.PlaybackUrlResolver(AppGraph.component.apiService(), sessions)
            .resolve(identity.key, "standard", owner)
        assertTrue("Cloud playback lost its source namespace", playback.cacheKey.startsWith(
            com.ljyh.mei.playback.playbackCacheKeyPrefix(identity.key, playback.actualQuality, owner.identity)))
        val result = AppGraph.component.downloadSources().resolve(
            listOf(identity.key), com.ljyh.mei.constants.MusicQuality.STANDARD, owner)
        val source = requireNotNull(result.sources.singleOrNull())
        assertTrue("Cloud source did not retain the logical entry", source.id == song.id)
        assertTrue("Cloud source has no verified media metadata", source.size > 0 && source.md5.matches(Regex("[a-f0-9]{32}")))
        assertTrue("Cloud source was rejected", result.rejectedCodes.isEmpty())
        // Do not print or transfer signed media URLs or spend a dedicated download grant.
    }

    @Test fun downloadBackendObtainsACompleteSourceFromTheStandalonePlayerContract() = liveRead { owner ->
        val songId = positiveArgument("standaloneReadSongId")
        val result = AppGraph.component.downloadSources().resolve(
            listOf(songId.toString()), com.ljyh.mei.constants.MusicQuality.STANDARD, owner,
        )
        val source = requireNotNull(result.sources.singleOrNull())
        assertEquals("Download source identity mismatch", songId, source.id)
        assertTrue("Download media metadata is unavailable", source.size > 0 && source.md5.matches(Regex("[a-f0-9]{32}")))
        assertTrue("Download source lifetime is unusable", (source.expiresAtMs ?: 0) > System.currentTimeMillis() + 30_000)
        assertTrue("Selected song was rejected", result.rejectedCodes.isEmpty())
    }

    @Test fun authenticatedSongHasAFullPlaybackSource() = liveRead { owner ->
        val songId = positiveArgument("standaloneReadSongId")
        val api = AppGraph.component.apiService()
        val details = api.getSongDetail(GetSongDetails(songId.toString()), owner)
        assertEquals("Song detail was rejected", 200, details.code)
        assertTrue("Song detail identity mismatch", details.songs.any { it.id == songId })
        val source = api.getSongUrlV1(GetSongUrlV1("[$songId]", "standard"), owner)
            .fullSourceFor(songId.toString())
        assertNotNull("No full, non-trial source for the selected song", source)
        assertTrue("Playback source has no duration", requireNotNull(source).time > 0)
        assertTrue("Playback source has no usable lifetime", (source.expi ?: 0) > 30)
        // Do not print or transfer the signed media URL; playback is verified separately.
    }

    @Test fun catalogCollectionReadsUseTheStandaloneContracts() = liveRead { owner ->
        val songId = positiveArgument("standaloneReadSongId")
        val details = AppGraph.component.apiService().getSongDetail(GetSongDetails(songId.toString()), owner)
        assertEquals("Song detail was rejected", 200, details.code)
        val song = requireNotNull(details.songs.singleOrNull { it.id == songId })
        val albumId = song.al.Id
        val artistId = requireNotNull(song.ar.firstOrNull()).Id
        assertTrue("Catalog identities are unavailable", albumId > 0 && artistId > 0)
        val sessions = AppGraph.component.standaloneSessions()
        val calls = StandaloneTransport(sessions).business
        val backend = StandaloneCatalogCollectionBackend(
            RetrofitModule.provideRetrofit(calls), RetrofitModule.provideWeApiRetrofit(calls), sessions,
        )
        // Both booleans are valid; the adapters reject absent, partial or malformed state.
        backend.albumCollected(albumId, owner)
        backend.artistFollowed(artistId, owner)
    }

    @Test fun remoteHistoryContainsTheExplicitPlaybackWindow() = liveRead { owner ->
        val songId = positiveArgument("standaloneReadSongId")
        val since = positiveArgument("standaloneHistorySinceMs")
        val until = positiveArgument("standaloneHistoryUntilMs")
        assertTrue("Invalid playback observation window", since <= until && until <= System.currentTimeMillis())
        val sessions = AppGraph.component.standaloneSessions()
        val calls = StandaloneTransport(sessions).business
        val api = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
        val response = api.post("/api/play-record/song/list", mapOf("limit" to 100), expectedSession = owner)
        assertEquals("Recent history was rejected", 200, response.get("code")?.asInt)
        val entries = requireNotNull(response.getAsJsonObject("data")?.getAsJsonArray("list"))
        val matching = entries.mapNotNull(::parseRecentHistorySong).any {
            it.id == songId && it.playedAt?.let { time -> time in since..until } == true
        }
        assertTrue("Remote recent history has no matching song in the observed playback window", matching)
    }

    private fun positiveArgument(name: String): Long =
        requireNotNull(arguments.getString(name)?.toLongOrNull()?.takeIf { it > 0 }) {
            "A positive $name argument is required"
        }

    private fun liveRead(read: suspend (SessionStamp) -> Unit) = runBlocking {
        assumeTrue("Live server reads are disabled", arguments.getString("standaloneLiveReads") == "true")
        assertEquals("Live checks must not replace production data", "com.neoruaa.meilox.standalone.debug",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        try {
            withTimeout(90_000) {
                val sessions = AppGraph.component.standaloneSessions()
                val owner = combine(sessions.changes, sessions.recoveryRequired) { _, recovery ->
                    if (recovery) null else runCatching { sessions.snapshot() }.getOrNull()
                        ?.takeIf { it.identity.authenticated && !it.identity.anonymous && it.identity.userId > 0 }
                }.first { it != null }!!
                sessions.requireAuthenticated(owner)
                read(owner)
                sessions.requireAuthenticated(owner)
            }
        } catch (error: Exception) {
            // Raw transport errors can contain private server details; keep runner output bounded.
            throw AssertionError("Standalone live read failed: ${error.javaClass.simpleName}")
        }
    }
}
