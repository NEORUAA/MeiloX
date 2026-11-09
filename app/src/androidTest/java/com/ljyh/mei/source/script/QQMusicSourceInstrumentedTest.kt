package com.ljyh.mei.source.script

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.playback.CacheManager
import com.ljyh.mei.playback.DownloadSourceEntryPoint
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** QQ validation never changes the host account, browse preferences, or application queue. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class QQMusicSourceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as AppContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun shippedAdapterParsesCapturedResponsesInsideTheIsolatedRuntime(): Unit = runBlocking {
        assumeTrue("The bundled QuickJS engine must be available", com.ljyh.mei.source.script.ScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext).supported)
        val packageFile = File.createTempFile("qq-music-captured-", ".meisource", context.cacheDir)
        var prepared: PreparedPlugin? = null
        try {
            copyPackage(packageFile)
            // Inspection uses a disposable staging directory, never the installed user's plugin.
            val inspected = app.sourcePlugins.inspect(Uri.fromFile(packageFile)).also { prepared = it }
            assertReviewedApi2Contract(inspected.manifest)
            val legacy = inspected.manifest.copy(version = "0.1.0", apiVersion = 1,
                capabilities = API1_CAPABILITIES, permissions = PluginPermissions(API1_DOMAINS.toList(), storage = true),
                searchTypes = setOf("track"), collectionTypes = emptySet())
            expectRejectedContract { assertSameUpdateContract(legacy, inspected.manifest, reviewed = false) }
            assertSameUpdateContract(legacy, inspected.manifest, reviewed = true)
            val previousApi2 = inspected.manifest.copy(version = "0.3.0", permissions = inspected.manifest.permissions.copy(network = API2_PREVIOUS_DOMAINS.toList()))
            expectRejectedContract { assertSameUpdateContract(previousApi2, inspected.manifest, reviewed = false) }
            assertSameUpdateContract(previousApi2, inspected.manifest, reviewed = true)
            expectRejectedContract { assertSameUpdateContract(legacy, inspected.manifest.copy(
                permissions = inspected.manifest.permissions.copy(network = API2_DOMAINS.toList() + "unknown.example")), reviewed = true) }
            expectRejectedContract { assertSameUpdateContract(legacy, inspected.manifest.copy(
                capabilities = API2_CAPABILITIES + "playlist.reorderTracks"), reviewed = true) }
            expectRejectedContract { assertSameUpdateContract(legacy, inspected.manifest.copy(
                config = inspected.manifest.config + PluginConfigField("extra", "Unreviewed field")), reviewed = true) }
            val script = File(inspected.directory, inspected.manifest.entry).readText()
            val fixtures = JsonObject().apply {
                for (name in CAPTURED_FIXTURES) {
                    val response = instrumentation.context.assets.open("source-plugin/qq-music/captured-$name.json")
                        .bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
                    response.remove("_capture")
                    add(name, response)
                }
            }
            val wrapped = script + capturedResponseWrapper(fixtures)
            val runtime = ScriptRuntime(context)
            // This test covers metadata parsing; artwork has its own permission-enforced transport.
            val parser = ScriptContentParser(PROVIDER_ID, inspected.manifest.apiVersion,
                PlaylistEditAction.entries.filter { it.operation in inspected.manifest.capabilities }.toSet()) { url ->
                assertTrue("Captured artwork must use an explicitly reviewed QQ domain", Uri.parse(url).host in inspected.manifest.permissions.network)
                ""
            }
            suspend fun invoke(operation: String, params: JsonObject = JsonObject()): JsonObject = runtime.invoke(
                wrapped, inspected.manifest, operation, params, emptyMap(), emptyMap(),
                ScriptNetworkPolicy(emptyList()),
            ).result.asJsonObject
            suspend fun search(type: ContentType): SourcePage<SourceItem> {
                val result = invoke("catalog.search", pageParams().apply {
                    addProperty("query", if (type == ContentType.TRACK) "晴天" else "周杰伦")
                    addProperty("type", type.wireName)
                })
                assertEquals(listOf("search"), stages(result))
                return parser.page(result.getAsJsonObject("value"), PageRequest(limit = 3)) { parser.item(it, type) }
            }
            withTimeout(180_000) {
                val trackSearch = search(ContentType.TRACK)
                assertEquals(2, trackSearch.items.size)
                val first = (trackSearch.items.first() as SourceItem.TrackItem).track
                assertEquals(TrackRef(PROVIDER_ID, FIXTURE_TRACK), first.ref)
                assertEquals("晴天", first.title)
                assertEquals(269_000L, first.durationMs)
                assertEquals(ArtistRef(PROVIDER_ID, FIXTURE_ARTIST), first.artists.first().ref)
                assertEquals(AlbumRef(PROVIDER_ID, "000MkMni19ClKG"), first.album.ref)
                val artistSearch = search(ContentType.ARTIST)
                val albumSearch = search(ContentType.ALBUM)
                val playlistSearch = search(ContentType.PLAYLIST)
                assertTrue(artistSearch.items.all { it is SourceItem.ArtistItem })
                assertTrue(albumSearch.items.all { it is SourceItem.AlbumItem })
                assertTrue(playlistSearch.items.all { it is SourceItem.PlaylistItem })
                assertEquals(2, artistSearch.items.size)
                assertEquals(2, albumSearch.items.size)
                assertEquals(2, playlistSearch.items.size)

                val detail = invoke("catalog.track", idParams("trackId", FIXTURE_TRACK))
                assertEquals(listOf("detail"), stages(detail))
                assertEquals(first.ref, parser.track(detail.getAsJsonObject("value")).ref)
                val lyrics = invoke("lyrics.get", idParams("trackId", FIXTURE_TRACK))
                assertTrue("lyrics" in stages(lyrics))
                val lyricText = lyrics.getAsJsonObject("value").get("text").asString
                assertTrue(lyricText.contains("[ti:晴天]"))
                assertTrue(lyricText.contains("[ar:周杰伦]"))

                val home = parser.feed(invoke("feed.home", pageParams()).getAsJsonObject("value"), PageRequest(limit = 3))
                assertEquals(listOf("recommend", "songs", "albums"), home.sections.map { it.id })
                assertEquals(com.ljyh.mei.source.api.SourceFeedLayout.HERO, home.sections.first().layout)
                assertTrue(home.categories.isEmpty())
                assertTrue(home.sections.all { it.items.isNotEmpty() })
                val playlist = home.sections.first().items.first() as SourceItem.PlaylistItem
                val playlistInfo = parser.playlist(invoke("playlist.get", idParams("playlistId", playlist.ref.playlistId)).getAsJsonObject("value"))
                assertEquals(playlist.ref, playlistInfo.ref)
                val playlistTracks = parser.page(invoke("playlist.tracks", pageParams().apply {
                    addProperty("playlistId", playlist.ref.playlistId)
                }).getAsJsonObject("value"), PageRequest(limit = 3), parser::track)
                assertEquals(3, playlistTracks.items.size)
                assertEquals("002eDlpu4U223F", playlistTracks.items.first().ref.trackId)
                assertTrue(playlistTracks.hasMore)
                assertEquals("3", playlistTracks.nextCursor)

                val chartFeed = parser.feed(invoke("feed.discover", pageParams().apply {
                    addProperty("categoryId", "charts")
                }).getAsJsonObject("value"), PageRequest(limit = 3))
                assertEquals(listOf("charts"), chartFeed.sections.map { it.id })
                val charts = chartFeed.sections.single().items.first() as SourceItem.PlaylistItem
                assertEquals("chart:62", charts.ref.playlistId)
                assertEquals(charts.ref, parser.playlist(invoke("playlist.get", idParams("playlistId", charts.ref.playlistId)).getAsJsonObject("value")).ref)
                assertEquals(3, parser.page(invoke("playlist.tracks", pageParams().apply {
                    addProperty("playlistId", charts.ref.playlistId)
                }).getAsJsonObject("value"), PageRequest(limit = 3), parser::track).items.size)
                val discover = parser.feed(invoke("feed.discover", pageParams().apply {
                    addProperty("categoryId", "3056")
                }).getAsJsonObject("value"), PageRequest(limit = 3))
                assertEquals(listOf("category"), discover.sections.map { it.id })
                assertTrue(discover.sections.single().items.all { it is SourceItem.PlaylistItem })

                val album = home.sections.first { it.id == "albums" }.items.first() as SourceItem.AlbumItem
                assertEquals(album.ref, parser.album(invoke("album.get", idParams("albumId", album.ref.albumId)).getAsJsonObject("value")).ref)
                assertEquals(2, parser.page(invoke("album.tracks", pageParams().apply {
                    addProperty("albumId", album.ref.albumId)
                }).getAsJsonObject("value"), PageRequest(limit = 3), parser::track).items.size)
                val artist = artistSearch.items.first() as SourceItem.ArtistItem
                assertEquals(artist.ref, parser.artist(invoke("artist.get", idParams("artistId", artist.ref.artistId)).getAsJsonObject("value")).ref)
                assertEquals(3, parser.page(invoke("artist.tracks", pageParams().apply {
                    addProperty("artistId", artist.ref.artistId)
                }).getAsJsonObject("value"), PageRequest(limit = 3), parser::track).items.size)
                assertEquals(3, parser.page(invoke("artist.albums", pageParams().apply {
                    addProperty("artistId", artist.ref.artistId)
                }).getAsJsonObject("value"), PageRequest(limit = 3), parser::album).items.size)

                assertTrue(invoke("account.get").get("value").isJsonNull)
                for (collection in LibraryCollection.entries) {
                    expectSourceError(SourceErrorCode.AUTH_REQUIRED) { invoke(collection.operation, pageParams()) }
                }
                expectSourceError(SourceErrorCode.AUTH_REQUIRED) {
                    invoke("playback.resolve", idParams("trackId", FIXTURE_TRACK).apply { addProperty("quality", "standard") })
                }
            }
        } finally {
            prepared?.directory?.deleteRecursively()
            packageFile.delete()
        }
    }

    @Test
    fun installedQQSourceSupportsDeclaredReadOnlyCapabilities(): Unit = runBlocking {
        assumeTrue("Enable real QQ requests explicitly with qqMusicLive=true", arguments.getString("qqMusicLive") == "true")
        assumeTrue("The bundled QuickJS engine must be available", com.ljyh.mei.source.script.ScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext).supported)
        val manager = app.sourcePlugins
        manager.loadInstalled()
        val existing = manager.installed.value.firstOrNull { it.manifest.id == PLUGIN_ID }
        val updateExisting = existing != null && arguments.getString("qqMusicUpdate") == "true"
        val configurationBefore = existing?.let { manager.configuration(PLUGIN_ID) }
        val sessionFile = File(context.cacheDir, "qq-music-session.json").takeIf { arguments.getString("qqMusicSession") == "true" }
        var expectedConfiguration = configurationBefore ?: emptyMap()
        val accountBefore = accountState()
        val packageFile = File.createTempFile("qq-music-source-", ".meisource", context.cacheDir)
        var installedByTest = false
        try {
            val installed = if (existing == null || updateExisting) {
                copyPackage(packageFile)
                val prepared = manager.inspect(Uri.fromFile(packageFile))
                try {
                    assertReviewedApi2Contract(prepared.manifest)
                    if (existing != null) assertSameUpdateContract(existing.manifest, prepared.manifest)
                    manager.install(prepared, approved = true).also {
                        installedByTest = existing == null
                        if (existing != null) {
                            assertEquals("Updates must preserve the user's enabled state", existing.enabled, it.enabled)
                            assertTrue("Updates must retain saved QQ configuration", configurationBefore == manager.configuration(PLUGIN_ID))
                        }
                    }
                } finally { prepared.directory.deleteRecursively() }
            } else existing
            assertEquals("Install the reviewed API2 package before live API2 validation", 2, installed.manifest.apiVersion)
            assertReviewedApi2Contract(installed.manifest)
            if (sessionFile != null) {
                val session = try {
                    assertTrue("The private session input file is missing", sessionFile.isFile)
                    JsonParser.parseString(sessionFile.readText()).asJsonObject
                } finally { sessionFile.delete() }
                assertTrue("Session input must contain only QQ configuration fields", session.keySet().all { it in setOf("cookie", "uin") })
                assertTrue("Session input must contain strings", session.entrySet().all { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString })
                val values = session.entrySet().associate { (key, value) -> key to value.asString }
                assertTrue("The private QQ session must contain a Cookie", !values["cookie"].isNullOrBlank())
                manager.configure(PLUGIN_ID, values)
                expectedConfiguration = values
                assertTrue("Session configuration must be saved privately", expectedConfiguration == manager.configuration(PLUGIN_ID))
            }
            val requireAccount = arguments.getString("qqMusicAccount") == "true"
            val requirePlayback = arguments.getString("qqMusicPlayback") == "true"
            if (existing != null && !requireAccount && !requirePlayback) {
                assumeTrue("The existing source's enabled state was preserved; it is not ready", installed.status == SourceStatus.READY)
            }
            assertEquals("The requested QQ source must be ready", SourceStatus.READY, installed.status)
            val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
            val provider = router.registry.requireProvider(PROVIDER_ID)
            assertEquals(installed.manifest.sourceCapabilities, provider.capabilities)
            withTimeout(240_000) {
                val tracks = verifyLiveCatalog(router)
                verifyLiveAccountAndLibrary(router, requireAccount, manager.configuration(PLUGIN_ID)["cookie"].isNullOrBlank())
                // Resolving URLs and playing audio require their own explicit live opt-in.
                if (requirePlayback) verifyFullQQPlayback(provider, tracks)
            }
        } finally {
            if (installedByTest && arguments.getString("keepQqPlugin") != "true") manager.uninstall(PLUGIN_ID)
            packageFile.delete()
            sessionFile?.delete()
            if (manager.installed.value.any { it.manifest.id == PLUGIN_ID }) {
                assertTrue("QQ validation must retain saved configuration", expectedConfiguration == manager.configuration(PLUGIN_ID))
            }
            assertTrue("QQ validation must retain the native account", accountBefore == accountState())
        }
    }

    @Test
    fun installedQQSessionSupportsPrivateLibrary(): Unit = runBlocking {
        assumeTrue(arguments.getString("qqMusicLive") == "true" && arguments.getString("qqMusicAccount") == "true")
        withReadOnlyInstalledQQ { router ->
            withTimeout(90_000) { verifyLiveAccountAndLibrary(router, required = true, noSession = false) }
        }
    }

    @Test
    fun installedQQSessionPlaysAnAuthorizedFullResource(): Unit = runBlocking {
        assumeTrue(arguments.getString("qqMusicLive") == "true" && arguments.getString("qqMusicPlayback") == "true")
        withReadOnlyInstalledQQ { router ->
            withTimeout(90_000) {
                val tracks = router.search(PROVIDER_ID, arguments.getString("qqMusicQuery") ?: "城南花已开",
                    ContentType.TRACK, PageRequest(limit = 5)).items.filterIsInstance<SourceItem.TrackItem>().map { it.track }
                assertTrue("QQ track search must supply playback candidates", tracks.isNotEmpty())
                verifyFullQQPlayback(router.registry.requireProvider(PROVIDER_ID), tracks)
            }
        }
    }

    private suspend fun withReadOnlyInstalledQQ(block: suspend (SourceRouter) -> Unit) {
        val manager = app.sourcePlugins
        manager.loadInstalled()
        val installed = checkNotNull(manager.installed.value.firstOrNull { it.manifest.id == PLUGIN_ID })
        assertReviewedApi2Contract(installed.manifest)
        assertEquals(SourceStatus.READY, installed.status)
        val nativeBefore = accountState()
        val configBefore = manager.configuration(PLUGIN_ID)
        try { block(EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()) }
        finally {
            assertTrue("Read-only validation must retain the native account", nativeBefore == accountState())
            assertTrue("Read-only validation must retain QQ configuration", configBefore == manager.configuration(PLUGIN_ID))
        }
    }

    private suspend fun verifyFullQQPlayback(provider: MusicProvider, tracks: List<Track>) {
        progress("playback.resolve")
        var resource: AudioResource? = null
        var expectedDurationMs = 0L
        for (track in tracks.take(5)) {
            try { resource = (provider as PlaybackCapability).resolve(track.ref, "standard"); expectedDurationMs = track.durationMs; break }
            catch (error: SourceException) {
                if (error.code !in setOf(SourceErrorCode.UNAVAILABLE, SourceErrorCode.NOT_FOUND)) throw error
            }
        }
        val audio = checkNotNull(resource) { "QQ playback must grant a full resource; refusal or a trial is not success" }
        assertTrue("Script audio must use the permission-enforcing host transport", audio.uri.startsWith("mei-source://"))
        assertTrue(audio.headers.isEmpty())
        assertEquals(CachePolicy.NO_STORE, audio.cachePolicy)
        assertTrue(audio.quality.isNotBlank())
        assertTrue("Playback candidates must provide their full track duration", expectedDurationMs > 0)
        verifyIndependentPlayback(audio, expectedDurationMs)
    }

    /** Explicit structural diagnostics, separate from account/playback acceptance. */
    @Test
    fun diagnoseConfiguredSessionWithoutExposingValues(): Unit = runBlocking {
        assumeTrue(arguments.getString("qqMusicSessionDiagnostics") == "true")
        val manager = app.sourcePlugins
        manager.loadInstalled()
        val configuration = manager.configuration(PLUGIN_ID)
        val cookieNames = configuration["cookie"].orEmpty().split(';').map { it.substringBefore('=').trim() }.toSet()
        progress(JsonObject().apply {
            for (name in listOf("uin", "wxuin", "qqmusic_key", "qm_keyst", "skey", "p_skey")) addProperty("has_$name", name in cookieNames)
        }.toString())
        val packageFile = File.createTempFile("qq-session-shape-", ".meisource", context.cacheDir)
        var prepared: PreparedPlugin? = null
        try {
            copyPackage(packageFile)
            val inspected = manager.inspect(Uri.fromFile(packageFile)).also { prepared = it }
            assertReviewedApi2Contract(inspected.manifest)
            val network = ScriptNetworkPolicy(inspected.manifest.permissions.network)
            val script = File(inspected.directory, inspected.manifest.entry).readText()
            try {
                ScriptRuntime(context).invoke(script, inspected.manifest, "account.get", JsonObject(), configuration, emptyMap(),
                    request = { request -> network.request(request).also { response ->
                        val body = response.get("body")?.asString.orEmpty()
                        val payload = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
                        val data = payload?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        val creator = data?.get("creator")?.takeIf { it.isJsonObject }?.asJsonObject
                        val expectedUin = Uri.parse(request.get("url").asString).getQueryParameter("loginUin")
                        val responseUin = creator?.get("uin")?.takeIf { it.isJsonPrimitive }?.asString
                        fun nonempty(value: com.google.gson.JsonElement?) = value?.takeUnless { it.isJsonNull }
                            ?.takeIf { it.isJsonPrimitive }?.asString?.isNotEmpty() == true
                        progress(JsonObject().apply {
                            addProperty("httpStatus", response.get("status")?.asInt)
                            addProperty("bodyChars", body.length)
                            addProperty("jsonObject", payload != null)
                            addProperty("topCode", payload?.get("code")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull())
                            addProperty("topCodeIsNumber", payload?.get("code")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isNumber == true)
                            addProperty("hasDataObject", data != null)
                            addProperty("hasCreatorObject", creator != null)
                            addProperty("creatorUinPresent", nonempty(creator?.get("uin")))
                            addProperty("creatorUinIsNumber", creator?.get("uin")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isNumber == true)
                            addProperty("creatorUinIsString", creator?.get("uin")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isString == true)
                            addProperty("creatorUinPositive", responseUin?.matches(Regex("[1-9][0-9]*")) == true)
                            addProperty("creatorUinMatchesExpected", expectedUin != null && expectedUin == responseUin)
                            addProperty("creatorNickPresent", nonempty(creator?.get("nick")))
                            addProperty("creatorNickIsString", creator?.get("nick")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isString == true)
                            addProperty("creatorEncryptedUinPresent", nonempty(creator?.get("encrypt_uin")))
                            addProperty("hasMusicArray", data?.get("mymusic")?.isJsonArray == true)
                        }.toString())
                    } })
                progress("sessionDiagnosticResult=parsed")
            } catch (error: SourceException) { progress("sessionDiagnosticResult=${error.code}") }
        } finally {
            prepared?.directory?.deleteRecursively()
            packageFile.delete()
        }
    }

    @Test
    fun diagnosePlaybackHostsWithoutExposingSignedUrls(): Unit = runBlocking {
        assumeTrue(arguments.getString("qqMusicPlaybackDiagnostics") == "true")
        val manager = app.sourcePlugins
        manager.loadInstalled()
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        val track = router.search(PROVIDER_ID, arguments.getString("qqMusicQuery") ?: "城南花已开",
            ContentType.TRACK, PageRequest(limit = 1)).items.filterIsInstance<SourceItem.TrackItem>().first().track
        val packageFile = File.createTempFile("qq-host-shape-", ".meisource", context.cacheDir)
        var prepared: PreparedPlugin? = null
        try {
            copyPackage(packageFile)
            val inspected = manager.inspect(Uri.fromFile(packageFile)).also { prepared = it }
            assertReviewedApi2Contract(inspected.manifest)
            val network = ScriptNetworkPolicy(inspected.manifest.permissions.network)
            try {
                ScriptRuntime(context).invoke(File(inspected.directory, inspected.manifest.entry).readText(), inspected.manifest,
                    "playback.resolve", idParams("trackId", track.ref.trackId).apply { addProperty("quality", "standard") },
                    manager.configuration(PLUGIN_ID), emptyMap(), request = { request -> network.request(request).also { response ->
                        val payload = decodeQQDiagnosticPayload(response)
                        val entry = payload?.get("playback")?.takeIf { it.isJsonObject }?.asJsonObject
                        val data = entry?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        if (data != null) progress(JsonObject().apply {
                            fun hosts(values: List<String>) = Gson().toJsonTree(values.mapNotNull { value ->
                                Uri.parse(value).host?.lowercase()?.takeIf { host ->
                                    host.length <= 253 && host.endsWith(".qqmusic.qq.com") &&
                                        host.matches(Regex("[a-z0-9.-]+")) && host.split('.').all { it.length <= 24 }
                                }
                            }.distinct().sorted())
                            fun strings(key: String) = data.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                                ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString }.orEmpty()
                            val urls = data.get("midurlinfo")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                                it.takeIf { item -> item.isJsonObject }?.asJsonObject?.get("purl")
                                    ?.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString
                            }.orEmpty()
                            add("sipHosts", hosts(strings("sip")))
                            add("thirdipHosts", hosts(strings("thirdip")))
                            add("purlHosts", hosts(urls))
                            addProperty("hasPurl", urls.any(String::isNotEmpty))
                            addProperty("hasRelativePurl", urls.any { it.isNotEmpty() && Uri.parse(it).scheme == null })
                            addProperty("retcode", data.get("retcode")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull())
                        }.toString())
                    } })
                progress("playbackDiagnosticResult=granted")
            } catch (error: SourceException) { progress("playbackDiagnosticResult=${error.code}") }
        } finally { prepared?.directory?.deleteRecursively(); packageFile.delete() }
    }

    private fun decodeQQDiagnosticPayload(response: JsonObject): JsonObject? = runCatching {
        // Test-only public AG1 response framing, qmweb-sign (MIT), commit 15305a3cc39f80eb1646ab2bae46468231765736.
        val bytes = response.get("bodyBase64")?.asString?.let { java.util.Base64.getDecoder().decode(it) }
        val mask = intArrayOf(122, 63, 140, 29, 94, 155, 47, 10, 108, 77, 126, 139, 31, 58, 92, 157, 14, 43, 111, 74, 129)
        val plain = response.get("body")?.asString?.takeIf { it.isNotEmpty() }
            ?: bytes?.let { encoded -> ByteArray(encoded.size) { (encoded[it].toInt() xor mask[it % mask.size]).toByte() }.toString(Charsets.UTF_8) }
            ?: return@runCatching null
        JsonParser.parseString(plain).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private suspend fun verifyLiveCatalog(router: SourceRouter): List<Track> {
        val page = PageRequest(limit = 3)
        val searches = ContentType.entries.associateWith { type ->
            progress("catalog.search.${type.wireName}")
            router.search(PROVIDER_ID, if (type == ContentType.TRACK) arguments.getString("qqMusicQuery") ?: "晴天" else "周杰伦", type, page)
                .also { result ->
                    assertTrue("QQ ${type.wireName} search must return catalog results", result.items.isNotEmpty())
                    assertTrue(result.items.all { it.ref.providerId == PROVIDER_ID && it.ref.type == type })
                }
        }
        val tracks = searches.getValue(ContentType.TRACK).items.map { (it as SourceItem.TrackItem).track }
        progress("catalog.track")
        val detail = router.getTrack(tracks.first().ref)
        assertNotNull("QQ track detail must be available", detail)
        assertEquals(tracks.first().ref, detail!!.ref)
        assertTrue(detail.title.isNotBlank() && detail.artists.isNotEmpty() && detail.durationMs > 0)
        progress("lyrics.get")
        val lyrics = (router.registry.requireProvider(PROVIDER_ID) as LyricsCapability).getLyrics(detail.ref)
        assertTrue("QQ lyrics must return real lyric text", !lyrics?.text.isNullOrBlank())

        progress("feed.home")
        val home = router.getFeed(PROVIDER_ID, FeedKind.HOME, page = page)
        progress("feed.discover")
        val discover = router.getFeed(PROVIDER_ID, FeedKind.DISCOVER, page = page)
        assertTrue("QQ home must supply catalog sections", home.sections.any { it.items.isNotEmpty() })
        assertTrue("QQ discovery must supply catalog sections", discover.sections.any { it.items.isNotEmpty() })
        val homeItems = home.sections.flatMap { it.items }
        val playlist = homeItems.filterIsInstance<SourceItem.PlaylistItem>().firstOrNull { !it.ref.playlistId.startsWith("chart:") }
        assertNotNull("QQ home must contain a playlist that can be opened", playlist)
        progress("playlist.get / playlist.tracks")
        assertEquals(playlist!!.ref, router.playlist(playlist.ref).ref)
        assertValidTracks("QQ home playlist", router.playlistTracks(playlist.ref, page).items)
        assertTrue("QQ default discovery must retain its official category feed", discover.sections.any { it.id == "category" })
        progress("feed.discover.charts")
        val chartFeed = router.getFeed(PROVIDER_ID, FeedKind.DISCOVER, "charts", page)
        val chart = chartFeed.sections.flatMap { it.items }.filterIsInstance<SourceItem.PlaylistItem>()
            .firstOrNull { it.ref.playlistId.startsWith("chart:") }
        assertNotNull("QQ discovery must contain a chart that can be opened", chart)
        progress("chart.get / chart.tracks")
        assertEquals(chart!!.ref, router.playlist(chart.ref).ref)
        assertValidTracks("QQ discovery chart", router.playlistTracks(chart.ref, page).items)
        discover.categories.firstOrNull { it.id.matches(Regex("[0-9]+")) }?.let { category ->
            progress("feed.discover.category")
            val categoryFeed = router.getFeed(PROVIDER_ID, FeedKind.DISCOVER, category.id, page)
            assertTrue("QQ discovery categories must return playlists", categoryFeed.sections.flatMap { it.items }.any { it is SourceItem.PlaylistItem })
        }
        val album = homeItems.filterIsInstance<SourceItem.AlbumItem>().firstOrNull()
            ?: searches.getValue(ContentType.ALBUM).items.filterIsInstance<SourceItem.AlbumItem>().first()
        progress("album.get / album.tracks")
        assertEquals(album.ref, router.album(album.ref).ref)
        assertValidTracks("QQ album", router.albumTracks(album.ref, page).items)
        val artist = searches.getValue(ContentType.ARTIST).items.filterIsInstance<SourceItem.ArtistItem>().first()
        progress("artist.get / artist.tracks / artist.albums")
        assertEquals(artist.ref, router.artist(artist.ref).ref)
        assertValidTracks("QQ artist", router.artistTracks(artist.ref, page).items)
        val albums = router.artistAlbums(artist.ref, page)
        assertTrue("QQ artist must expose its album catalog", albums.items.isNotEmpty())
        assertTrue(albums.items.all { it.ref.providerId == PROVIDER_ID })
        return tracks
    }

    private suspend fun verifyLiveAccountAndLibrary(router: SourceRouter, required: Boolean, noSession: Boolean) {
        val pageSize = arguments.getString("qqMusicLibraryPageSize")?.toIntOrNull()?.coerceIn(1, 100) ?: 3
        progress("account.get")
        val account = try { router.account(PROVIDER_ID) }
        catch (error: SourceException) {
            if (required || error.code !in AUTH_ERRORS) throw error
            null
        }
        if (required) assertNotNull("qqMusicAccount=true requires an authorized personal account", account)
        if (noSession) assertNull("No Cookie must never create a logged-in QQ account", account)
        if (account != null) {
            assertTrue(account.ref.accountId.value.isNotBlank())
            assertTrue(account.displayName.isNotBlank())
            assertEquals(router.registry.requireProvider(PROVIDER_ID).instanceId, account.ref.instanceId)
        }
        for (collection in LibraryCollection.entries) {
            progress(collection.operation)
            try {
                val result = router.library(PROVIDER_ID, collection, PageRequest(limit = pageSize))
                assertFalse("An anonymous library must return AUTH_REQUIRED rather than a fake empty success", noSession)
                assertNotNull("Library success requires a confirmed personal QQ account", account)
                assertTrue(result.items.all { it.ref.providerId == PROVIDER_ID && it.ref.type == collection.type })
                progress("${collection.operation}:items=${result.items.size}")
                if (collection.operation in arguments.getString("qqMusicRequireNonemptyCollections").orEmpty().split(',')) {
                    assertTrue("The configured library collection must contain its known items", result.items.isNotEmpty())
                }
                if (collection == LibraryCollection.LIKED_TRACKS && arguments.getString("qqMusicVerifyLikedFlags") == "true") {
                    val refs = result.items.filterIsInstance<SourceItem.TrackItem>().map { it.track.ref }.distinct()
                    if (refs.isNotEmpty()) {
                        progress("track.liked:count=${refs.size}")
                        val flags = router.liked(refs)
                        assertTrue("Every returned favorite must retain its visible star", refs.all { flags[it] == true })
                    }
                }
            } catch (error: SourceException) {
                if (required || error.code !in AUTH_ERRORS) throw error
            }
        }
    }

    private fun progress(operation: String) = instrumentation.sendStatus(0, android.os.Bundle().apply {
        putString("stream", "QQ live: $operation\n")
    })

    private fun assertValidTracks(label: String, tracks: List<Track>) {
        assertTrue("$label must return tracks that can be queued", tracks.isNotEmpty())
        assertTrue(tracks.all { it.ref.providerId == PROVIDER_ID && it.title.isNotBlank() && it.durationMs > 0 })
    }

    private fun assertSameUpdateContract(
        previous: PluginManifest,
        proposed: PluginManifest,
        reviewed: Boolean = arguments.getString("qqMusicReviewedUpdate") == "true",
    ) {
        assertTrue("Updates must retain QQ identities", previous.id == PLUGIN_ID && proposed.id == PLUGIN_ID &&
            previous.providerId == PROVIDER_ID && proposed.providerId == PROVIDER_ID)
        assertTrue("Updates must retain the QQ configuration fields", previous.config.sortedBy { it.key } == proposed.config.sortedBy { it.key })
        val same = previous.apiVersion == proposed.apiVersion && previous.entry == proposed.entry &&
            previous.capabilities == proposed.capabilities && previous.searchTypes == proposed.searchTypes &&
            previous.collectionTypes == proposed.collectionTypes && previous.permissions.network.toSet() == proposed.permissions.network.toSet() &&
            previous.permissions.storage == proposed.permissions.storage && previous.permissions.nativeSdks == proposed.permissions.nativeSdks &&
            previous.features == proposed.features && previous.featureRoutes == proposed.featureRoutes &&
            previous.permissions.effects == proposed.permissions.effects && previous.sessionKeys == proposed.sessionKeys &&
            previous.sessionCookieNames == proposed.sessionCookieNames && previous.linkHosts == proposed.linkHosts
        if (same) return
        assertTrue("Contract changes require explicit qqMusicReviewedUpdate=true", reviewed)
        if (previous.apiVersion == 2) {
            assertEquals("Only the reviewed API2 package can add its observed CDN", "0.3.0", previous.version)
            assertEquals("index.js", previous.entry)
            assertEquals(API2_CAPABILITIES, previous.capabilities)
            assertEquals(API2_PREVIOUS_DOMAINS, previous.permissions.network.toSet())
            assertTrue(previous.permissions.storage)
            assertEquals(proposed.searchTypes, previous.searchTypes)
            assertEquals(proposed.collectionTypes, previous.collectionTypes)
            assertReviewedApi2Contract(proposed)
            return
        }
        assertEquals("Only the reviewed API1 to API2 migration is allowed", 1, previous.apiVersion)
        assertEquals("Only the reviewed 0.1 package can expand its contract", "0.1.0", previous.version)
        assertEquals("Only the existing QQ entry can be upgraded", "index.js", previous.entry)
        assertEquals(API1_CAPABILITIES, previous.capabilities)
        assertEquals(API1_DOMAINS, previous.permissions.network.toSet())
        assertTrue(previous.permissions.storage)
        assertEquals(setOf("track"), previous.searchTypes)
        assertTrue(previous.collectionTypes.isEmpty())
        assertReviewedApi2Contract(proposed)
    }

    /** Explicit test-only migration approval is pinned to this shipped QQ contract. */
    private fun assertReviewedApi2Contract(manifest: PluginManifest) {
        assertEquals(PLUGIN_ID, manifest.id)
        assertEquals(PROVIDER_ID, manifest.providerId)
        assertEquals("0.3.1", manifest.version)
        assertEquals(2, manifest.apiVersion)
        assertEquals("index.js", manifest.entry)
        assertEquals(API2_CAPABILITIES, manifest.capabilities)
        assertEquals(API2_DOMAINS, manifest.permissions.network.toSet())
        assertTrue(manifest.permissions.storage)
        assertTrue(manifest.permissions.nativeSdks.isEmpty())
        assertTrue(manifest.features.isEmpty())
        assertTrue(manifest.featureRoutes.isEmpty())
        assertTrue(manifest.permissions.effects.isEmpty())
        assertTrue(manifest.sessionKeys.isEmpty())
        assertTrue(manifest.sessionCookieNames.isEmpty())
        assertTrue(manifest.linkHosts.isEmpty())
        assertEquals(setOf("track", "playlist", "album", "artist"), manifest.searchTypes)
        assertEquals(setOf("playlist", "album", "artist"), manifest.collectionTypes)
        assertEquals(listOf(
            PluginConfigField("cookie", "本人 QQ 音乐网页 Cookie", secret = true),
            PluginConfigField("uin", "账号 ID（Cookie 中缺失时填写）"),
        ).sortedBy { it.key }, manifest.config.sortedBy { it.key })
    }

    /** Shipped callbacks process captured payloads inside QuickJS without granting host network access. */
    private fun capturedResponseWrapper(fixtures: JsonObject): String = """
        ;(function () {
            const original = globalThis.sourcePlugin;
            const captured = ${Gson().toJson(fixtures)};
            const operations = ['catalog.search', 'catalog.track', 'lyrics.get', 'playback.resolve',
                'feed.home', 'feed.discover', 'playlist.get', 'playlist.tracks', 'album.get', 'album.tracks',
                'artist.get', 'artist.tracks', 'artist.albums', 'account.get',
                'library.likedTracks', 'library.createdPlaylists', 'library.savedPlaylists',
                'library.savedAlbums', 'library.followedArtists'];
            const wrapper = {};
            operations.forEach(function (operation) {
                wrapper[operation] = function (params, context) {
                    let value = original[operation](params, context);
                    const stages = [];
                    for (let hop = 0; value && value.request; hop++) {
                        if (hop >= 4) throw new Error('Captured response request limit');
                        const state = value.state || {};
                        const stage = state.stage;
                        stages.push(stage);
                        let response;
                        if (stage === 'feed') {
                            response = {code: 0};
                            const categoryId = String(state.categoryId || '');
                            const keys = /^\d+$/.test(categoryId) ? ['categories', 'category'] :
                                state.feedKind === 'home' ? ['recommend', 'songs', 'albums'] :
                                ['categories', {recommend:'recommend', tracks:'songs', albums:'albums', charts:'charts'}[categoryId] || 'category'];
                            keys.forEach(function (key) {
                                response[key] = key === 'category' ? captured.category.category : captured.feed[key];
                                if (!response[key]) throw new Error('Missing captured feed response');
                            });
                        } else {
                            const name = stage === 'search' ? state.type === 'track' ? 'search' : 'search-' + state.type :
                                stage === 'lyrics' ? 'lyrics' :
                                ['detail', 'lyrics-detail', 'playback-detail'].includes(stage) ? 'detail' :
                                stage === 'playlist.get' || stage === 'playlist.tracks' ? state.chart ? 'chart' : 'playlist' :
                                {'album.get':'album', 'album.tracks':'album-tracks', 'artist.get':'artist',
                                 'artist.tracks':'artist-tracks', 'artist.albums':'artist-albums'}[stage];
                            response = captured[name];
                        }
                        if (!response) throw new Error('Unexpected captured response stage');
                        value = original.onResponse(operation, {status: 200, body: JSON.stringify(response)}, state, context);
                    }
                    if (value && value.error) return value;
                    return {result: {value: value && Object.prototype.hasOwnProperty.call(value, 'result') ? value.result : value, stages: stages}};
                };
            });
            globalThis.sourcePlugin = wrapper;
        })();
    """.trimIndent()

    private fun expectRejectedContract(check: () -> Unit) {
        var rejected = false
        try { check() } catch (_: AssertionError) { rejected = true }
        assertTrue("An unreviewed QQ contract expansion must be rejected", rejected)
    }

    private fun pageParams(): JsonObject = JsonObject().apply { addProperty("offset", 0); addProperty("limit", 3) }
    private fun idParams(key: String, id: String): JsonObject = JsonObject().apply { addProperty(key, id) }
    private fun stages(result: JsonObject) = result.getAsJsonArray("stages").map { it.asString }

    private suspend fun expectSourceError(code: SourceErrorCode, invoke: suspend () -> Unit) {
        try { invoke(); fail("Expected explicit ${code.name}") }
        catch (error: SourceException) { assertEquals(code, error.code) }
    }

    private fun copyPackage(target: File) {
        val localPackage = File(context.cacheDir, "qq-music-test.meisource")
        val useLocal = arguments.getString("qqMusicPackageFromCache") == "true"
        if (useLocal) {
            val expected = arguments.getString("qqMusicPackageSha256").orEmpty()
            assertTrue("Local package validation requires an explicit reviewed digest", expected.matches(Regex("[a-f0-9]{64}")))
            assertTrue("Local package must be bounded", localPackage.isFile && localPackage.length() in 1..(2L * 1024 * 1024))
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(localPackage.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("Only the reviewed package may replace the captured fixture", expected, digest)
        }
        (if (useLocal) localPackage.inputStream() else instrumentation.context.assets.open("source-plugin/qq-music.meisource")).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private suspend fun accountState(): Pair<String?, String?> = context.dataStore.data.first().let { it[CookieKey] to it[UserIdKey] }
    private fun verifyIndependentPlayback(resource: AudioResource, expectedDurationMs: Long) {
        val directory = File(context.cacheDir, "qq-playback-${UUID.randomUUID()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        val ready = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        try {
            val transport = ResolvingDataSource.Factory(CacheManager.getPlaybackDataSourceFactory(context, cache)) { spec ->
                spec.buildUpon().setUri(resource.uri).setHttpRequestHeaders(resource.headers)
                    .setCustomData(resource.cachePolicy).build()
            }
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(transport)).build().apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) ready.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error); ready.countDown() }
                    })
                    setMediaItem(MediaItem.fromUri(resource.uri))
                    prepare()
                    play()
                }
            }
            assertTrue("Independent QQ playback did not start", ready.await(30, TimeUnit.SECONDS))
            assertTrue("Independent QQ playback failed", failure.get() == null)
            var actualDurationMs = -1L
            instrumentation.runOnMainSync { actualDurationMs = player?.duration ?: -1L }
            assertTrue("The decoded QQ resource must expose its full duration", actualDurationMs > 0)
            assertTrue("A short preview must not count as full playback",
                kotlin.math.abs(actualDurationMs - expectedDurationMs) <= maxOf(5_000L, expectedDurationMs / 20))
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            cache.release()
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val PLUGIN_ID = "org.meilox.qqmusic"
        private const val PROVIDER_ID = "qqmusic"
        private const val FIXTURE_TRACK = "0039MnYb0qxYhV"
        private const val FIXTURE_ARTIST = "0025NhlN2yWrP4"
        private val AUTH_ERRORS = setOf(SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED)
        private val CAPTURED_FIXTURES = listOf("search", "detail", "lyrics", "feed", "category", "playlist", "chart",
            "album", "album-tracks", "artist", "artist-tracks", "artist-albums", "search-artist", "search-album", "search-playlist")
        private val API1_CAPABILITIES = setOf("catalog.search", "catalog.track", "lyrics.get", "playback.resolve")
        private val API1_DOMAINS = setOf("u.y.qq.com", "y.gtimg.cn", "ws6.stream.qqmusic.qq.com")
        private val API2_CAPABILITIES = API1_CAPABILITIES + setOf(
            "feed.home", "feed.discover", "playlist.get", "playlist.tracks", "album.get", "album.tracks",
            "artist.get", "artist.tracks", "artist.albums", "account.get", "library.likedTracks",
            "library.createdPlaylists", "library.savedPlaylists", "library.savedAlbums", "library.followedArtists",
            "track.liked", "track.like", "collection.saved", "collection.save", "playlist.create",
            "playlist.update", "playlist.delete", "playlist.addTracks", "playlist.removeTracks",
        )
        private val API2_PREVIOUS_DOMAINS = API1_DOMAINS + setOf("c.y.qq.com", "music-file.y.qq.com", "p.qpic.cn",
            "qpic.y.qq.com", "pic6.y.qq.com", "thirdqq.qlogo.cn", "q.qlogo.cn")
        private val API2_DOMAINS = API2_PREVIOUS_DOMAINS + "sjy6.stream.qqmusic.qq.com"
    }
}
