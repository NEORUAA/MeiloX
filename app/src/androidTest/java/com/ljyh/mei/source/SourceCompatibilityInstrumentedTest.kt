package com.ljyh.mei.source

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.network.NeteaseWireCodec
import com.ljyh.mei.utils.encrypt.createRandomKey
import com.ljyh.mei.utils.encrypt.encryptEApi
import com.ljyh.mei.utils.netease.NeteaseUtils.getWNMCID
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.Request
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.source.api.AudioResource
import com.ljyh.mei.source.api.AccountCapability
import com.ljyh.mei.source.api.CatalogIndexCapability
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.source.api.PlaylistRef
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.core.SourceFeatureExecutor
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.ResourceValidationCapability
import com.ljyh.mei.source.script.ScriptAudioResources
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.PlaybackCapability
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceCoreEffectCapability
import com.ljyh.mei.source.script.ScriptMusicProvider
import com.ljyh.mei.source.script.ScriptNetworkPolicy
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.withTimeout
import com.ljyh.mei.playback.DownloadSourceEntryPoint
import com.ljyh.mei.playback.PlaybackArtistSnapshot
import com.ljyh.mei.playback.PlaybackItemSnapshot
import com.ljyh.mei.playback.PlaybackPersistence
import com.ljyh.mei.playback.PlaybackSnapshot
import com.ljyh.mei.playback.OfficialMobileShuffle
import com.ljyh.mei.source.api.AlbumRef
import com.ljyh.mei.source.api.ArtistRef
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.TrackRefCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compatibility fixtures use isolated data; the installed-source smoke performs read-only online requests. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SourceCompatibilityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun installedNeteaseCloudAndPodcastsUseBoundedReadPaths(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("neteaseCloudPodcastLive") == "true")
        val baseline = args.getString("neteaseCloudPodcastBaseline") == "true"
        androidx.test.core.app.ActivityScenario.launch(com.ljyh.mei.MainActivity::class.java).use {
            fun phase(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
            val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
            withTimeout(20_000) { router.registry.awaitInitialized() }
            val provider = router.registry.requireProvider(ProviderIds.NETEASE) as ScriptMusicProvider
            withTimeout(30_000) { router.account(ProviderIds.NETEASE) }
            val configuration = provider.platformConfiguration().toMap()
            val revision = provider.accountRevision.value
            val client = com.ljyh.mei.source.core.SourceFeatureClient(router, SourceFeatureExecutor(router),
                com.ljyh.mei.source.core.SourcePreferences(context.dataStore),
                com.ljyh.mei.source.platform.SourcePlatformEffects(context, router.registry))
            val repository = com.ljyh.mei.source.shared.SharedOnlineRepository(client)
            val policy = ScriptMusicProvider::class.java.getDeclaredField("network").apply { isAccessible = true }.get(provider) as ScriptNetworkPolicy
            val field = ScriptNetworkPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }
            val original = field.get(policy) as OkHttpClient
            val cloudReads = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
            val podcastPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
            try {
                field.set(policy, original.newBuilder().addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath
                    if (path.endsWith("/cloud/get")) {
                        val buffer = okio.Buffer(); checkNotNull(request.body).writeTo(buffer)
                        val params = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                        cloudReads += params.get("offset").asInt to params.get("limit").asInt
                    }
                    if (path.contains("/djradio/")) podcastPaths += path
                    chain.proceed(request)
                }.build())
                val firstSession = repository.screen("cloud-first-page-live", ProviderIds.NETEASE)
                phase("Physical foreground cloud first-page read starting")
                val started = android.os.SystemClock.elapsedRealtime()
                val first = withTimeout(60_000) {
                    if (baseline) {
                        val features = firstSession.features()
                        val params = JsonObject().apply { addProperty("offset", 0); addProperty("limit", 200) }
                        com.ljyh.mei.source.shared.SourceOnlineParser(features.providerId, features.instanceId)
                            .cloudPage(features.invoke("cloud", "cloudSongs", params), 0)
                    } else firstSession.cloudSongs(limit = 30)
                }
                val firstMs = android.os.SystemClock.elapsedRealtime() - started
                phase("Cloud first page elapsedMs=$firstMs rows=${first.songs.size} nextOffset=${first.nextOffset}")
                if (baseline) {
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                        "Cloud first page baseline=true elapsedMs=$firstMs rows=${first.songs.size} nextOffset=${first.nextOffset} reads=$cloudReads\n") })
                } else {
                    assertTrue(first.songs.size <= 30)
                    assertTrue(cloudReads.isNotEmpty())
                    assertTrue(cloudReads.all { it == (0 to 30) })
                    val beforeWarm = cloudReads.size
                    val warmStarted = android.os.SystemClock.elapsedRealtime()
                    val warm = withTimeout(5_000) { repository.screen("cloud-reopen-live", ProviderIds.NETEASE).cloudSongs(limit = 30) }
                    val warmMs = android.os.SystemClock.elapsedRealtime() - warmStarted
                    assertTrue("Reopened cloud data must match the cached first page", first == warm)
                    assertEquals("Reopened cloud page must reuse the bound host cache", beforeWarm, cloudReads.size)
                    phase("Cloud cached reopen verified; explicit refresh starting")
                    withTimeout(60_000) { firstSession.cloudSongs(limit = 30, refresh = true) }
                    assertTrue("Explicit refresh must still read the service", cloudReads.size > beforeWarm)
                    if (first.hasMore) {
                        phase("Cloud explicit refresh verified; next page starting")
                        val next = withTimeout(60_000) { firstSession.cloudSongs(offset = first.nextOffset, limit = 200) }
                        assertTrue(next.nextOffset > first.nextOffset || !next.hasMore)
                        assertEquals(first.nextOffset to 200, cloudReads.last())
                    }
                    phase("Cloud paging verified; podcast home starting")
                    val podcastStarted = android.os.SystemClock.elapsedRealtime()
                    val home = withTimeout(60_000) { repository.screen("podcast-live", ProviderIds.NETEASE).podcastHome() }
                    val podcastMs = android.os.SystemClock.elapsedRealtime() - podcastStarted
                    assertEquals("Podcast home must finish its three distinct read phases", 3, podcastPaths.size)
                    assertEquals(3, podcastPaths.distinct().size)
                    assertTrue(home.categories.isNotEmpty())
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                        "Cloud first page baseline=false elapsedMs=$firstMs rows=${first.songs.size} nextOffset=${first.nextOffset} warmMs=$warmMs warmHttp=0 reads=$cloudReads; podcastMs=$podcastMs categories=${home.categories.size} featured=${home.featured.size} personalized=${home.personalized.size} podcastHttp=${podcastPaths.size}\n") })
                }
            } finally {
                field.set(policy, original)
                assertTrue("Read-only cloud/podcast checks must preserve source configuration", configuration == provider.platformConfiguration())
                assertTrue("Read-only cloud/podcast checks must preserve account revision", revision == provider.accountRevision.value)
            }
        }
    }

    @Test fun installedNeteaseMetadataWindowUsesAtMostOneBatchRequest(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("neteaseBatchCatalogLive") == "true")
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        withTimeout(20_000) { router.registry.awaitInitialized() }
        val provider = router.registry.requireProvider(ProviderIds.NETEASE) as ScriptMusicProvider
        val configuration = provider.platformConfiguration().toMap()
        val revision = provider.accountRevision.first()
        val refs = listOf("26134303", "745026", "745206", "2667169196", "34077763", "28977409", "2125632190", "32235257")
            .map { TrackRef(ProviderIds.NETEASE, it) }
        val wanted = refs.map { it.trackId }.toSet()
        val networkField = ScriptMusicProvider::class.java.getDeclaredField("network").apply { isAccessible = true }
        val policy = networkField.get(provider) as ScriptNetworkPolicy
        val clientField = ScriptNetworkPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = clientField.get(policy) as OkHttpClient
        val requests = AtomicInteger()
        try {
            clientField.set(policy, original.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.endsWith("/song/detail")) {
                    val matching = runCatching {
                        val buffer = okio.Buffer(); requireNotNull(request.body).writeTo(buffer)
                        val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                        val rows = JsonParser.parseString(body.get("c").asString).asJsonArray
                        rows.any { it.asJsonObject.get("id").asString in wanted }
                    }.getOrDefault(false)
                    if (matching) requests.incrementAndGet()
                }
                chain.proceed(request)
            }.build())
            val start = android.os.SystemClock.elapsedRealtime()
            val tracks = withTimeout(30_000) { router.getTracks(refs) }
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Metadata window requested=${refs.size} returned=${tracks.size} detailHttp=${requests.get()} elapsedMs=$elapsed\n") })
            assertEquals("The visible metadata batch must return every requested track", refs.toSet(), tracks.map { it.ref }.toSet())
            assertTrue("Uncached metadata must use at most one batch HTTP request", requests.get() in 0..1)
        } finally {
            clientField.set(policy, original)
            assertTrue("The metadata check must preserve source configuration", configuration == provider.platformConfiguration())
            assertTrue("The metadata check must preserve account revision", revision == provider.accountRevision.first())
        }
    }

    @Test fun installedNeteaseLibraryReusesVerifiedAccount(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("neteaseAccountReuseLive") == "true")
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        withTimeout(20_000) { router.registry.awaitInitialized() }
        val provider = router.registry.requireProvider(ProviderIds.NETEASE) as ScriptMusicProvider
        val configuration = provider.platformConfiguration().toMap()
        val networkField = ScriptMusicProvider::class.java.getDeclaredField("network").apply { isAccessible = true }
        val policy = networkField.get(provider) as ScriptNetworkPolicy
        val clientField = ScriptNetworkPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = clientField.get(policy) as OkHttpClient
        val requests = AtomicInteger()
        try {
            clientField.set(policy, original.newBuilder().addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/nuser/account/get")) requests.incrementAndGet()
                chain.proceed(chain.request())
            }.build())
            val account = withTimeout(30_000) { router.account(ProviderIds.NETEASE, refresh = true) }
            assertNotNull("The reuse check requires a verified account", account)
            val initialReads = requests.get()
            val start = android.os.SystemClock.elapsedRealtime()
            withTimeout(90_000) {
                repeat(3) {
                    router.library(ProviderIds.NETEASE, LibraryCollection.LIKED_TRACKS, PageRequest(limit = 30))
                    router.library(ProviderIds.NETEASE, LibraryCollection.CREATED_PLAYLISTS, PageRequest(limit = 30))
                }
            }
            val repeatedReads = requests.get() - initialReads
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            val beforeRefresh = requests.get()
            val refreshed = withTimeout(30_000) { router.account(ProviderIds.NETEASE, refresh = true) }
            val refreshReads = requests.get() - beforeRefresh
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Library account pages=6 initialHttp=$initialReads repeatedHttp=$repeatedReads explicitRefreshHttp=$refreshReads elapsedMs=$elapsed\n") })
            assertEquals("An explicit account refresh must retain the account", account?.ref, refreshed?.ref)
            assertEquals("Read-only library navigation must reuse the verified session identity", 0, repeatedReads)
            assertTrue("An explicit refresh must still contact the account endpoint", refreshReads > 0)
        } finally {
            clientField.set(policy, original)
            assertEquals("The reuse check must preserve source configuration", configuration, provider.platformConfiguration())
        }
    }

    @Test fun installedNeteaseDetailFirstPagesReuseCatalogSnapshots(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("neteaseDetailFirstPageLive") == "true")
        val baseline = args.getString("neteaseDetailBaseline") == "true"
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        withTimeout(20_000) { router.registry.awaitInitialized() }
        val provider = router.registry.requireProvider(ProviderIds.NETEASE) as ScriptMusicProvider
        val configuration = provider.platformConfiguration().toMap()
        val revision = provider.accountRevision.value
        val policy = ScriptMusicProvider::class.java.getDeclaredField("network").apply { isAccessible = true }.get(provider) as ScriptNetworkPolicy
        val field = ScriptNetworkPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = field.get(policy) as OkHttpClient
        val playlistRequests = AtomicInteger()
        val albumRequests = AtomicInteger()
        val metadataRows = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val windows = java.util.concurrent.CopyOnWriteArrayList<Int>()
        try {
            field.set(policy, original.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                if (path.contains("playlist/detail")) {
                    playlistRequests.incrementAndGet()
                    runCatching {
                        val buffer = okio.Buffer(); checkNotNull(request.body).writeTo(buffer)
                        JsonParser.parseString(buffer.readUtf8()).asJsonObject.get("n")?.asInt
                    }.getOrNull()?.let(metadataRows::add)
                }
                if (path.contains("/album/")) albumRequests.incrementAndGet()
                if (path.endsWith("/song/detail")) runCatching {
                    val buffer = okio.Buffer(); checkNotNull(request.body).writeTo(buffer)
                    val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                    JsonParser.parseString(body.get("c").asString).asJsonArray.size()
                }.getOrNull()?.let(windows::add)
                chain.proceed(request)
            }.build())
            val playlist = PlaylistRef(ProviderIds.NETEASE, args.getString("neteaseDetailPlaylistId") ?: "945595972")
            val album = AlbumRef(ProviderIds.NETEASE, args.getString("neteaseDetailAlbumId") ?: "32311")
            val playlistStart = android.os.SystemClock.elapsedRealtime()
            val first = withTimeout(60_000) { router.playlistTracks(playlist, PageRequest(limit = 30, refresh = true)) }
            withTimeout(20_000) { router.playlist(playlist) }
            val playlistMs = android.os.SystemClock.elapsedRealtime() - playlistStart
            assertTrue(first.items.isNotEmpty())
            val beforeIndex = playlistRequests.get()
            val ids = checkNotNull((provider as CatalogIndexCapability).completeTrackIds(playlist.playlistId)).ids
            assertTrue(ids.size > 30)
            assertEquals(beforeIndex, playlistRequests.get())
            val albumStart = android.os.SystemClock.elapsedRealtime()
            withTimeout(20_000) { router.album(album) }
            val firstAlbum = withTimeout(40_000) { router.albumTracks(album, PageRequest(limit = 30)) }
            val albumMs = android.os.SystemClock.elapsedRealtime() - albumStart
            assertTrue(firstAlbum.items.isNotEmpty())
            val albumReads = albumRequests.get()
            val beforeWarm = playlistRequests.get() + albumRequests.get() + windows.size
            val warmStart = android.os.SystemClock.elapsedRealtime()
            withTimeout(20_000) {
                router.playlist(playlist); router.playlistTracks(playlist, PageRequest(limit = 30))
                router.album(album); router.albumTracks(album, PageRequest(limit = 30))
            }
            val warmMs = android.os.SystemClock.elapsedRealtime() - warmStart
            val warmRequests = playlistRequests.get() + albumRequests.get() + windows.size - beforeWarm
            instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                "Detail first pages baseline=$baseline playlistMs=$playlistMs playlistHttp=${playlistRequests.get()} metadataRows=$metadataRows detailBatches=$windows albumMs=$albumMs albumHttp=$albumReads warmMs=$warmMs warmHttp=$warmRequests completeIds=${ids.size}\n") })
            if (!baseline) {
                assertEquals(1, playlistRequests.get())
                assertEquals("First-page metadata must be bounded to the visible page", listOf(30), metadataRows.toList())
                assertTrue("First page must not hydrate unrelated song metadata", windows.all { it <= 30 })
                assertEquals("Album detail and songs must share one endpoint read", 1, albumReads)
                assertEquals("Reopening cached details must not fetch metadata", 0, warmRequests)
            }
        } finally {
            field.set(policy, original)
            assertTrue("Read-only detail requests must preserve source configuration", configuration == provider.platformConfiguration())
            assertTrue("Read-only detail requests must preserve account revision", revision == provider.accountRevision.value)
        }
    }

    @Test fun preExtractionMobileServerShuffleReturnsOrder(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("neteasePreExtractionMobileLive") == "true")
        val playlist = requireNotNull(args.getString("neteaseServerPlaylistId")).toLong()
        val seed = requireNotNull(args.getString("neteaseServerSongId")).toLong()
        require(playlist > 0 && seed > 0)
        val sessionFile = args.getString("neteaseBaselineSessionFile")?.let { basename ->
            require(basename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))) { "Invalid transient session basename" }
            File(context.cacheDir, basename).also {
                require(it.canonicalFile.parentFile == context.cacheDir.canonicalFile && it.isFile && it.length() in 1..16_384L) {
                    "Invalid transient session file"
                }
            }
        }
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        withTimeout(20_000) { router.registry.awaitInitialized() }
        val provider = router.registry.requireProvider(ProviderIds.NETEASE) as ScriptMusicProvider
        val config = provider.platformConfiguration().toMap()
        val revision = provider.accountRevision.first()
        fun status(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
        try {
            require(config["sessionType"].equals("mobile", true)) { "This exact baseline requires the existing mobile session" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val raw = sessionFile?.readText()?.trim() ?: config["cookie"].orEmpty()
                require(raw.isNotBlank() && raw.all { it.code in 32..126 }) { "Invalid transient session content" }
                val cookie = if (Regex("(^|;\\s*)MUSIC_U=").containsMatchIn(raw)) raw else "MUSIC_U=$raw"
                val supplied = cookie.split(';').mapNotNull { part ->
                    val at = part.indexOf('=')
                    if (at > 0) part.substring(0, at).trim() to part.substring(at + 1).trim() else null
                }.toMap()
                val device = config["deviceId"].orEmpty()
                val musicU = supplied["MUSIC_U"].orEmpty()
                require(device.isNotBlank() && musicU.isNotBlank()) { "Existing mobile identity is required" }
                val metrics = context.resources.displayMetrics
                val cookies = linkedMapOf("os" to "android", "appver" to "9.5.70",
                    "osver" to android.os.Build.VERSION.RELEASE, "channel" to "xiaomi", "versioncode" to "9005070",
                    "mobilename" to android.os.Build.MODEL.replace(" ", "+"), "buildver" to "260818213343",
                    "resolution" to "${metrics.widthPixels}x${metrics.heightPixels}", "deviceId" to device)
                config["sDeviceId"]?.takeIf(String::isNotBlank)?.let { cookies["sDeviceId"] = it }
                cookies.putAll(linkedMapOf("brand" to android.os.Build.BRAND, "packageType" to "release", "ntes_kaola_ad" to "1",
                    "_ntes_nuid" to createRandomKey(32), "WNMCID" to getWNMCID(), "WEVNSM" to "1.0.0",
                    "__csrf" to (if (sessionFile == null) config["csrf"].orEmpty() else supplied["__csrf"].orEmpty()).ifBlank { UUID.randomUUID().toString().replace("-", "") }))
                config["ursAppId"]?.takeIf(String::isNotBlank)?.let { cookies["URS_APPID"] = it }
                cookies["EVNSM"] = "1.0.0"; cookies["MUSIC_U"] = musicU
                (if (sessionFile == null) config["musicA"] else supplied["MUSIC_A"])?.takeIf(String::isNotBlank)?.let { cookies["MUSIC_A"] = it }
                val payload = JsonObject().apply {
                    addProperty("playlistId", playlist); addProperty("songId", seed); addProperty("alg", "")
                    addProperty("header", "{}"); addProperty("e_r", false)
                }
                val path = "/api/playlist/random/list/get"
                val request = Request.Builder().url("https://interface.music.163.com/eapi/playlist/random/list/get")
                    .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    .header("User-Agent", "NeteaseMusic/9.5.70.260818213343(9005070);Dalvik/2.1.0 (Linux; U; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL} Build/${android.os.Build.ID})")
                mapOf("x-appver" to "9.5.70", "x-buildver" to "260818213343", "x-deviceId" to device,
                    "x-os" to "android", "x-osver" to android.os.Build.VERSION.RELEASE, "x-music-u" to musicU,
                    "x-mam-custommark" to "okhttp", "X-Client-Enc-State" to "ENCRYPTED", "x-channel" to "xiaomi",
                    "x-mobilename" to android.os.Build.MODEL).forEach(request::header)
                config["sDeviceId"]?.takeIf(String::isNotBlank)?.let { request.header("x-sDeviceId", it) }
                val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
                client.newCall(request.post(FormBody.Builder().add("params", encryptEApi(path, payload.toString()).params).build()).build()).execute().use { response ->
                    val bytes = requireNotNull(response.body).use {
                        check(it.contentLength() <= 4L * 1024 * 1024 && !it.source().request(4L * 1024 * 1024 + 1)) { "Response too large" }
                        it.bytes()
                    }
                    val result = JsonParser.parseString(NeteaseWireCodec.decodeResponseBytes(bytes, "eapi").toString(Charsets.UTF_8)).asJsonObject
                    val code = result.get("code")?.asInt
                    val ids = result.getAsJsonObject("data")?.getAsJsonArray("songIds")
                    val data = result.getAsJsonObject("data")
                    val songData = data?.get("songData")?.takeIf { it.isJsonArray }?.asJsonArray
                    val privileges = data?.get("privileges")?.takeIf { it.isJsonArray }?.asJsonArray
                    status("Pre-extraction mobile server http=${response.code} code=$code songIdsCount=${ids?.size() ?: -1} songDataCount=${songData?.size() ?: -1} privilegesCount=${privileges?.size() ?: -1} host=interface numericIds=true e_r=false algEmpty=true")
                    val order = if (ids != null && ids.size() > 0) ids.toList()
                        else if (privileges != null) songData?.toList().orEmpty() else emptyList()
                    assertTrue("The exact pre-extraction server request must return a real order", response.isSuccessful && code == 200 &&
                        order.isNotEmpty() && order.all { value -> (if (value.isJsonObject) value.asJsonObject.get("id") else value).asLong > 0 })
                }
            }
        } finally {
            assertTrue("Transient baseline session must be removed", sessionFile == null || !sessionFile.exists() || sessionFile.delete())
            assertTrue("The baseline replay must preserve source configuration", config == provider.platformConfiguration())
            assertTrue("The baseline replay must preserve source account revision", revision == provider.accountRevision.first())
        }
    }

    @Test fun installedNeteaseServerShuffleReturnsARealServerOrder(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("neteaseServerShuffleLive") == "true")
        val playlistId = requireNotNull(arguments.getString("neteaseServerPlaylistId"))
        val songId = requireNotNull(arguments.getString("neteaseServerSongId"))
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        withTimeout(20_000) { router.registry.awaitInitialized() }
        val provider = router.registry.requireProvider(ProviderIds.NETEASE) as ScriptMusicProvider
        val account = withTimeout(20_000) { (provider as AccountCapability).account() }
        assertNotNull("The server-order check requires an authenticated account", account)
        val configuration = provider.platformConfiguration().toMap()
        val revision = provider.accountRevision.first()
        val networkField = ScriptMusicProvider::class.java.getDeclaredField("network").apply { isAccessible = true }
        val policy = networkField.get(provider) as ScriptNetworkPolicy
        val clientField = ScriptNetworkPolicy::class.java.getDeclaredField("client").apply { isAccessible = true }
        val original = clientField.get(policy) as OkHttpClient
        val requests = AtomicInteger()
        try {
            clientField.set(policy, original.newBuilder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath.endsWith("/playlist/random/list/get")) {
                    requests.incrementAndGet()
                    val root = runCatching { JsonParser.parseString(response.peekBody(4L * 1024 * 1024).string()).asJsonObject }.getOrNull()
                    val data = root?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    fun count(key: String) = data?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: -1
                    val code = root?.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "Server shuffle http=${response.code} code=$code songIdsCount=${count("songIds")} songDataCount=${count("songData")} privilegesCount=${count("privileges")}\n")
                    })
                }
                response
            }.build())
            val result = withTimeout(60_000) {
                SourceFeatureExecutor(router).execute(ProviderIds.NETEASE, "discovery", "cloudShuffle", JsonObject().apply {
                    addProperty("playlistId", playlistId); addProperty("songId", songId)
                }, expectedProvider = provider, expectedAccountRevision = revision).asJsonObject
            }
            val order = result.getAsJsonArray("order")
            assertTrue("Server shuffle must perform a real observed HTTP request", requests.get() > 0)
            assertTrue("A server-only feature must return a real order", order != null && order.size() > 0 &&
                order.all { it.isJsonPrimitive && it.asString.matches(Regex("[1-9][0-9]{0,18}")) })
        } finally {
            clientField.set(policy, original)
            assertSame("The server check must preserve the source instance", provider, router.registry.requireProvider(ProviderIds.NETEASE))
            assertEquals("The server check must preserve source configuration", configuration, provider.platformConfiguration())
            assertEquals("The server check must preserve the source account", revision, provider.accountRevision.first())
        }
    }

    @Test fun installedNeteaseShuffleIncludesTheCompleteCollectionAndKeepsAccount(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("neteaseShuffleLive") == "true")
        instrumentation.runOnMainSync {
            val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        router.registry.awaitInitialized()
        val provider = router.registry.requireProvider(ProviderIds.NETEASE)
        val account = withTimeout(20_000) { (provider as AccountCapability).account() }
        assertNotNull("The live shuffle check requires an authenticated account", account)
        val revision = (provider as AccountRevisionCapability).accountRevision.first()
        val executor = SourceFeatureExecutor(router)
        try {
            withTimeout(120_000) {
                val playlistId = arguments.getString("neteaseShufflePlaylistId")
                val first = if (playlistId != null) router.playlistTracks(
                    PlaylistRef(ProviderIds.NETEASE, playlistId), PageRequest(limit = 30)).items
                else router.library(ProviderIds.NETEASE, LibraryCollection.LIKED_TRACKS,
                    PageRequest(limit = 30)).items.mapNotNull { (it as? SourceItem.TrackItem)?.track }
                assertTrue("The live shuffle check requires multiple songs", first.size >= 2)
                val seed = first.first().ref.trackId
                val started = android.os.SystemClock.elapsedRealtime()
                val identities = requireNotNull((provider as CatalogIndexCapability).completeTrackIds(
                    playlistId.orEmpty(), if (playlistId == null) LibraryCollection.LIKED_TRACKS else null)).ids
                val identityMs = android.os.SystemClock.elapsedRealtime() - started
                val canonical = identities.map { it.trackId }
                assertEquals("Full shuffle identities must stay unique", canonical.size, canonical.toSet().size)
                assertTrue("Full identities must retain the visible page", canonical.containsAll(first.map { it.ref.trackId }))
                if (playlistId != null) assertTrue("The large-playlist check must include songs beyond page one",
                    canonical.size > first.size)
                val policyStarted = android.os.SystemClock.elapsedRealtime()
                val policy = executor.execute(ProviderIds.NETEASE, "discovery", "shufflePolicy",
                    expectedProvider = provider, expectedAccountRevision = revision).asJsonObject
                fun policyResponse(key: String) = policy.get(key)?.takeUnless { it.isJsonNull }?.asJsonObject
                val state = OfficialMobileShuffle.resolve(policyResponse("parent"), policyResponse("child"))
                val policyMs = android.os.SystemClock.elapsedRealtime() - policyStarted
                val shuffleStarted = android.os.SystemClock.elapsedRealtime()
                val order = OfficialMobileShuffle.shuffle(canonical, seed, state)
                val shuffleMs = android.os.SystemClock.elapsedRealtime() - shuffleStarted
                assertEquals("Shuffle must return the complete identity set", canonical.toSet(), order.toSet())
                assertEquals("Shuffle must not duplicate identities", canonical.size, order.size)
                assertEquals("The current song must remain first", seed, order.first())
                assertNotEquals("Shuffle must change the canonical order", canonical, order)
                assertNotEquals("Another host shuffle must produce a fresh order", order,
                    OfficialMobileShuffle.shuffle(canonical, seed, state))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "Shuffle count=${order.size} identityMs=$identityMs policyMs=$policyMs nativeShuffleMs=$shuffleMs canonicalHead=${canonical.take(10)} shuffledHead=${order.take(10)}\n")
                })
            }
        } finally {
            assertSame("Shuffle must retain the source instance", provider,
                router.registry.requireProvider(ProviderIds.NETEASE))
            assertEquals("Shuffle must retain the source account", revision, provider.accountRevision.first())
            assertEquals("Shuffle must retain the account identity", account?.ref,
                withTimeout(20_000) { (provider as AccountCapability).account()?.ref })
        }
    }

    @Test fun installedRemovableNeteaseSourceCanSearchReadAndResolveWithoutChangingAccount(): Unit = runBlocking {
        val entryPoint = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java)
        val router = entryPoint.sourceRouter()
        router.registry.awaitInitialized()
        val provider = router.registry.requireProvider(ProviderIds.NETEASE)
        assertFalse("NetEase must be an independently removable source", provider.isBuiltIn)
        assertTrue(provider.instanceId.value.startsWith("script:"))
        val sourceAccountBefore = (provider as? AccountRevisionCapability)?.accountRevision?.first()
        val accountBefore = context.dataStore.data.first().let { it[CookieKey] to it[UserIdKey] }
        try {
            withTimeout(45_000) {
                val results = router.search(ProviderIds.NETEASE, "晴天", PageRequest(limit = 10)).items
                assertTrue("Installed NetEase search must return real catalog results", results.isNotEmpty())
                var audio: AudioResource? = null
                for (result in results) {
                    assertEquals(ProviderIds.NETEASE, result.ref.providerId)
                    val track = router.getTrack(result.ref) ?: continue
                    assertEquals(result.ref, track.ref)
                    assertTrue(track.title.isNotBlank())
                    try {
                        if (InstrumentationRegistry.getArguments().getString("neteaseArtworkDiagnostics") == "true") {
                            recordUndeclaredPlaybackHost(provider as ScriptMusicProvider, track.ref, "standard", sourceAccountBefore)
                        }
                        audio = (provider as PlaybackCapability).resolve(track.ref, "standard")
                        break
                    } catch (error: SourceException) {
                        // Copyright restrictions on one result do not turn into a fake successful source.
                        if (error.code !in setOf(SourceErrorCode.UNAVAILABLE, SourceErrorCode.NOT_FOUND)) throw error
                    }
                }
                assertTrue("Installed NetEase catalog results must contain at least one full playable source", audio != null)
                val resource = checkNotNull(audio)
                assertTrue("Resolved script audio must retain the host permission-enforcing transport", resource.uri.startsWith("mei-source://"))
                assertTrue(ScriptAudioResources.isRegistered(resource.uri))
                assertTrue((provider as ResourceValidationCapability).acceptsResource(resource))
                assertTrue(resource.headers.isEmpty())
                assertTrue(resource.quality.isNotBlank())
                assertTrue(resource.expiresAtMs?.let { it > System.currentTimeMillis() } != false)
            }
        } finally {
            val accountAfter = context.dataStore.data.first().let { it[CookieKey] to it[UserIdKey] }
            assertTrue("Read-only source requests must retain the legacy account", accountBefore == accountAfter)
            assertTrue("Read-only source requests must retain its private account", sourceAccountBefore ==
                (provider as? AccountRevisionCapability)?.accountRevision?.first())
        }
    }

    private suspend fun recordUndeclaredPlaybackHost(provider: ScriptMusicProvider, ref: TrackRef,
        quality: String, accountRevision: String?) {
        // Match playbackResponse's raw IDs, quality and bounded continuation before URI registration.
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(3) {
            val result = (provider as SourceCoreEffectCapability).invokeCoreOperation("playback.resolve", JsonObject().apply {
                addProperty("trackId", ref.trackId)
                addProperty("quality", quality)
                cursor?.let { addProperty("cursor", it) }
            }, accountRevision).takeIf { it.isJsonObject }?.asJsonObject ?: return
            val uri = result.get("uri")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            if (uri != null) {
                val host = runCatching { java.net.URI(uri).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
                if (host != null && host.matches(Regex("[a-z0-9.-]{1,253}")) &&
                    host !in provider.manifest.permissions.network) {
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "NetEase undeclared audio host=$host\n")
                    })
                }
                return
            }
            val next = result.get("nextCursor")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return
            if (next.length !in 1..128 || !cursors.add(next)) return
            cursor = next
        }
    }

    @Test fun v18MigrationKeepsRelationshipsFilesAndLegacyIdentities(): Unit = runBlocking {
        val name = "source-migration-${UUID.randomUUID()}"
        val offline = File.createTempFile("source-migration-", ".audio", context.cacheDir)
        val bytes = byteArrayOf(1, 3, 5, 7)
        offline.writeBytes(bytes)
        var migrated: AppDatabase? = null
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val schema = instrumentation.context.assets.open("source-compatibility/v18.sql")
                    .bufferedReader().use { it.readText() }
                schema.lineSequence().filter { it.isNotBlank() && !it.startsWith("--") }
                    .forEach { old.execSQL(it) }
                old.setForeignKeyConstraintsEnabled(true)
                for ((id, type) in listOf("42" to "DOWNLOAD", "local_42" to "LOCAL", "-77" to "LOCAL", "-99" to "STREAM")) {
                    old.execSQL("""INSERT INTO song(id,title,artist,album,cover,duration,path,sourceType,fileSize,addedAt,updatedAt)
                        VALUES(?, 'Title', '["Artist"]', 'Album', '', 180000, ?, ?, 4, 1, 2)""",
                        arrayOf(id, offline.absolutePath, type))
                }
                old.execSQL("""INSERT INTO playlist(id,title,cover,author,authorName,authorAvatar,count,type,createdAt,updatedAt,playCount,lastPlayTime,localPlayCount)
                    VALUES('playlist_1','Playlist','','1','Author','',1,'USER',1,2,3,4,5)""")
                old.execSQL("INSERT INTO playlist_song_cross_ref VALUES('playlist_1','42',7,8)")
                old.execSQL("INSERT INTO playback_history VALUES(11,'42',123)")
                old.execSQL("INSERT INTO playback_count VALUES('42',9,456)")
                old.execSQL("INSERT INTO `like` VALUES('42')")
                old.execSQL("INSERT INTO qqSong VALUES('42','qq-lyric-id','Title','Artist','Album',180000)")
                old.execSQL("INSERT INTO cached_lyric VALUES('42','[00:00]Lyric',NULL,0,0,'Netease','LRC',99)")
                old.execSQL("""INSERT INTO download_task VALUES('42','https://audio.example/old','old.flac','flac','COMPLETED',100,'Title','Artist','Album','','lossless','request-1',1,2)""")
                old.execSQL("INSERT INTO albums VALUES(100,'Album','',1,1)")
                old.execSQL("INSERT INTO artists VALUES(200,'Artist',NULL)")
                old.execSQL("INSERT INTO album_artist_cross_ref VALUES(100,200)")
                old.execSQL("INSERT INTO color VALUES('cover',123)")
                old.version = 18
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_18_19).build().also { migrated = it }
            val sqlite = db.openHelper.writableDatabase
            assertEquals(19, sqlite.version)
            val downloaded = checkNotNull(db.songDao().getById("42"))
            assertEquals("netease", downloaded.providerId)
            assertEquals("42", downloaded.providerTrackId)
            assertEquals(offline.absolutePath, downloaded.path)
            assertEquals(TrackRef("local", "local_42"), db.songDao().getById("local_42")!!.toMediaMetadata().trackRef)
            assertEquals(TrackRef("local", "local_77"), db.songDao().getById("-77")!!.toMediaMetadata().trackRef)
            assertEquals(TrackRef("netease", "-99"), db.songDao().getById("-99")!!.toMediaMetadata().trackRef)
            assertEquals(listOf("42"), db.playlistSongCrossRefDao().getSongIdsByPlaylist("playlist_1").first())
            assertEquals(5, db.playlistDao().getPlaylist("playlist_1")!!.localPlayCount)
            assertEquals("local", db.playlistDao().getPlaylist("playlist_1")!!.providerId)
            assertEquals("playlist_1", db.playlistDao().getPlaylist("playlist_1")!!.providerPlaylistId)
            assertEquals(9, db.downloadDao().playbackCount("42"))
            assertNotNull(db.likeDao().getLike("42"))
            assertEquals("qq-lyric-id", db.qqSongDao().getSong("42").first()!!.qid)
            assertEquals("[00:00]Lyric", db.cachedLyricDao().get("42").first()!!.content)
            val download = checkNotNull(db.downloadDao().getBySongId("42"))
            assertEquals("request-1", download.requestId)
            assertEquals("netease", download.actualAudioProviderId)
            assertNull(download.actualQuality)
            assertNull(download.resolvedTrackRef)
            assertEquals("lossless", download.quality)
            assertEquals(1, db.AlbumsDao().getAlbumWithArtists(100).artists.size)
            assertEquals(123, db.colorDao().getColor("cover")!!.color)
            assertEquals(11L, db.historyDao().getHistory().first().single().historyId)
            db.historyDao().addSongToHistory(downloaded.copy(title = "Refreshed"), playedAt = 789)
            db.songDao().insertSong(downloaded.copy(title = "Updated"))
            db.songDao().insertSongs(listOf(downloaded.copy(title = "Batch update")))
            assertEquals(2, db.historyDao().getHistory().first().size)
            assertEquals(offline.absolutePath, db.songDao().getById("42")!!.path)
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            sqlite.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            assertArrayEquals(bytes, offline.readBytes())
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
            offline.delete()
        }
    }

    @Test fun oldAndNewSnapshotsRestoreThroughRealMedia3Items() {
        val gson = Gson()
        val persistence = PlaybackPersistence(context)
        val legacy = gson.fromJson("""{"schemaVersion":1,"items":[
            {"mediaId":"42","title":"Netease"},
            {"mediaId":"local_42","title":"Local","isLocal":true},
            {"mediaId":"-99","title":"Podcast","isPodcast":true}] }""", PlaybackSnapshot::class.java)
        val oldItems = persistence.restoreItems(legacy)
        assertEquals(listOf(TrackRef("netease", "42"), TrackRef("local", "local_42"), TrackRef("netease", "-99")),
            oldItems.map { it.metadata!!.trackRef })
        assertEquals(listOf("42", "local_42", TrackRefCodec.mediaId(TrackRef("netease", "-99"))), oldItems.map { it.mediaId })

        val ref = TrackRef("demo", "42:歌曲/音轨")
        val snapshot = PlaybackSnapshot(items = listOf(PlaybackItemSnapshot(
            mediaId = TrackRefCodec.mediaId(ref), title = "External", trackRef = TrackRefCodec.encode(ref),
            artists = listOf(PlaybackArtistSnapshot(name = "Artist", ref = ArtistRef("demo", "artist:string"))),
            albumTitle = "Album", albumRef = AlbumRef("demo", "album/string"),
            isrc = "TEST00000001", recordingVersion = "Live",
        )))
        val restored = persistence.restoreItems(gson.fromJson(gson.toJson(snapshot), PlaybackSnapshot::class.java)).single()
        assertEquals(ref, restored.metadata!!.trackRef)
        assertEquals("TEST00000001", restored.metadata!!.isrc)
        assertEquals("Live", restored.metadata!!.recordingVersion)
        assertEquals(0L, restored.metadata!!.id)
        assertEquals(0L, restored.metadata!!.artists.single().id)
        assertEquals(snapshot.items.single().albumRef, restored.metadata!!.album.ref)
        assertEquals(ref, TrackRefCodec.decode(restored.mediaId))
        assertEquals(TrackRefCodec.storageKey(ref), restored.localConfiguration!!.customCacheKey)
        var captured: PlaybackSnapshot? = null
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            try {
                player.setMediaItem(restored.metadata!!.toMediaItem())
                captured = persistence.capture(player, "Source compatibility", false)
            } finally { player.release() }
        }
        assertEquals(TrackRefCodec.encode(ref), captured!!.items.single().trackRef)
        assertEquals("TEST00000001", captured!!.items.single().isrc)
        assertEquals("Live", captured!!.items.single().recordingVersion)
        assertEquals(snapshot.items.single().albumRef, captured!!.items.single().albumRef)
        assertEquals(snapshot.items.single().artists.single().ref, captured!!.items.single().artists.single().ref)
    }
}
