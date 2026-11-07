package com.ljyh.mei.source.script

import android.net.Uri
import com.ljyh.mei.AppContext
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ScriptPluginInstrumentedTest {
    @Test
    fun marketplaceUpdatesAuthenticateLegacyImportsAndPreserveTransactionalOwnership() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(base.cacheDir, "source-updates-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "")
        val context = object : android.content.ContextWrapper(base) {
            override fun getFilesDir() = File(fixture, "files").apply { mkdirs() }
            override fun getCacheDir() = File(fixture, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$suffix-$name", mode)
        }
        val id = "org.meilox.update-$suffix"
        val providerId = "test.update-$suffix"
        val repository = "https://github.com/Tests/Source"
        fun archive(version: String, extraNetwork: Boolean = false, title: String = "Fixture"): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                val manifest = """{"id":"$id","providerId":"$providerId","name":"Update fixture","author":"Tests",
                    "version":"$version","apiVersion":2,"capabilities":["catalog.track"],
                    "permissions":{"network":[${if (extraNetwork) "\"api.example.com\"" else ""}]},
                    "config":[{"key":"cookie","label":"Cookie","secret":true}]}"""
                for ((name, text) in mapOf("manifest.json" to manifest,
                    "index.js" to "globalThis.sourcePlugin={'catalog.track':function(p){return {trackId:p.trackId,title:'$title'};}};")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            return output.toByteArray()
        }
        fun entry(version: String, bytes: ByteArray) = com.ljyh.mei.source.update.SourceMarketEntry(id, providerId, "Update fixture", version,
            repository, "https://raw.githubusercontent.com/Tests/Source/${"a".repeat(40)}/fixture.meisource",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, 2)
        val first = archive("1.0.0")
        val second = archive("1.1.0")
        val expanded = archive("1.2.0", extraNetwork = true)
        var marketEntry = entry("1.0.0", first)
        var marketBytes = first
        var downloads = 0
        var offline = false
        val transport = object : com.ljyh.mei.source.update.SourceUpdateTransport {
            override suspend fun index(etag: String?): com.ljyh.mei.source.update.SourceIndexResponse {
                if (offline) throw IOException("private-market-fixture-secret")
                val json = com.google.gson.Gson().toJson(mapOf("schemaVersion" to 1, "sources" to listOf(marketEntry)))
                return com.ljyh.mei.source.update.SourceIndexResponse(json, "fixture-etag")
            }
            override suspend fun download(entry: com.ljyh.mei.source.update.SourceMarketEntry, destination: File) {
                downloads++
                destination.writeBytes(marketBytes)
            }
        }
        val registry = ProviderRegistry(emptyList())
        val manager = ScriptPluginManager(context, registry, ScriptRuntime(context))
        val work = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val updates = com.ljyh.mei.source.update.SourceUpdateManager(context, manager, registry, transport, work)
        suspend fun inspect(bytes: ByteArray): PreparedPlugin {
            val file = File.createTempFile("update-fixture-", ".meisource", context.cacheDir)
            try { file.writeBytes(bytes); return manager.inspect(Uri.fromFile(file)) }
            finally { file.delete() }
        }
        try {
            if (InstrumentationRegistry.getArguments().getString("sourceMarketLive") == "true") {
                val live = com.ljyh.mei.source.update.SourceMarketTransport()
                val entries = com.ljyh.mei.source.update.SourceMarketIndex.parse(checkNotNull(live.index(null).json))
                    .filter { it.providerId in setOf("netease", "qqmusic") }
                assertEquals("The published market must include both maintained sources", 2, entries.size)
                for (entry in entries) {
                    val download = File.createTempFile("market-live-", ".meisource", context.cacheDir)
                    var checked: PreparedPlugin? = null
                    try {
                        live.download(entry, download)
                        checked = manager.inspect(Uri.fromFile(download))
                        assertTrue(entry.matches(checked.manifest))
                    } finally {
                        checked?.let { manager.discardPrepared(it) }
                        download.delete()
                    }
                }
            }
            manager.install(inspect(first), approved = true)
            manager.configure(id, mapOf("cookie" to "fixture-local-only"))
            manager.setEnabled(id, false)
            // Same IDs and version alone must never transfer repository trust.
            marketBytes = archive("1.0.0", title = "Different payload")
            marketEntry = entry("1.0.0", marketBytes)
            updates.checkNow()
            assertNull(manager.updateSnapshot(id)!!.repository)
            marketBytes = first
            marketEntry = entry("1.0.0", first)
            updates.checkNow()
            assertEquals(repository, manager.updateSnapshot(id)!!.repository)
            val adoptedDownloads = downloads
            updates.checkNow()
            assertEquals("An authenticated current payload needs no repeat download", adoptedDownloads, downloads)

            marketEntry = entry("1.1.0", second); marketBytes = second
            updates.refreshMarket()
            assertEquals("Browsing the market cannot apply an update", "1.0.0", manager.updateSnapshot(id)!!.manifest.version)
            val revoked = manager.inspect(Uri.fromFile(File(context.cacheDir, "revoked-update.meisource").apply { writeBytes(second) }))
            try {
                manager.installMarket(revoked, marketEntry, manager.updateSnapshot(id), automatic = true, authorized = { false })
                fail("An authorization revoked while waiting must be checked inside the installation transaction")
            } catch (_: com.ljyh.mei.source.update.SourceUpdateDeferred) { }
            finally { manager.discardPrepared(revoked) }
            assertEquals("1.0.0", manager.updateSnapshot(id)!!.manifest.version)
            updates.checkNow()
            assertEquals("1.1.0", manager.updateSnapshot(id)!!.manifest.version)
            assertFalse(manager.updateSnapshot(id)!!.enabled)
            assertEquals(mapOf("cookie" to "fixture-local-only"), manager.configuration(id))

            marketEntry = entry("1.2.0", expanded); marketBytes = expanded
            updates.checkNow()
            assertEquals("1.1.0", manager.updateSnapshot(id)!!.manifest.version)
            assertTrue(updates.state.value.items.single().requiresReview)
            val review = updates.prepareUpdate(id)
            assertEquals(listOf(com.ljyh.mei.source.update.SourceUpdateReviewReason.NETWORK), review.reviewReasons)
            updates.installReviewed(review)
            updates.discardPrepared(review)
            assertEquals("1.2.0", manager.updateSnapshot(id)!!.manifest.version)
            assertFalse(manager.updateSnapshot(id)!!.enabled)

            val newer = archive("1.3.0", extraNetwork = true)
            marketEntry = entry("1.3.0", newer); marketBytes = newer
            updates.refreshMarket()
            val staleReview = updates.prepareUpdate(id)
            manager.install(inspect(newer), approved = true)
            assertNull("File imports cannot retain a previous market origin", manager.updateSnapshot(id)!!.repository)
            try { updates.installReviewed(staleReview); fail("A stale review must not replace the changed installation") }
            catch (_: SourceException) { }
            finally { updates.discardPrepared(staleReview) }

            manager.uninstall(id)
            val beforeRemovedCheck = downloads
            updates.checkNow()
            assertEquals(beforeRemovedCheck, downloads)
            assertNull(manager.updateSnapshot(id))
            marketBytes = first // Deliberately does not match the registry digest.
            try { updates.prepareMarketInstall(id); fail("Transport bytes need independent hash validation") }
            catch (_: SourceException) { }
            assertNull(manager.updateSnapshot(id))
            offline = true
            updates.checkNow()
            assertEquals("暂时无法连接音源市场", updates.state.value.message)
            assertFalse(updates.state.value.message.orEmpty().contains("private-market"))
            assertTrue(File(context.cacheDir, "source-import").listFiles().isNullOrEmpty())
        } finally {
            work.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            if (manager.installed.value.any { it.manifest.id == id }) manager.uninstall(id)
            base.deleteSharedPreferences("$suffix-source-update-settings")
            fixture.deleteRecursively()
        }
    }

    @Test
    fun rapidSandboxCancellationReconnectsAndOperationExceptionsKeepTheSourceAvailable() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = ScriptRuntime(context)
        val manifest = PluginManifest.parse("""{"id":"org.meilox.connection-retirement","name":"Runtime lifecycle fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track","playback.resolve"]}""")
        val script = """globalThis.sourcePlugin={'catalog.track':function(p) {
            if(p.trackId==='broken')throw new Error('private-evaluation-fixture');
            if(p.trackId==='optional-artwork')return {trackId:p.trackId,title:'Valid catalog',artworkUrl:'https://undeclared.invalid/cover'};
            return {trackId:p.trackId,title:'Healthy source'};
        },'playback.resolve':function(){return {uri:'https://undeclared.invalid/audio.mp3',quality:'standard'};}};"""
        val directory = File(context.cacheDir, "source-runtime-retirement-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(directory, "index.js").writeText(script)
            val parameters = JsonObject().apply { addProperty("trackId", "healthy") }
            withTimeout(90_000) {
                repeat(6) {
                    val interrupted = launch(start = CoroutineStart.UNDISPATCHED) {
                        runtime.invoke(script, manifest, "catalog.track", parameters, emptyMap(), emptyMap(), ScriptNetworkPolicy(emptyList()))
                    }
                    yield()
                    interrupted.cancelAndJoin()
                    val healthy = runtime.invoke(script, manifest, "catalog.track", parameters, emptyMap(), emptyMap(), ScriptNetworkPolicy(emptyList()))
                    assertEquals("Healthy source", healthy.result.asJsonObject["title"].asString)
                }
                val provider = ScriptMusicProvider(manifest, directory, runtime, true, emptyMap(), { true },
                    { emptyMap() }, { emptyMap() }, {}, { fail("An ordinary JS operation error must not fail the source") }, 0)
                try { provider.getTrack(TrackRef(manifest.providerId, "broken")); fail("Expected the operation error") }
                catch (error: SourceException) {
                    assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code)
                    assertFalse(error.message.orEmpty().contains("private-evaluation-fixture"))
                    assertNull(error.cause)
                }
                assertFalse(provider.failed)
                assertEquals(SourceStatus.READY, provider.status)
                assertEquals("Healthy source", provider.getTrack(TrackRef(manifest.providerId, "healthy"))?.title)
                val withoutArtwork = provider.getTrack(TrackRef(manifest.providerId, "optional-artwork"))
                assertEquals("Valid catalog", withoutArtwork?.title)
                assertEquals("", withoutArtwork?.artworkUrl)
                assertEquals(SourceStatus.READY, provider.status)
                try { provider.resolve(TrackRef(manifest.providerId, "denied-audio"), "standard"); fail("Undeclared audio must be rejected") }
                catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
                assertEquals(SourceStatus.READY, provider.status)
                assertEquals("Healthy source", provider.getTrack(TrackRef(manifest.providerId, "healthy"))?.title)
                try {
                    runtime.invoke("""globalThis.sourcePlugin={'catalog.track':function(){return {error:'UNAVAILABLE',outcomeUnknown:true};}};""",
                        manifest, "catalog.track", parameters, emptyMap(), emptyMap(), ScriptNetworkPolicy(emptyList()))
                    fail("Expected the explicit business failure")
                } catch (error: SourceException) {
                    assertEquals(SourceErrorCode.UNAVAILABLE, error.code)
                    assertFalse("Scripts must not set host-only mutation ambiguity metadata", error.outcomeUnknown)
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun lazyArtworkSurvivesStoreRestartAndSourceUpgradeWithoutExposingItsDescriptor() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "org.meilox.artwork-${java.util.UUID.randomUUID()}"
        val neighbor = "$id-artwork-other"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val artwork = File(context.cacheDir, "source-artwork/$id")
        val registry = ProviderRegistry(emptyList())
        val firstStore = SourceArtworkStore(context, registry)
        fun provider(version: String, session: String, store: SourceArtworkStore): ScriptMusicProvider {
            val manifest = PluginManifest.parse("""{"id":"$id","name":"Artwork fixture","author":"Tests",
                "version":"$version","apiVersion":2,"capabilities":["catalog.track"],"sessionKeys":["session"],
                "permissions":{"network":["cdn.example"],"storage":true}}""")
            return ScriptMusicProvider(manifest, directory, ScriptRuntime(context), true, emptyMap(), { true },
                { emptyMap() }, { mapOf("session" to session) }, {}, { fail("Artwork must not fail the source") }, 0,
                artworkDirectory = artwork, artworkStore = store).also(registry::register)
        }
        try {
            val first = provider("1", "account-a", firstStore)
            val uri = first.featureArtwork("https://cdn.example/cover.jpg?token=fixture-private-token")
            val file = File(java.net.URI(uri))
            assertFalse(uri.contains("fixture-private-token"))
            assertFalse("Catalog registration must not download its images", file.exists())
            val restored = SourceArtworkStore(context, registry)
            assertEquals(uri, restored.acceptOwned(first, uri))
            val upgraded = provider("2", "account-a", restored)
            assertEquals(uri, restored.acceptOwned(upgraded, uri))
            val switched = provider("2", "account-b", restored)
            try { restored.acceptOwned(switched, uri); fail("A missing cover cannot reuse another account's descriptor") }
            catch (_: CancellationException) { }
            file.parentFile.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
            val secrets = PluginSecretStore(context)
            val neighborRecord = mapOf("url" to "https://cdn.example/other.jpg", "account" to "another-owner")
            secrets.write(neighbor, "artwork-${file.name}", neighborRecord)
            secrets.delete(id)
            assertEquals("Uninstall must not delete a package with a similar name", neighborRecord,
                secrets.read(neighbor, "artwork-${file.name}"))
            registry.unregister(switched.instanceId)
            assertEquals("Downloaded covers remain available without the source or its descriptor", uri, restored.resolve(uri))
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally {
            PluginSecretStore(context).delete(id)
            PluginSecretStore(context).delete(neighbor)
            artwork.deleteRecursively(); directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledLoginPublishesTheAccountRevisionAfterDurableStorageCommit() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = PluginManifest.parse("""{"id":"org.meilox.login-commit","name":"Login commit fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["feature.invoke","track.like"],
            "features":{"login":["complete"]},"sessionKeys":["session"],"permissions":{"storage":true}}""")
        val directory = File(context.cacheDir, "source-login-commit-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val state = java.util.concurrent.atomic.AtomicReference(mapOf("session" to "first"))
        val committed = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            File(directory, "index.js").writeText("""globalThis.sourcePlugin = {
                'feature.invoke':function() { return {result:true,storage:{session:'second'}}; },
                'track.like':function() { return {result:true,storage:{session:'second',write:'unexpected'}}; }
            };""")
            val provider = ScriptMusicProvider(manifest, directory, ScriptRuntime(context), true, emptyMap(), { true },
                { emptyMap() }, { state.get() }, { values ->
                    state.set(values.toMap()); committed.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }, { fail("Cancelling login must not fail the source") }, 0)
            val expected = provider.accountRevision.value
            withTimeout(30_000) {
                val login = async {
                    provider.invokeFeature("login", "complete", JsonObject(), expected) {
                        throw CancellationException("The caller left after its storage commit")
                    }
                }
                try {
                    committed.await()
                    login.cancel()
                    release.countDown()
                    login.join()
                    assertNotEquals(expected, provider.accountRevision.value)
                    try { provider.setLiked(TrackRef(manifest.providerId, "song"), true, expected); fail("Old account must be rejected") }
                    catch (_: CancellationException) { }
                    assertEquals(mapOf("session" to "second"), state.get())
                    assertEquals(SourceStatus.READY, provider.status)
                } finally { release.countDown(); login.cancelAndJoin() }
            }
        } finally { release.countDown(); directory.deleteRecursively() }
    }

    @Test
    fun coreMutationsResumeFreshHostProofWithoutHoldingTheSourceOperationLock() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = PluginManifest.parse("""{"id":"org.meilox.core-proof","name":"Core proof fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track","collection.save","account.get"],"collectionTypes":["playlist"],
            "permissions":{"effects":["sdk.call","web.page"],"nativeSdks":["netease.securityHeaders"]}}""")
        val directory = File(context.cacheDir, "source-core-proof-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val registry = com.ljyh.mei.source.core.ProviderRegistry(emptyList())
        val executor = com.ljyh.mei.source.core.SourceFeatureExecutor(com.ljyh.mei.source.core.SourceRouter(registry))
        lateinit var provider: ScriptMusicProvider
        var effects = 0
        try {
            File(directory, "index.js").writeText("""globalThis.sourcePlugin = {
                'catalog.track':function(p) { return {trackId:p.trackId,title:'Concurrent catalog'}; },
                'account.get':function() { return {hostEffect:{type:'web.page',parameters:{}},continuation:{}}; },
                'collection.save':function(p) {
                    if (!p['${'$'}continuation']) return {hostEffect:{type:'sdk.call',parameters:{name:'netease.securityHeaders'}},continuation:{saved:p.saved}};
                    const reply=p['${'$'}continuation'];
                    return reply.state.saved===true && reply.effect.ok===true && reply.effect.result.checkToken==='body-proof' && reply.effect.result.headerAntiCheatToken==='header-proof';
                }
            };""")
            provider = ScriptMusicProvider(manifest, directory, ScriptRuntime(context), true, emptyMap(), { true },
                { emptyMap() }, { emptyMap() }, {}, { fail("Valid core proof must not fail the source") }, 0,
                coreExecutor = executor, coreHandler = com.ljyh.mei.source.api.SourceHostEffectHandler { _, scope ->
                    effects++
                    assertEquals("core", scope.feature)
                    assertEquals("collection.save", scope.action)
                    assertEquals("Concurrent catalog", provider.getTrack(TrackRef(manifest.providerId, "other"))?.title)
                    com.ljyh.mei.source.api.SourceHostEffectResult.Success(JsonObject().apply {
                        addProperty("checkToken", "body-proof"); addProperty("headerAntiCheatToken", "header-proof")
                    })
                })
            registry.register(provider)
            withTimeout(30_000) { provider.setSaved(com.ljyh.mei.source.api.PlaylistRef(manifest.providerId, "list"), true) }
            try { provider.accountWithoutEffects(provider.accountRevision.value); fail("Bridge metadata cannot recursively open a Web effect") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
            assertEquals(1, effects)
            assertEquals(SourceStatus.READY, provider.status)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun removableDefaultSourcePreservesHostDataAndDoesNotReinstallItself() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = (context.applicationContext as AppContext).sourcePlugins
        manager.loadInstalled()
        val id = BundledSourceProvisioner.NETEASE_ID
        val original = manager.installed.value.firstOrNull { it.manifest.id == id }
        assumeTrue("The reviewed default source must be installed", original != null)
        val root = File(context.filesDir, "music-sources")
        val marker = File(root, ".netease-default-v1")
        val markerBefore = marker.takeIf { it.isFile }?.readBytes()
        val configuration = manager.configuration(id)
        val secrets = PluginSecretStore(context)
        val storage = secrets.read(id, "storage")
        val preferences = context.dataStore.data.first()
        val hostBefore = sourceHostDataFingerprint(context)
        val packageFile = File.createTempFile("default-source-restore-", ".meisource", context.cacheDir)
        context.assets.open("music-sources/netease.meisource").use { input -> packageFile.outputStream().use { output -> input.copyTo(output) } }
        assertTrue(BundledSourceProvisioner.matches(packageFile.readBytes()))
        val reviewedEntry = java.util.zip.ZipInputStream(packageFile.inputStream()).use { zip ->
            var script: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "index.js") script = zip.readBytes()
            }
            script
        }
        assumeTrue("Preserve independently installed source versions", reviewedEntry != null &&
            reviewedEntry.contentEquals(File(root, "$id/index.js").readBytes()))
        var removed = false
        try {
            manager.uninstall(id)
            removed = true
            assertTrue("Default source must be removable", manager.installed.value.none { it.manifest.id == id })
            manager.loadInstalled()
            assertTrue("An explicit uninstall must survive initialization", manager.installed.value.none { it.manifest.id == id })
            assertTrue("Host preferences and queue must survive source removal", preferences == context.dataStore.data.first())
            assertTrue("Host database rows must survive source removal", hostBefore.contentEquals(sourceHostDataFingerprint(context)))
        } finally {
            if (removed) {
                // Restore the exact private session without exposing it to assertion output.
                secrets.write(id, "config", configuration)
                secrets.write(id, "storage", storage)
                val prepared = manager.inspect(Uri.fromFile(packageFile))
                try { manager.install(prepared, approved = true) }
                finally { prepared.directory.deleteRecursively() }
                if (original?.enabled == false) manager.setEnabled(id, false)
                if (markerBefore == null) marker.delete() else marker.writeBytes(markerBefore)
                assertTrue("The private source configuration must be restored", configuration == manager.configuration(id))
                assertTrue("The private source storage must be restored", storage == secrets.read(id, "storage"))
            }
            packageFile.delete()
        }
    }

    private fun sourceHostDataFingerprint(context: android.content.Context): ByteArray {
        val database = com.ljyh.mei.di.AppDatabase.getDatabase(context).openHelper.readableDatabase
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val tables = database.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (table in tables) {
            digest.update(table.toByteArray())
            database.query("""SELECT * FROM "$table" ORDER BY rowid""").use { cursor ->
                while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) {
                    val kind = cursor.getType(column)
                    val bytes = if (kind == android.database.Cursor.FIELD_TYPE_NULL) byteArrayOf()
                        else if (kind == android.database.Cursor.FIELD_TYPE_BLOB) cursor.getBlob(column)
                        else cursor.getString(column).toByteArray()
                    digest.update(kind.toByte())
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digest.update(bytes)
                }
            }
        }
        return digest.digest()
    }

    @Test
    fun malformedFavoriteResponsesKeepOtherCapabilitiesButPermissionViolationsFailClosed() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = PluginManifest.parse("""{"id":"org.meilox.favorite-errors","name":"Favorite fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track","track.like"],"permissions":{"network":["api.example.com"]}}""")
        val directory = File(context.cacheDir, "source-favorite-errors-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val failures = mutableListOf<SourceErrorCode>()
        try {
            File(directory, "index.js").writeText("""globalThis.sourcePlugin = {
                'catalog.track':function(params) {
                    if(params.trackId === 'denied') return {request:{url:'https://undeclared.example.com/private'}};
                    return {trackId:params.trackId,title:'Healthy catalog'};
                },
                'track.like':function() { return {accepted:true}; }
            };""".trimIndent())
            val provider = ScriptMusicProvider(manifest, directory, ScriptRuntime(context), enabled = true,
                configurationSnapshot = emptyMap(), isCurrent = { true }, config = { emptyMap() }, storage = { emptyMap() },
                saveStorage = {}, onFailure = { failures += it }, configurationVersion = 0)
            try { provider.setLiked(TrackRef(manifest.providerId, "song"), true); fail("Expected a malformed operation result") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
            assertEquals(SourceStatus.READY, provider.status)
            assertFalse(provider.failed)
            assertEquals("Healthy catalog", provider.getTrack(TrackRef(manifest.providerId, "healthy"))?.title)
            assertTrue(failures.isEmpty())
            try { provider.getTrack(TrackRef(manifest.providerId, "denied")); fail("Expected a network permission rejection") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
            assertTrue(provider.failed)
            assertEquals(listOf(SourceErrorCode.PLUGIN), failures)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun queuedMutationsCheckTheAccountAfterThePreviousStorageCommit() = runBlocking {
        assumeTrue(JavaScriptSandbox.isSupported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = PluginManifest.parse("""{"id":"org.meilox.favorite-account","name":"Account fixture","author":"Tests",
            "version":"1","apiVersion":2,"capabilities":["catalog.track","track.like","feature.invoke"],
            "features":{"login":["logout"]},"sessionKeys":["session"],"permissions":{"storage":true}}""")
        val directory = File(context.cacheDir, "source-favorite-account-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val state = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(mapOf("session" to "first"))
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            File(directory, "index.js").writeText("""globalThis.sourcePlugin = {
                'catalog.track':function(params) { return {result:{trackId:params.trackId,title:'Changed account'},storage:{session:'second'}}; },
                'track.like':function(params,context) { return {result:true,storage:{session:context.storage.session,write:'unexpected'}}; },
                'feature.invoke':function(params,context) { return {result:true,storage:{session:'',write:'unexpected'}}; }
            };""".trimIndent())
            val provider = ScriptMusicProvider(manifest, directory, ScriptRuntime(context), enabled = true,
                configurationSnapshot = emptyMap(), isCurrent = { true }, config = { emptyMap() }, storage = { state.get() },
                saveStorage = { values ->
                    entered.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    state.set(values.toMap())
                }, onFailure = { fail("An account replacement must not fail the provider") }, configurationVersion = 0)
            val expected = provider.accountRevision.value
            withTimeout(30_000) {
                val changingAccount = async { provider.getTrack(TrackRef(manifest.providerId, "seed")) }
                try {
                    entered.await()
                    val mutation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        provider.setLiked(TrackRef(manifest.providerId, "song"), true, expected)
                    }
                    val feature = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        provider.invokeFeature("login", "logout", JsonObject(), expected, null)
                    }
                    try {
                        release.countDown()
                        assertNotNull(changingAccount.await())
                        try { mutation.await(); fail("Expected the queued mutation to cancel") }
                        catch (_: CancellationException) { }
                        try { feature.await(); fail("Expected the queued session mutation to cancel") }
                        catch (_: CancellationException) { }
                        assertNotEquals(expected, provider.accountRevision.value)
                        assertEquals(mapOf("session" to "second"), state.get())
                        assertEquals(SourceStatus.READY, provider.status)
                    } finally { mutation.cancelAndJoin(); feature.cancelAndJoin() }
                } finally { release.countDown(); changingAccount.cancelAndJoin() }
            }
        } finally { release.countDown(); directory.deleteRecursively() }
    }

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
            "version":"1","apiVersion":2,"capabilities":["catalog.track"],"permissions":{"network":["api.example.com"],"storage":true}}""")
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
                if(params.trackId === 'oversized') return {trackId:'oversized',title:'x'.repeat(300000)};
                if(params.trackId === 'storage') return {result:{trackId:'storage',title:'Fixture'},storage:{large:'x'.repeat(65536)}};
                if(params.trackId !== 'healthy') return {error:params.trackId};
                return {trackId:'healthy',title:'Recovered source'};
            }};""".trimIndent())
            val provider = ScriptMusicProvider(manifest, directory, runtime, enabled = true,
                configurationSnapshot = emptyMap(), isCurrent = { true }, config = { emptyMap() }, storage = { emptyMap() },
                saveStorage = {}, onFailure = { failureCodes += it }, configurationVersion = 0)
            val cases = failures.map { it.second.name to it.second } + listOf(
                "oversized" to SourceErrorCode.INVALID_RESPONSE, "storage" to SourceErrorCode.INVALID_RESPONSE)
            for ((trackId, expected) in cases) {
                try { provider.getTrack(TrackRef(manifest.providerId, trackId)); fail("Expected a bounded operation failure") }
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
