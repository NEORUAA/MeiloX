package com.ljyh.mei.data.network

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.luben.zstd.Zstd
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.AppContext
import com.ljyh.mei.data.network.netease.NcblCodec
import com.ljyh.mei.playback.DownloadSourceEntryPoint
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.api.SourceCoreEffectCapability
import com.ljyh.mei.source.api.ContentType
import com.ljyh.mei.source.api.FeedKind
import com.ljyh.mei.source.api.LibraryCollection
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.SourceItem
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.script.PluginManifest
import com.ljyh.mei.source.script.ScriptContentParser
import com.ljyh.mei.source.script.ScriptRuntime
import com.ljyh.mei.ui.screen.account.readPcQrValues
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline production-script/SDK proofs and explicitly opted-in installed-source reads. */
@RunWith(AndroidJUnit4::class)
class NetEaseScriptInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun largeSourceResponsesPreserveIdentifiersWithinTheSandboxMemoryBudget() = runBlocking {
        assumeTrue(com.ljyh.mei.source.script.ScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext).supported)
        val (manifest, script) = bundledSource()
        val text = "x".repeat(700_000)
        val payload = """{"code":200,"songs":[{"id":9223372036854775807,"name":"12345678901234567890","padding":"$text","ar":[],"al":{},"dt":1500}]}"""
        val result = ScriptRuntime(context).invoke(script, manifest, "catalog.track",
            JsonObject().apply { addProperty("trackId", "9223372036854775807") }, emptyMap(), emptyMap(),
            request = { JsonObject().apply { addProperty("status", 200); addProperty("body", payload) } }).result.asJsonObject
        assertEquals("9223372036854775807", result["trackId"].asString)
        assertEquals("12345678901234567890", result["title"].asString)
        assertEquals(1500, result["durationMs"].asInt)
    }

    @Test fun explicitlyEnabledPrivateSessionReadsDoNotChangeInstalledAccounts() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("neteasePrivateSession") == "true")
        val fixture = File(context.cacheDir, "source-session-smoke.json")
        val configuration = try {
            require(fixture.length() in 2..32_768)
            val value = JsonParser.parseString(fixture.readText())
            require(value.isJsonObject)
            value.asJsonObject.entrySet().associate {
                require(it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString)
                it.key to it.value.asString
            }
        } catch (_: Exception) {
            throw AssertionError("Invalid private session fixture")
        } finally { fixture.delete() }
        val preferences = context.dataStore.data.first()
        val (manifest, script) = bundledSource()
        val runtime = ScriptRuntime(context)
        val failures = mutableListOf<String>()
        var storage = emptyMap<String, String>()
        val network = com.ljyh.mei.source.script.ScriptNetworkPolicy(
            manifest.permissions.network, manifest.permissions.nativeSdks, { configuration + storage })
        try {
            for ((operation, parameters) in listOf(
                "account.get" to "{}",
                "feed.home" to "{\"limit\":3}",
                "feed.discover" to "{\"limit\":3}",
                "catalog.search" to "{\"query\":\"HOYO-MiX\",\"type\":\"track\",\"limit\":3}",
                "catalog.track" to "{\"trackId\":\"29897918\"}",
                "playback.resolve" to "{\"trackId\":\"29897918\",\"quality\":\"standard\"}",
            )) {
                try {
                    val result = runtime.invoke(script, manifest, operation, json(parameters), configuration, storage,
                        request = { frame -> network.request(frame).also { response ->
                            val body = runCatching { JsonParser.parseString(response["body"].asString) }.getOrNull()
                            val code = body?.takeIf { it.isJsonObject }?.asJsonObject?.get("code")
                                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                            instrumentation.sendStatus(0, android.os.Bundle().apply {
                                putString("stream", "Private-session read: $operation HTTP=${response["status"].asInt} JSON=${body?.isJsonObject == true} code=$code\n")
                            })
                        } })
                    result.storage?.let { storage = it }
                    assertFalse("$operation returned null", result.result.isJsonNull)
                } catch (error: com.ljyh.mei.source.api.SourceException) {
                    failures += "$operation:${error.code.name}"
                }
            }
            assertTrue("Private-session reads failed: ${failures.joinToString()}", failures.isEmpty())
        } finally {
            fixture.delete()
            assertTrue("Read-only validation must retain preferences", preferences == context.dataStore.data.first())
        }
    }

    @Test fun bundledSourceRunsCatalogAndAccountReadsThroughTheRealSandboxAndWireCodec() = runBlocking {
        assumeTrue(com.ljyh.mei.source.script.ScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext).supported)
        val (manifest, script) = bundledSource()
        assertEquals("netease", manifest.providerId)
        assertEquals(2, manifest.apiVersion)
        val runtime = ScriptRuntime(context)
        val parser = ScriptContentParser(manifest.providerId, manifest.apiVersion, emptySet()) { "" }
        val preferences = context.dataStore.data.first()
        val configuration = mapOf("cookie" to "MUSIC_U=fixture-session", "sessionType" to "mobile", "userId" to "7")
        suspend fun invoke(operation: String, parameters: String, replies: List<String>): com.google.gson.JsonElement {
            var calls = 0
            val invocation = runtime.invoke(script, manifest, operation, json(parameters), configuration, emptyMap(), request = { frame ->
                val url = Uri.parse(frame["url"].asString)
                assertTrue("Every recipe must use a declared network domain", url.host in manifest.permissions.network)
                assertEquals("https", url.scheme)
                assertEquals("netease.transport", frame["sdk"].asString)
                val arguments = frame.getAsJsonObject("sdkConfig").entrySet().associate { it.key to it.value.asString }
                assertTrue("Wire SDK must receive no endpoint/account/profile policy", arguments.keys.all {
                    it in setOf("mode", "signingPath", "formFields", "weapiKey")
                })
                val original = Request.Builder().url(frame["url"].asString).apply {
                    frame.getAsJsonObject("headers").entrySet().forEach { (name, value) -> header(name, value.asString) }
                }.post(frame["body"].asString.toRequestBody()).build()
                val encoded = NeteaseWireCodec.encodeRequest(original, arguments)
                assertEquals(original.url, encoded.url)
                assertTrue(encoded.body is FormBody)
                assertTrue("Fixture response count must match the real recipe", calls < replies.size)
                JsonObject().apply { addProperty("status", 200); addProperty("body", replies[calls++]) }
            })
            assertEquals(replies.size, calls)
            return invocation.result
        }
        withTimeout(90_000) {
            val song = """{"id":42,"name":"Fixture song","ar":[{"id":8,"name":"Fixture artist"}],"al":{"id":9,"name":"Fixture album"},"dt":180000}"""
            val track = parser.track(invoke("catalog.track", """{"trackId":"42"}""", listOf("""{"code":200,"songs":[$song]}""")).asJsonObject)
            assertEquals(TrackRef("netease", "42"), track.ref)
            assertEquals("Fixture song", track.title)
            assertEquals(180_000L, track.durationMs)
            val profile = """{"code":200,"profile":{"userId":7,"nickname":"Fixture user"},"account":{"id":7}}"""
            val liked = invoke("track.liked", """{"trackId":"42","trackIds":["42","43"]}""", listOf(profile, """{"code":200,"ids":[42]}"""))
            assertEquals("42", liked.asJsonObject.getAsJsonArray("items")[0].asJsonObject["trackId"].asString)
            assertTrue(liked.asJsonObject.getAsJsonArray("items")[0].asJsonObject["liked"].asBoolean)
            assertFalse(liked.asJsonObject.getAsJsonArray("items")[1].asJsonObject["liked"].asBoolean)
            val playlist = """{"code":200,"playlist":{"id":10,"name":"Fixture list","trackCount":2,"trackIds":[{"id":43},{"id":42}],"creator":{"userId":7}}}"""
            val reversed = song.replace("\"id\":42", "\"id\":43")
            val page = invoke("playlist.tracks", """{"playlistId":"10","limit":2}""", listOf(playlist, """{"code":200,"songs":[$song,$reversed]}"""))
            assertEquals(listOf("43", "42"), page.asJsonObject.getAsJsonArray("items").map { it.asJsonObject["trackId"].asString })
        }
        assertTrue("Offline script fixtures must never change the host account or preferences", preferences == context.dataStore.data.first())
    }

    @Test fun packagedNativeCodecsAndDeviceIdentityRemainAvailableWithoutOnlineBusiness() = runBlocking {
        val body = "playback-native-smoke".toByteArray()
        val encoded = NcblCodec.encode("{}".toByteArray(), body)
        assertArrayEquals(byteArrayOf(0x4e, 0x43, 0x42, 0x4c), encoded.copyOfRange(0, 4))
        assertArrayEquals(body, Zstd.decompress(Zstd.compress(body), body.size))
        val first = NeteaseOfficialDeviceId.create(context, "0123456789abcdef")
        assertEquals(first.encoded, NeteaseOfficialDeviceId.create(context, "0123456789abcdef").encoded)
        assertEquals(16, first.localIdLength)
        val nativeId = com.netease.`is`.deviceid.NEDeviceID.getLocalID(OfficialNeteaseSecurityContext(context)).orEmpty().trim()
        assertEquals(nativeId.length < 16, first.usedLocalIdFallback)
        suspend fun solve(algorithm: String, args: JSONObject) = NeteaseUrsPuzzle.solve(JSONObject().put("compQues", JSONArray().put(
            JSONObject().put("hashFunc", algorithm).put("sid", "fixture").put("minTime", 0).put("maxTime", 1000).put("args", args))))
        val sequential = solve("SEQ_HASHCASH", JSONObject().put("puzzle", "fixture").put("target", "f".repeat(64)))
        val n = sequential.values.getValue("n") as Long
        assertEquals((1L..n).minOf { MessageDigest.getInstance("SHA-256").digest("fixture$it".toByteArray()).toHex() }, sequential.values["pow"])
        val recursive = solve("RECUR_HASHCASH", JSONObject().put("puzzle", "fixture").put("target", "f".repeat(64)))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(("fixture" + recursive.values.getValue("n1")).toByteArray()).toHex(), recursive.values["pow"])
        val vdf = solve("VDF_FUNCTION", JSONObject().put("puzzle", "fixture").put("mod", "11").put("x", "3").put("t", "3"))
        assertEquals(BigInteger.valueOf(3).modPow(BigInteger.valueOf(8), BigInteger.valueOf(17)).toString(16), vdf.values["x"])
        val directory = File(context.cacheDir, "source-urs-codec-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val isolated = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getFilesDir(): File = directory
            }
            val certificate = context.assets.open("netease_official_signing_certificate.der").use { it.readBytes() }
            val crypto = NeteaseUrsCrypto(NeteaseUrsApplicationContext(isolated, certificate), NeteaseUrsSdkProfile.APP_SIGN)
            val headers = crypto.encryptHeaders(JSONObject().put("fixture", true))
            assertTrue(crypto.packageSignature.isNotBlank())
            assertEquals(setOf("p1", "p2"), headers.keys)
            assertTrue(headers.values.all { it.isNotBlank() && it.all { char -> char in "0123456789abcdefABCDEF" } })
            assertTrue("Every envelope must have fresh symmetric entropy", headers != crypto.encryptHeaders(JSONObject().put("fixture", true)))
            assertEquals("fixture", crypto.decryptResponse(JSONObject().put("data", JSONObject().put("value", "fixture"))).getString("value"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun sourceAssetsContainNoExecutableDexAndQrImageImportKeepsItsExistingResults() = runBlocking {
        fun inspect(directory: String) {
            for (name in context.assets.list(directory).orEmpty()) {
                val path = if (directory.isEmpty()) name else "$directory/$name"
                if (context.assets.list(path).orEmpty().isNotEmpty()) inspect(path)
                else context.assets.open(path).use { input ->
                    val magic = ByteArray(4)
                    val length = input.read(magic)
                    assertFalse("Executable asset: $path", name.endsWith(".dex", true) || length == 4 && magic.contentEquals(byteArrayOf(100, 101, 120, 10)))
                }
            }
        }
        inspect("")
        suspend fun image(name: String): List<String> {
            val file = File.createTempFile("source-qr-", ".png", context.cacheDir)
            return try {
                instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
                readPcQrValues(context, Uri.fromFile(file))
            } finally { file.delete() }
        }
        val legacy = image("pc-login-test.png").single()
        assertEquals("https://music.163.com/login?codekey=test-web-key&login_traceId=test-web-trace", legacy)
        val current = image("web-scanlogin-test.png").single()
        assertEquals(listOf("https://example.com"), image("unsupported-qr-test.png"))
        assertTrue(image("no-qr-test.png").isEmpty())
        if (com.ljyh.mei.source.script.ScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext).supported) {
            val (manifest, script) = bundledSource()
            suspend fun parse(value: String) = ScriptRuntime(context).invoke(script, manifest, "feature.invoke", JsonObject().apply {
                addProperty("feature", "login"); addProperty("action", "parsePcQr")
                add("parameters", JsonObject().apply { addProperty("value", value) })
            }, emptyMap(), emptyMap(), request = { error("QR format recognition must require no network requests") }).result
            assertEquals("test-web-key", parse(legacy).asJsonObject["key"].asString)
            val payload = parse(current).asJsonObject
            assertEquals("00000000-0000-0000-0000-000000000000", payload["key"].asString)
            assertTrue(payload["clientTraceId"].isJsonNull)
            assertTrue(parse("https://example.com").isJsonNull)
        }
    }

    @Test fun explicitlyEnabledInstalledSourceSupportsReadOnlyCatalogAndLibrary() = runBlocking {
        assumeTrue("Enable installed-source requests explicitly with neteaseSourceLive=true",
            InstrumentationRegistry.getArguments().getString("neteaseSourceLive") == "true")
        assumeTrue(com.ljyh.mei.source.script.ScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext).supported)
        val app = context.applicationContext as AppContext
        val router = EntryPointAccessors.fromApplication(context, DownloadSourceEntryPoint::class.java).sourceRouter()
        router.registry.awaitInitialized()
        val installed = app.sourcePlugins.installed.value.firstOrNull { it.manifest.providerId == "netease" }
        assumeTrue("An enabled NetEase source must already be installed", installed?.status == SourceStatus.READY)
        val id = checkNotNull(installed).manifest.id
        val configuration = app.sourcePlugins.configuration(id)
        val preferences = context.dataStore.data.first()
        val provider = router.registry.requireProvider("netease")
        fun params(key: String? = null, value: String? = null) = JsonObject().apply {
            addProperty("offset", 0); addProperty("limit", 3)
            if (key != null) addProperty(key, value)
        }
        suspend fun <T> read(operation: String, parameters: JsonObject = params(), block: suspend () -> T): T {
            if (InstrumentationRegistry.getArguments().getString("neteaseArtworkDiagnostics") == "true") {
                val revision = (provider as? AccountRevisionCapability)?.accountRevision?.first()
                val result = runCatching {
                    (provider as SourceCoreEffectCapability).invokeCoreOperation(operation, parameters, revision)
                }.getOrNull()
                val hosts = sortedSetOf<String>()
                fun collect(value: com.google.gson.JsonElement?) {
                    when {
                        value == null || value.isJsonNull -> Unit
                        value.isJsonObject -> value.asJsonObject.entrySet().forEach { collect(it.value) }
                        value.isJsonArray -> value.asJsonArray.forEach(::collect)
                        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                            val url = value.asString
                            if (url.startsWith("https://")) runCatching { java.net.URI(url).host }.getOrNull()
                                ?.takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) && it !in installed.manifest.permissions.network }
                                ?.let(hosts::add)
                        }
                    }
                }
                collect(result)
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "NetEase read: $operation; undeclared metadata hosts=${hosts.joinToString()}\n")
                })
            }
            return block()
        }
        try {
            withTimeout(180_000) {
                for (type in ContentType.entries) {
                    val items = read("catalog.search", params("query", "HOYO-MiX").apply { addProperty("type", type.wireName) }) { router.search("netease", "HOYO-MiX", type, PageRequest(limit = 3)).items }
                    assertTrue("The installed source must supply $type results", items.isNotEmpty())
                    assertTrue(items.all { it.ref.providerId == "netease" })
                    when (val item = items.first()) {
                        is SourceItem.TrackItem -> assertEquals(item.ref, read("catalog.track", params("trackId", item.ref.trackId)) { router.getTrack(item.ref)?.ref })
                        is SourceItem.AlbumItem -> {
                            assertEquals(item.ref, read("album.get", params("albumId", item.ref.albumId)) { router.album(item.ref).ref })
                            assertTrue(read("album.tracks", params("albumId", item.ref.albumId)) { router.albumTracks(item.ref, PageRequest(limit = 3)).items.isNotEmpty() })
                        }
                        is SourceItem.ArtistItem -> {
                            assertEquals(item.ref, read("artist.get", params("artistId", item.ref.artistId)) { router.artist(item.ref).ref })
                            assertTrue(read("artist.tracks", params("artistId", item.ref.artistId)) { router.artistTracks(item.ref, PageRequest(limit = 3)).items.isNotEmpty() })
                            read("artist.albums", params("artistId", item.ref.artistId)) { router.artistAlbums(item.ref, PageRequest(limit = 3)) }
                        }
                        is SourceItem.PlaylistItem -> {
                            assertEquals(item.ref, read("playlist.get", params("playlistId", item.ref.playlistId)) { router.playlist(item.ref).ref })
                            read("playlist.tracks", params("playlistId", item.ref.playlistId)) { router.playlistTracks(item.ref, PageRequest(limit = 3)) }
                        }
                    }
                }
                for (kind in FeedKind.entries) {
                    val feed = read(kind.operation) { router.getFeed("netease", kind, page = PageRequest(limit = 3)) }
                    assertTrue("The installed source must supply $kind content", feed.sections.any {
                        it.items.isNotEmpty() || it.cards.isNotEmpty()
                    })
                }
                val account = read("account.get") { router.account("netease") }
                if (InstrumentationRegistry.getArguments().getString("neteaseSourceAccount") == "true") {
                    assertNotNull("The migrated account must remain signed in", account)
                }
                if (account != null) {
                    for (collection in LibraryCollection.entries) read(collection.operation) { router.library("netease", collection, PageRequest(limit = 3)) }
                }
            }
        } finally {
            assertTrue("Read-only validation must retain source configuration", configuration == app.sourcePlugins.configuration(id))
            assertTrue("Read-only validation must retain the host account and preferences", preferences == context.dataStore.data.first())
        }
    }

    private fun bundledSource(): Pair<PluginManifest, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(context.assets.open("music-sources/netease.meisource")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in setOf("manifest.json", "index.js")) entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val manifest = PluginManifest.parse(checkNotNull(entries["manifest.json"]))
        return manifest to checkNotNull(entries[manifest.entry])
    }

    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
}
