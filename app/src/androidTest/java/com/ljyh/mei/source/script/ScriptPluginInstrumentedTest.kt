package com.ljyh.mei.source.script

import android.net.Uri
import androidx.javascriptengine.JavaScriptSandbox
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.PlaybackResolver
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourceSettings
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.google.gson.JsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ScriptPluginInstrumentedTest {
    @Test
    fun concurrentOperationsReadStorageOnlyAfterThePreviousCommit() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = PluginManifest.parse("""{"id":"org.meilox.storage-test","name":"Storage fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track"],"permissions":{"storage":true}}""")
        val directory = File(context.cacheDir, "source-storage-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val state = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
        val firstCommit = java.util.concurrent.atomic.AtomicBoolean(true)
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = java.util.concurrent.CountDownLatch(1)
        try {
            File(directory, "index.js").writeText("""globalThis.sourcePlugin = {'catalog.track':function(params, context) {
                return {result:{trackId:params.trackId,title:'Storage fixture'},
                    storage:{counter:String(Number(context.storage.counter || '0') + 1)}};
            }};""".trimIndent())
            val provider = ScriptMusicProvider(manifest, directory, ScriptRuntime(context), enabled = true,
                configurationSnapshot = emptyMap(), isCurrent = { true }, config = { emptyMap() }, storage = { state.get() },
                saveStorage = { values ->
                    if (firstCommit.compareAndSet(true, false)) {
                        commitEntered.complete(Unit)
                        check(releaseCommit.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    state.set(values.toMap())
                }, onFailure = { fail("Valid storage updates must not fail") }, configurationVersion = 0)
            withTimeout(30_000) {
                val first = async { provider.getTrack(TrackRef(manifest.providerId, "first")) }
                try {
                    commitEntered.await()
                    // Start inside the provider's dispatcher so this call reaches its first suspension
                    // before the earlier commit is released, without timing a JavaScript busy loop.
                    val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        provider.getTrack(TrackRef(manifest.providerId, "second"))
                    }
                    try {
                        releaseCommit.countDown()
                        assertEquals("first", first.await()?.ref?.trackId)
                        assertEquals("second", second.await()?.ref?.trackId)
                        assertEquals("2", state.get()["counter"])
                    } finally { second.cancelAndJoin() }
                } finally {
                    releaseCommit.countDown()
                    first.cancelAndJoin()
                }
            }
        } finally { releaseCommit.countDown(); directory.deleteRecursively() }
    }

    @Test
    fun revokedInvocationsCannotSendAnotherRequestOrStartAfterWaitingForTheSandbox() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = ScriptRuntime(context)
        val manifest = PluginManifest.parse("""{"id":"org.meilox.revision-test","name":"Revision fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["track.like"],"permissions":{"network":["api.example.com"]}}""")
        val network = ScriptNetworkPolicy(manifest.permissions.network)
        val script = """globalThis.sourcePlugin = {
            'track.like': function() { return {request:{url:'https://api.example.com/session'}}; },
            onResponse: function() { return {request:{url:'https://api.example.com/like',method:'POST',body:'liked=true'}}; }
        };""".trimIndent()
        val gate = PluginRevisionGate()
        val revision = gate.change { it }
        val sentMethods = mutableListOf<String>()
        try {
            runtime.invoke(script, manifest, "track.like", JsonObject(), emptyMap(), emptyMap(), request = { request ->
                network.validateUrl(request["url"].asString)
                sentMethods += request["method"]?.asString ?: "GET"
                gate.change { }
                JsonObject().apply { addProperty("status", 200); addProperty("body", "session ready") }
            }, ensureCurrent = { gate.read(revision) { } })
            fail("Expected the revoked request to cancel")
        } catch (_: CancellationException) { }
        assertEquals(listOf("GET"), sentMethods)

        withTimeout(30_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = async {
                runtime.invoke(script.replace("return {request:{url:'https://api.example.com/like',method:'POST',body:'liked=true'}};", "return true;"),
                    manifest, "track.like", JsonObject(), emptyMap(), emptyMap(), request = { request ->
                        network.validateUrl(request["url"].asString)
                        entered.complete(Unit)
                        release.await()
                        JsonObject().apply { addProperty("status", 200); addProperty("body", "ready") }
                    })
            }
            try {
                entered.await()
                val queuedRevision = gate.change { it }
                var queuedRequests = 0
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.invoke(script, manifest, "track.like", JsonObject(), emptyMap(), emptyMap(), request = {
                        queuedRequests++
                        fail("A revoked queued operation must not perform networking")
                        JsonObject()
                    }, ensureCurrent = { gate.read(queuedRevision) { } })
                }
                gate.change { }
                release.complete(Unit)
                assertTrue(holder.await().result.asBoolean)
                try { queued.await(); fail("Expected the queued operation to cancel") }
                catch (_: CancellationException) { }
                assertEquals(0, queuedRequests)
            } finally {
                release.complete(Unit)
                holder.cancelAndJoin()
            }
        }
    }

    @Test
    fun transportBodyErrorsKeepTheirTypeAndTheProviderCanImmediatelyRecover() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = ScriptRuntime(context)
        val manifest = PluginManifest.parse("""{"id":"org.meilox.transport-test","name":"Transport fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track"],"permissions":{"network":["api.example.com"]}}""")
        val policy = ScriptNetworkPolicy(manifest.permissions.network)
        val failures = listOf(IOException("private source URL") to SourceErrorCode.NETWORK,
            SocketTimeoutException("private source timeout") to SourceErrorCode.TIMEOUT)
        val requestScript = """globalThis.sourcePlugin = {'catalog.track':function() {
            return {request:{url:'https://api.example.com/binary',responseEncoding:'base64'}};
        }};""".trimIndent()
        for ((failure, expected) in failures) {
            try {
                runtime.invoke(requestScript, manifest, "catalog.track", JsonObject(), emptyMap(), emptyMap(), request = { request ->
                    policy.validateUrl(request["url"].asString)
                    failingResponse(failure).use { policy.responsePayload(it, policy.responseEncoding(request)) }
                })
                fail("Expected a typed body read failure")
            } catch (error: SourceException) { assertEquals(expected, error.code) }
        }
        val directory = File(context.cacheDir, "source-transport-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val failureCodes = mutableListOf<SourceErrorCode>()
        try {
            File(directory, "index.js").writeText("""globalThis.sourcePlugin = {'catalog.track':function(params) {
                if(params.trackId !== 'healthy') return {error:params.trackId};
                return {trackId:'healthy',title:'Recovered source'};
            }};""".trimIndent())
            val provider = ScriptMusicProvider(manifest, directory, runtime, enabled = true,
                configurationSnapshot = emptyMap(), isCurrent = { true }, config = { emptyMap() }, storage = { emptyMap() },
                saveStorage = {}, onFailure = { failureCodes += it }, configurationVersion = 0)
            for ((_, expected) in failures) {
                try { provider.getTrack(TrackRef(manifest.providerId, expected.name)); fail("Expected a typed transport error") }
                catch (error: SourceException) { assertEquals(expected, error.code) }
                assertEquals(SourceStatus.READY, provider.status)
                assertFalse(provider.failed)
                assertEquals("Recovered source", provider.getTrack(TrackRef(manifest.providerId, "healthy"))?.title)
            }
            assertTrue(failureCodes.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    private fun failingResponse(failure: Exception): Response {
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        return Response.Builder().request(Request.Builder().url("https://api.example.com/binary").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(object : ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
    }

    @Test
    fun isolatedEvaluationsReceiveFreshEntropyAndBinaryResponsesWithoutAnObjectBridge() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = ScriptRuntime(context)
        val manifest = PluginManifest.parse("""{"id":"org.meilox.entropy-test","name":"Entropy fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track"],"permissions":{"network":["api.example.com"]}}""")
        val bytes = byteArrayOf(0, 0xff.toByte(), 0xc3.toByte(), 0x28)
        val network = ScriptNetworkPolicy(manifest.permissions.network)
        val script = """globalThis.sourcePlugin = {
            'catalog.track': function(params, context) {
                return {request:{url:'https://api.example.com/binary',responseEncoding:'base64'},
                    state:{pools:[context.secureRandomBase64]}};
            },
            onResponse: function(operation, response, state, context) {
                var pools = state.pools.concat([context.secureRandomBase64]);
                if (pools.length < 3) return {
                    request:{url:'https://api.example.com/binary',responseEncoding:'base64'}, state:{pools:pools}};
                return {pools:pools,body:response.body,bodyBase64:response.bodyBase64,
                    isolated:typeof Android === 'undefined' && typeof Java === 'undefined'};
            }
        };""".trimIndent()
        var responses = 0
        val result = runtime.invoke(script, manifest, "catalog.track", JsonObject(), emptyMap(), emptyMap(), request = { request ->
            responses++
            network.validateUrl(request["url"].asString)
            val encoding = network.responseEncoding(request)
            Response.Builder().request(Request.Builder().url(request["url"].asString).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(bytes.toResponseBody()).build().use {
                    network.responsePayload(it, encoding)
                }
        }).result.asJsonObject
        val pools = result["pools"].asJsonArray.map { it.asString }
        assertEquals(2, responses)
        assertEquals(3, pools.distinct().size)
        pools.forEach { assertEquals(256, Base64.getDecoder().decode(it).size) }
        assertEquals("", result["body"].asString)
        assertArrayEquals(bytes, Base64.getDecoder().decode(result["bodyBase64"].asString))
        assertTrue(result["isolated"].asBoolean)
    }

    @Test
    fun packageInspectionAcceptsOnlyBoundedRootLegalText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ScriptPluginManager(context, ProviderRegistry(emptyList()), ScriptRuntime(context))
        val manifest = """{"id":"org.meilox.legal-test","name":"Legal fixture","author":"Tests",
            "version":"1","apiVersion":1,"capabilities":["catalog.track"]}""".toByteArray()
        val script = "globalThis.sourcePlugin={'catalog.track':function(){return null;}};".toByteArray()
        val legal = mapOf("LICENSE" to "MIT License\n".toByteArray(), "NOTICE" to "Cryptography notices\n".toByteArray())
        suspend fun inspect(extra: Map<String, ByteArray>): PreparedPlugin {
            val file = File.createTempFile("source-legal-", ".meisource", context.cacheDir)
            try {
                ZipOutputStream(file.outputStream()).use { zip ->
                    (mapOf("manifest.json" to manifest, "index.js" to script) + extra).forEach { (name, value) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
                    }
                }
                return manager.inspect(Uri.fromFile(file))
            } finally { file.delete() }
        }
        val prepared = inspect(legal)
        try {
            assertEquals("MIT License\n", File(prepared.directory, "LICENSE").readText())
            assertEquals("index.js", prepared.manifest.entry)
        } finally { prepared.directory.deleteRecursively() }
        for (invalid in listOf(mapOf("helper.js" to script), mapOf("nested/LICENSE" to legal.getValue("LICENSE")),
            mapOf("LICENSE" to byteArrayOf(0xff.toByte())), mapOf("NOTICE" to ByteArray(64 * 1024 + 1)))) {
            try { inspect(invalid).directory.deleteRecursively(); fail("Expected package rejection") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        }
    }

    @Test
    fun runawayCodeAndUndeclaredNetworkCannotEscapeTheSandbox() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = ScriptRuntime(context)
        val manifest = PluginManifest.parse("""{"id":"org.meilox.runtime-test","name":"Runtime fixture","author":"Tests",
            "version":"1","apiVersion":1,"capabilities":["catalog.track"]}""")
        val params = com.google.gson.JsonObject().apply { addProperty("trackId", "fixture") }
        val network = ScriptNetworkPolicy(emptyList())
        try {
            runtime.invoke("while (true) {}", manifest, "catalog.track", params, emptyMap(), emptyMap(), network)
            fail("Expected runaway code to be terminated")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.TIMEOUT, error.code) }
        try {
            runtime.invoke("""globalThis.sourcePlugin = {'catalog.track': function () {
                return {request: {url: 'https://127.0.0.1/private'}};
            }};""", manifest, "catalog.track", params, emptyMap(), emptyMap(), network)
            fail("Expected undeclared network rejection")
        } catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        val healthy = runtime.invoke("globalThis.sourcePlugin = {'catalog.track': function () { return null; }};",
            manifest, "catalog.track", params, emptyMap(), emptyMap(), network)
        assertTrue(healthy.result.isJsonNull)
    }

    @Test
    fun importedScriptSearchDetailsFallbackAndAudioUseTheRealSandbox() = runBlocking {
        assumeTrue("JavaScript sandbox requires a compatible Android System WebView", JavaScriptSandbox.isSupported())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val origin = object : MusicProvider, PlaybackCapability {
            override val id = "test.unavailable-origin"
            override val displayName = "Unavailable fixture"
            override val capabilities = setOf(SourceCapability.PLAYBACK)
            override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource = throw SourceException(SourceErrorCode.UNAVAILABLE)
        }
        val registry = ProviderRegistry(listOf(origin))
        val manager = ScriptPluginManager(context, registry, ScriptRuntime(context))
        manager.loadInstalled()
        assumeTrue("Keep any existing user-installed fixture intact", manager.installed.value.none { it.manifest.id == "org.meilox.mock" })
        val packageFile = File(context.cacheDir, "offline-mock.meisource")
        instrumentation.context.assets.open("source-plugin/offline-mock.meisource").use { input -> packageFile.outputStream().use { output -> input.copyTo(output) } }
        try {
            val prepared = manager.inspect(Uri.fromFile(packageFile))
            assertTrue(prepared.manifest.permissions.network.isEmpty())
            val installed = manager.install(prepared, approved = true)
            assertEquals(SourceStatus.READY, installed.status)
            val plugin = registry.requireProvider(installed.manifest.providerId)
            val catalog = plugin as CatalogCapability
            val track = catalog.search("Mock Recording MeiloX SDK").items.single()
            assertEquals("demo:offline/中文 1", track.ref.trackId)
            assertEquals(track, catalog.getTrack(track.ref))
            val settings = SourceSettings(autoFallbackEnabled = true, fallbackOrder = listOf(plugin.instanceId.value))
            val resolver = PlaybackResolver(registry, { settings }, attemptTimeoutMs = 30_000)
            val originTrack = track.copy(ref = TrackRef(origin.id, "same-recording"))
            val result = resolver.resolve(originTrack, "hires")
            assertEquals(originTrack.ref, result.originTrackRef)
            assertEquals(track.ref, result.resolvedTrackRef)
            assertEquals(plugin.id, result.actualAudioProviderId)
            assertEquals("standard", result.resource.quality)
            assertTrue(result.resource.uri.startsWith("mei-source://"))
            assertTrue(result.resource.headers.isEmpty())
            val source = ScriptAudioDataSource.Factory(context).createDataSource()
            try {
                source.open(DataSpec(Uri.parse(result.resource.uri)))
                val header = ByteArray(12)
                assertEquals(12, source.read(header, 0, header.size))
                assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
                assertEquals(result.resource.uri, source.uri.toString())
            } finally { source.close() }
            manager.setEnabled(installed.manifest.id, false)
            assertNull(registry.provider(installed.manifest.providerId))
            manager.setEnabled(installed.manifest.id, true)
            assertNotNull(registry.provider(installed.manifest.providerId))
            val restored = ScriptPluginManager(context, ProviderRegistry(emptyList()), ScriptRuntime(context))
            restored.loadInstalled()
            assertTrue(restored.installed.value.any { it.manifest.id == installed.manifest.id && it.enabled })
        } finally {
            if (manager.installed.value.any { it.manifest.id == "org.meilox.mock" }) manager.uninstall("org.meilox.mock")
            packageFile.delete()
        }
    }
}
