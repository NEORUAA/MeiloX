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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ScriptPluginInstrumentedTest {
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
