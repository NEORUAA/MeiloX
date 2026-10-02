package com.ljyh.mei.standalone

import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.api.GetSongUrlV1
import com.ljyh.mei.data.repository.SongLyricBackend
import com.ljyh.mei.data.repository.parseRecentHistorySong
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.di.RetrofitModule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in server reads using a private session; no production graph or download recovery. */
class StandaloneLiveReadDeviceTest {
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test fun privateCloudFavoritesReadTheAudioUnderTheExistingCookieAccount() = liveRead { owner ->
        android.util.Log.i("MeiloX-live-read", "cloud_favorites_phase=library")
        val calls = StandaloneTransport(sessions).business
        val api = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
        val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(calls))
        val song = com.ljyh.mei.data.repository.CloudLibraryBackend(api, sessions, eapi).songs(owner).songs.firstOrNull()
        assumeTrue("The account has no cloud entries", song != null)
        val source = requireNotNull(song?.source)
        source.requireAccount(owner.identity)
        android.util.Log.i("MeiloX-live-read", "cloud_favorites_phase=favorite")
        favorites.isLiked(source, owner)
        android.util.Log.i("MeiloX-live-read", "cloud_favorites_phase=complete no_mutation=true")
        // Either boolean is valid. No favorite toggle, playlist write or credential output.
    }

    @Test fun privateCloudLyricsUseTheSourceOwnerWithoutCatalogFallback() = liveRead { owner ->
        val calls = StandaloneTransport(sessions).business
        val api = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
        val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(calls))
        val song = com.ljyh.mei.data.repository.CloudLibraryBackend(api, sessions, eapi).songs(owner).songs.firstOrNull()
        assumeTrue("The account has no cloud entries", song != null)
        val result = lyrics.lyrics(requireNotNull(song?.source).key, owner)
        assertEquals("Cloud lyric read was not accepted", 200, result.code)
        // Empty/no-lyric responses are valid; do not log private lyric text or mutate the account.
    }

    @Test fun privateCloudSourcesUseTheCapturedAccountAndOriginalPlayerDownloadPolicy() = liveRead { owner ->
        val calls = StandaloneTransport(sessions).business
        val api = RetrofitModule.provideMeloXWeapiService(RetrofitModule.provideWeApiRetrofit(calls))
        val eapi = RetrofitModule.provideMeloXEapiService(RetrofitModule.provideRetrofit(calls))
        val page = com.ljyh.mei.data.repository.CloudLibraryBackend(api, sessions, eapi).songs(owner)
        assumeTrue("The account has no cloud entries", page.songs.isNotEmpty())
        assertTrue("Cloud entries lost their account affinity", page.songs.all { it.source?.accountId == owner.identity.userId })
        val song = page.songs.first()
        val identity = requireNotNull(song.source)
        val playback = com.ljyh.mei.playback.PlaybackUrlResolver(apiService, sessions)
            .resolve(identity.key, "standard", owner)
        assertTrue("Cloud playback lost its source namespace", playback.cacheKey.startsWith(
            com.ljyh.mei.playback.playbackCacheKeyPrefix(identity.key, playback.actualQuality, owner.identity)))
        val result = downloads.resolve(
            listOf(identity.key), com.ljyh.mei.constants.MusicQuality.STANDARD, owner)
        val source = requireNotNull(result.sources.singleOrNull())
        assertTrue("Cloud source did not retain the logical entry", source.id == song.id)
        assertTrue("Cloud source has no verified media metadata", source.size > 0 && source.md5.matches(Regex("[a-f0-9]{32}")))
        assertTrue("Cloud source was rejected", result.rejectedCodes.isEmpty())
        // Do not print or transfer signed media URLs or spend a dedicated download grant.
    }

    @Test fun downloadBackendObtainsACompleteSourceFromTheStandalonePlayerContract() = liveRead { owner ->
        val songId = positiveArgument("standaloneReadSongId")
        val result = downloads.resolve(
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
        val api = apiService
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
        val details = apiService.getSongDetail(GetSongDetails(songId.toString()), owner)
        assertEquals("Song detail was rejected", 200, details.code)
        val song = requireNotNull(details.songs.singleOrNull { it.id == songId })
        val albumId = song.al.Id
        val artistId = requireNotNull(song.ar.firstOrNull()).Id
        assertTrue("Catalog identities are unavailable", albumId > 0 && artistId > 0)
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

    private fun liveRead(read: suspend LiveReadFixture.(SessionStamp) -> Unit) = runBlocking {
        assumeTrue("Live server reads are disabled", arguments.getString("standaloneLiveReads") == "true")
        assertEquals("Live checks must not replace production data", "com.neoruaa.meilox.standalone.debug",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        assertTrue("Live reads require offline fixture startup",
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext is StandaloneFixtureApplication)
        try {
            withTimeout(90_000) {
                // Read credentials only after explicit opt-in; verified account writes stay in memory.
                val saved = DataStoreAccountPersistence(InstrumentationRegistry.getInstrumentation().targetContext).read()
                assumeTrue("The isolated app has no saved Cookie", isValidMusicU(saved.musicU))
                val fixture = LiveReadFixture(saved)
                val owner = fixture.authenticate()
                fixture.sessions.requireAuthenticated(owner)
                fixture.read(owner)
                fixture.sessions.requireAuthenticated(owner)
            }
        } catch (error: AssumptionViolatedException) {
            throw error
        } catch (error: Exception) {
            // Raw transport errors can contain private server details; keep runner output bounded.
            throw AssertionError("Standalone live read failed: ${error.javaClass.simpleName}")
        }
    }

    private class LiveReadFixture(saved: StoredAccount) {
        private var account = saved
        val sessions = StandaloneSessionStore(object : StandaloneAccountPersistence {
            override suspend fun read() = account
            override suspend fun write(account: StoredAccount) { this@LiveReadFixture.account = account }
        })
        private val transport = StandaloneTransport(sessions)
        private val retrofit = RetrofitModule.provideRetrofit(transport.business)
        val apiService = RetrofitModule.provideApiService(retrofit)
        val favorites = StandaloneSongFavoritesBackend(retrofit)
        val lyrics = SongLyricBackend(apiService, sessions)
        val downloads = StandaloneDownloadSourceBackend(retrofit, sessions)

        suspend fun authenticate(): SessionStamp {
            val cookie = requireNotNull(sessions.initialize()) { "A saved Cookie is required" }
            assertTrue("The saved Cookie could not be verified", StandaloneAccountController(sessions, transport).login(cookie))
            return sessions.snapshot().also(sessions::requireAuthenticated)
        }
    }
}
