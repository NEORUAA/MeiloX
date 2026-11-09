package com.ljyh.mei.source.script

import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import java.net.InetAddress
import java.util.Base64
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import com.ljyh.mei.data.network.NeteaseWireCodec
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody

class ScriptPluginPolicyTest {
    @Test fun typedCatalogCacheSeparatesOwnersAndRejectsRetiredWritesAndAccountRebinds() {
        val cache = SourceCatalogCache()
        val first = cache.bind("source-a", "1+7", "account-a")
        val other = cache.bind("source-b", "1+7", "account-a")
        val track = Track(TrackRef("source", "opaque/A"), "Private A", emptyList())
        cache.putTracks(first, listOf(track))
        assertTrue(cache.tracks(other, listOf(track.ref)).isEmpty())
        val replacement = cache.bind("source-a", "1+8", "account-a")
        assertTrue(cache.tracks(replacement, listOf(track.ref)).isEmpty())
        val current = track.copy(title = "Current owner")
        cache.putTracks(replacement, listOf(current))
        for (action in listOf<() -> Unit>(
            { cache.putTracks(first, listOf(track)) },
            { cache.rebind(first, "late-account") },
            { cache.ensureOwner(first) },
        )) {
            try { action(); fail("A retired invocation must not mutate or replace the current scope") }
            catch (_: CancellationException) { }
        }
        cache.release(first)
        assertEquals(current, cache.tracks(replacement, listOf(track.ref))[track.ref])
        var checks = 0
        try {
            cache.putTracks(replacement, listOf(track)) {
                if (++checks == 2) throw CancellationException("Cancelled before commit")
            }
            fail("Cancellation must precede a cache mutation")
        } catch (_: CancellationException) { }
        assertEquals(current, cache.tracks(replacement, listOf(track.ref))[track.ref])
        val sameAccount = cache.rebind(replacement, "account-a")
        assertNotSame(replacement, sameAccount)
        assertTrue(cache.tracks(sameAccount, listOf(track.ref)).isEmpty())
        try { cache.rebind(replacement, "late-account"); fail("Same-account rebinding must retire the old token") }
        catch (_: CancellationException) { }
        cache.putTracks(sameAccount, listOf(track))
        val nextAccount = cache.rebind(sameAccount, "account-b")
        assertTrue(cache.tracks(nextAccount, listOf(track.ref)).isEmpty())
        cache.release(nextAccount)
        try { cache.account(nextAccount); fail("Released scopes must not serve account or catalog data") }
        catch (_: CancellationException) { }
    }

    @Test fun typedCatalogCacheBoundsMemoryAndEvictsLeastRecentlyUsedTracksWithoutATtl() {
        var now = 1_000L
        val cache = SourceCatalogCache { now }
        val owner = cache.bind("source", "1", null)
        val account = SourceAccount(SourceAccountRef(ProviderInstanceId("source"), ProviderAccountId("verified-user")), "User")
        cache.putAccount(owner, account)
        val title = "x".repeat((SourceCatalogCache.MAX_BYTES - 16_384) / 8)
        fun track(id: String) = Track(TrackRef("source", id), title, emptyList())
        val tracks = listOf("a", "b", "c", "d").map(::track)
        cache.putTracks(owner, tracks)
        assertEquals(4, cache.tracks(owner, tracks.map { it.ref }).size)
        assertEquals(tracks.first(), cache.tracks(owner, listOf(tracks.first().ref))[tracks.first().ref])
        cache.putTracks(owner, listOf(track("e")))
        assertFalse(cache.tracks(owner, listOf(TrackRef("source", "b"))).containsKey(TrackRef("source", "b")))
        assertEquals(tracks.first(), cache.tracks(owner, listOf(tracks.first().ref))[tracks.first().ref])
        cache.putTracks(owner, listOf(track("huge").copy(title = "雪".repeat(SourceCatalogCache.MAX_BYTES / 2))))
        assertTrue(cache.tracks(owner, listOf(TrackRef("source", "huge"))).isEmpty())
        assertEquals(tracks.first(), cache.tracks(owner, listOf(tracks.first().ref))[tracks.first().ref])
        now += 10 * SourceCatalogCache.PAGE_TTL_MS
        assertEquals(tracks.first(), cache.tracks(owner, listOf(tracks.first().ref))[tracks.first().ref])
        assertEquals(true to account, cache.account(owner))
        assertEquals("verified-user", cache.account(owner).second?.ref?.accountId?.value)
        assertEquals(8192, SourceCatalogCache.MAX_ENTRIES)
    }

    @Test fun typedCatalogCachePreservesCompleteIndexesPageIdentityAndVerifiedAccounts() {
        var now = 1_000L
        val cache = SourceCatalogCache { now }
        val owner = cache.bind("source", "1", "account")
        val artists = mutableListOf(SourceArtist("Artist", ArtistRef("source", "artist/A")))
        val track = Track(TrackRef("source", "opaque/A"), "A", artists, artworkUrl = "owned:art/A")
        val second = track.copy(ref = TrackRef("source", "opaque/B"), title = "B")
        cache.putTracks(owner, listOf(track, second))
        artists.clear()
        val refs = listOf(second.ref, TrackRef("source", "missing"), track.ref, track.ref)
        val hits = cache.tracks(owner, refs)
        assertEquals(listOf("B", "A", "A"), refs.mapNotNull(hits::get).map { it.title })
        assertEquals("owned:art/A", hits[track.ref]?.artworkUrl)
        assertEquals("Artist", hits[track.ref]?.artists?.single()?.name)
        val playlist = SourcePlaylist(PlaylistRef("source", "playlist"), "Playlist", trackCount = 1534)
        cache.putSnapshot(owner, SourceCatalogCache.Snapshot(playlist, null))
        assertNull(cache.snapshot(owner, "playlist")?.ids)
        cache.putSnapshot(owner, SourceCatalogCache.Snapshot(playlist, emptyList()))
        assertEquals(emptyList<TrackRef>(), cache.snapshot(owner, "playlist")?.ids)
        val ids = (1..1534).map { TrackRef("source", "opaque/$it") }.toMutableList()
        cache.putSnapshot(owner, SourceCatalogCache.Snapshot(playlist, ids, metadataLimit = 5000))
        ids.clear()
        assertEquals(1534, cache.snapshot(owner, "playlist")?.ids?.size)
        assertEquals(5000, cache.snapshot(owner, "playlist")?.metadataLimit)
        cache.putLikedPlaylist(owner, "playlist")
        val key = SourceCatalogCache.PageKey("playlist.tracks", "playlist", PageRequest())
        val page = SourcePage<SourceItem>(listOf(SourceItem.TrackItem(second)), hasMore = true, nextCursor = "next-page")
        cache.putPage(owner, key, page)
        assertEquals(page, cache.page(owner, key))
        assertNull(cache.page(owner, key.copy(page = PageRequest(offset = 30))))
        assertNull(cache.page(owner, key.copy(identity = "another-playlist")))
        assertNull(cache.page(owner, key.copy(operation = "library.likedTracks")))
        assertEquals(false to null, cache.account(owner))
        cache.putAccount(owner, null)
        assertEquals(true to null, cache.account(owner))
        val account = SourceAccount(SourceAccountRef(ProviderInstanceId("source"), ProviderAccountId("account")), "User")
        cache.putAccount(owner, account)
        now += SourceCatalogCache.PAGE_TTL_MS
        assertNull(cache.page(owner, key))
        assertEquals(1534, cache.snapshot(owner, "playlist")?.ids?.size)
        assertEquals(true to account, cache.account(owner))
        cache.putPage(owner, key, page)
        cache.invalidateCatalog(owner)
        assertNull(cache.snapshot(owner, "playlist"))
        assertNull(cache.likedPlaylist(owner))
        assertNull(cache.page(owner, key))
        assertEquals(true to account, cache.account(owner))
        assertEquals(setOf(track.ref, second.ref), cache.tracks(owner, refs).keys)
        val next = cache.rebind(owner, "next-account")
        assertEquals(false to null, cache.account(next))
        assertTrue(cache.tracks(next, refs).isEmpty())
    }

    @Test fun largeCatalogResultsStayExplicitlyDeclaredAndCannotExpandProtocolState() {
        assertEquals(32L * 1024 * 1024, ScriptRuntime.MAX_HEAP_BYTES)
        assertEquals(256 * 1024, ScriptRuntime.MAX_RESULT_BYTES)
        assertEquals(2 * 1024 * 1024, ScriptRuntime.MAX_CATALOG_RESULT_BYTES)
        val manifest = PluginManifest("example.source", name = "Source", author = "Author", version = "1", apiVersion = 2,
            capabilities = setOf("playlist.get", "catalog.tracks", "library.likedTracks", "catalog.track", "feature.invoke"))
        for (operation in listOf("playlist.get", "catalog.tracks")) {
            assertEquals(ScriptRuntime.MAX_CATALOG_RESULT_BYTES, scriptResultLimit(manifest, operation, json("{}")))
            assertEquals(ScriptRuntime.MAX_RESULT_BYTES, scriptResultLimit(manifest.copy(capabilities = emptySet()), operation, json("{}")))
        }
        assertEquals(ScriptRuntime.MAX_CATALOG_RESULT_BYTES, scriptResultLimit(manifest, "library.likedTracks", json("{\"metadataOnly\":true}")))
        for (operation in listOf("library.likedTracks", "catalog.track", "feature.invoke")) {
            assertEquals(ScriptRuntime.MAX_RESULT_BYTES, scriptResultLimit(manifest, operation, json("{}")))
        }
        assertEquals(ScriptRuntime.MAX_RESULT_BYTES, scriptResultLimit(manifest, "library.likedTracks", json("{\"metadataOnly\":\"true\"}")))
        val large = "x".repeat(600 * 1024)
        val result = com.google.gson.JsonObject().apply { addProperty("result", large) }.toString()
        rejects { parseScriptResult(result) }
        assertEquals(large, parseScriptResult(result, ScriptRuntime.MAX_CATALOG_RESULT_BYTES).asJsonObject["result"].asString)
        rejects { parseScriptResult(result, ScriptRuntime.MAX_CATALOG_RESULT_BYTES + 1) }
        rejects { parseScriptResult(com.google.gson.JsonObject().apply {
            addProperty("result", "雪".repeat(ScriptRuntime.MAX_CATALOG_RESULT_BYTES / 3 + 1))
        }.toString(), ScriptRuntime.MAX_CATALOG_RESULT_BYTES) }
        rejects { parseScriptResult("""{"request":{"cache":{"op":"get","key":"catalog"}}}""", ScriptRuntime.MAX_CATALOG_RESULT_BYTES) }
        rejects { parseScriptResult(com.google.gson.JsonObject().apply {
            add("request", com.google.gson.JsonObject().apply { addProperty("url", "https://example.com/"); addProperty("body", large) })
            add("state", json("{}"))
        }.toString(), ScriptRuntime.MAX_CATALOG_RESULT_BYTES) }
        rejects { parseScriptResult(com.google.gson.JsonObject().apply {
            add("result", com.google.gson.JsonObject().apply {
                add("hostEffect", json("{\"type\":\"source.continue\"}")); addProperty("continuation", large)
            })
        }.toString(), ScriptRuntime.MAX_CATALOG_RESULT_BYTES) }
        rejects { parseScriptResult(com.google.gson.JsonObject().apply {
            add("request", json("{\"url\":\"https://example.com/\"}")); addProperty("state", "x".repeat(65_537))
        }.toString(), ScriptRuntime.MAX_CATALOG_RESULT_BYTES) }
        val declared = json(minimal.replace("\"apiVersion\":1", "\"apiVersion\":2")).apply {
            add("capabilities", com.google.gson.JsonArray().apply { add("catalog.track"); add("catalog.tracks") })
            addProperty("catalogBatchSize", 1000)
            add("discardStorageKeys", com.google.gson.JsonArray().apply { add("profile") })
        }
        assertEquals(1000, PluginManifest.parse(declared.toString()).catalogBatchSize)
        assertEquals(setOf("profile"), PluginManifest.parse(declared.toString()).discardStorageKeys)
        rejects { PluginManifest.parse(declared.deepCopy().apply { addProperty("catalogBatchSize", 1001) }.toString()) }
        rejects { PluginManifest.parse(declared.deepCopy().apply {
            add("sessionKeys", com.google.gson.JsonArray().apply { add("profile") })
            add("permissions", json("{\"storage\":true}"))
        }.toString()) }
    }

    @Test fun onlyOptedInResponseSizeFailuresJoinTheExistingNetworkRecoveryProtocol() {
        val size = SourceResponseSizeException()
        assertNull(scriptErrorResponse(size, false))
        assertEquals("RESPONSE_TOO_LARGE", scriptErrorResponse(size, true)?.get("error")?.asString)
        for (code in listOf(SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT)) {
            assertEquals(code.name, scriptErrorResponse(SourceException(code), true)?.get("error")?.asString)
            assertNull(scriptErrorResponse(SourceException(code), false))
        }
        for (code in listOf(SourceErrorCode.INVALID_RESPONSE, SourceErrorCode.PLUGIN, SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED)) {
            assertNull(scriptErrorResponse(SourceException(code), true))
        }
    }

    @Test fun queuedScriptOperationsKeepTheirExecutionBudgetAndCanBeCancelledWhileWaiting() = runTest {
        val operations = Mutex()
        var cancelledOperationStarted = false
        val first = async { operations.withScriptOperationTimeout(200) { delay(150); "first" } }
        val second = async { operations.withScriptOperationTimeout(100) { delay(75); "second" } }
        val cancelled = async {
            operations.withScriptOperationTimeout(100) { cancelledOperationStarted = true }
        }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        cancelled.cancelAndJoin()
        advanceUntilIdle()
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(225L, testScheduler.currentTime)
        assertFalse(cancelledOperationStarted)
    }

    @Test fun scriptExecutionTimeoutAndCallerCancellationReleaseTheOperationLock() = runTest {
        val operations = Mutex()
        val expired = async {
            runCatching { operations.withScriptOperationTimeout(100) { delay(101) } }
        }
        advanceUntilIdle()
        assertTrue(expired.await().exceptionOrNull() is TimeoutCancellationException)
        assertEquals("recovered", operations.withScriptOperationTimeout(100) { "recovered" })

        var entered = false
        val cancelled = async {
            operations.withScriptOperationTimeout(1000) { entered = true; awaitCancellation() }
        }
        runCurrent()
        assertTrue(entered)
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertEquals("after cancellation", operations.withScriptOperationTimeout(100) { "after cancellation" })
    }

    @Test fun cancelledBindingsRetireTheirLateSandboxBeforeAnotherConnectionStarts() = runTest {
        for (completedBeforeCancellation in listOf(false, true)) {
            val gate = ScriptConnectionGate()
            val pending = com.google.common.util.concurrent.SettableFuture.create<java.io.Closeable>()
            var created = 0
            var retired = 0
            val oldSandbox = java.io.Closeable { retired++ }
            val currentSandbox = java.io.Closeable { }
            val old = async { gate.connect { created++; pending } }
            runCurrent()
            if (completedBeforeCancellation) pending.set(oldSandbox)
            old.cancelAndJoin()
            assertFalse("Cancelling a waiter must not cancel AndroidX's binding future", pending.isCancelled)
            val next = async { gate.connect { created++; com.google.common.util.concurrent.Futures.immediateFuture(currentSandbox) } }
            runCurrent()
            if (!completedBeforeCancellation) {
                assertEquals(1, created)
                assertFalse(next.isCompleted)
                val abandoned = async { gate.connect<java.io.Closeable> { error("A cancelled successor must not open another binding") } }
                runCurrent()
                abandoned.cancelAndJoin()
                pending.set(oldSandbox)
            }
            val lease = next.await()
            assertSame(currentSandbox, lease.value)
            assertEquals(1, retired)
            assertEquals(2, created)
            lease.close()
        }
    }

    @Test fun failedLateAndNormalClosesKeepTheBindingRetiredAndSanitizeTheirFailures() = runTest {
        val secret = "private-framework-close-message"
        for (lateClose in listOf(false, true)) {
            val gate = ScriptConnectionGate()
            var closes = 0
            var factories = 0
            val sandbox = java.io.Closeable { closes++; throw IllegalStateException(secret) }
            if (lateClose) {
                val pending = com.google.common.util.concurrent.SettableFuture.create<java.io.Closeable>()
                val waiting = async { gate.connect { factories++; pending } }
                runCurrent()
                waiting.cancelAndJoin()
                pending.set(sandbox)
                runCurrent()
            } else {
                val lease = gate.connect { factories++; com.google.common.util.concurrent.Futures.immediateFuture(sandbox) }
                val failure = runCatching { lease.close() }.exceptionOrNull() ?: error("Expected the failed close")
                val result = scriptRuntimeFailure(failure, ScriptRuntimePhase.REQUEST, requestSent = true)
                assertEquals(SourceErrorCode.UNAVAILABLE, result.code)
                assertTrue(result.outcomeUnknown)
                assertFalse(result.message.orEmpty().contains(secret))
                assertNull(result.cause)
                lease.close()
            }
            repeat(2) {
                try {
                    gate.connect<java.io.Closeable> { factories++; error("A failed unbind must not be retried") }
                    fail("Expected the retired connection failure")
                } catch (failure: Exception) {
                    val result = scriptRuntimeFailure(failure, ScriptRuntimePhase.CONNECTION)
                    assertEquals(SourceErrorCode.UNAVAILABLE, result.code)
                    assertFalse(result.outcomeUnknown)
                    assertFalse(result.message.orEmpty().contains(secret))
                    assertNull(result.cause)
                }
            }
            assertEquals(1, factories)
            assertEquals(1, closes)
        }
    }

    @Test fun engineAvailabilityDoesNotRevokeSourcesAndResourceViolationsRemainFatal() {
        val secret = "private-session-in-engine-message"
        val failures = listOf(
            androidx.javascriptengine.SandboxDeadException(secret) to SourceErrorCode.UNAVAILABLE,
            androidx.javascriptengine.IsolateTerminatedException(secret) to SourceErrorCode.UNAVAILABLE,
            androidx.javascriptengine.DataInputException(secret) to SourceErrorCode.UNAVAILABLE,
            IllegalStateException(secret) to SourceErrorCode.UNAVAILABLE,
            androidx.javascriptengine.SandboxUnsupportedException(secret) to SourceErrorCode.UNSUPPORTED,
            androidx.javascriptengine.MemoryLimitExceededException(secret) to SourceErrorCode.PLUGIN,
            androidx.javascriptengine.EvaluationResultSizeLimitExceededException(secret) to SourceErrorCode.INVALID_RESPONSE,
        )
        for ((failure, code) in failures) {
            val result = scriptRuntimeFailure(java.util.concurrent.ExecutionException(failure), ScriptRuntimePhase.CONNECTION)
            assertEquals(code, result.code)
            assertFalse(result.message.orEmpty().contains(secret))
            assertNull(result.cause)
            assertFalse(result.outcomeUnknown)
        }
        val evaluation = androidx.javascriptengine.EvaluationFailedException(secret)
        assertEquals(SourceErrorCode.PLUGIN, scriptRuntimeFailure(evaluation, ScriptRuntimePhase.SCRIPT).code)
        assertEquals(SourceErrorCode.INVALID_RESPONSE, scriptRuntimeFailure(evaluation, ScriptRuntimePhase.OPERATION).code)
        assertEquals(SourceErrorCode.PLUGIN, scriptRuntimeFailure(
            androidx.javascriptengine.SandboxDeadException(secret), ScriptRuntimePhase.OPERATION, memoryLimited = true).code)
        assertTrue(scriptRuntimeFailure(androidx.javascriptengine.SandboxDeadException(secret),
            ScriptRuntimePhase.OPERATION, requestSent = true).outcomeUnknown)
        val explicitBusinessFailure = SourceException(SourceErrorCode.UNAVAILABLE)
        assertSame(explicitBusinessFailure, scriptRuntimeFailure(explicitBusinessFailure, ScriptRuntimePhase.OPERATION, requestSent = true))
        assertFalse(explicitBusinessFailure.outcomeUnknown)
        val denied = SourceException(SourceErrorCode.PLUGIN)
        assertSame(denied, scriptRuntimeFailure(denied, ScriptRuntimePhase.REQUEST))
    }

    @Test fun lazyArtworkUsesTheExistingDurableFileKeyWithoutExposingUrlCredentials() {
        val root = java.nio.file.Files.createTempDirectory("source-artwork").toFile()
        try {
            val url = "https://cdn.example/cover.jpg?token=private-token"
            val file = ScriptNetworkPolicy.artworkFile(url, java.io.File(root, "org.example.music"))
            val uri = ScriptNetworkPolicy.artworkUri(file)
            assertEquals("18d2af4d3b1377406286612d6b43c405f2d25102d16a1296588bb67f5df2eeff", file.name)
            assertFalse(uri.contains("private-token"))
            assertFalse(uri.contains("cdn.example"))
            assertEquals("org.example.music" to file.canonicalFile, sourceArtworkLocation(root, uri))
            assertFalse("Registration must not require an existing image", file.exists())
            file.parentFile.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(file.canonicalFile, sourceArtworkLocation(root, uri)?.second)
            for (invalid in listOf("file://attacker${file.absolutePath}", "$uri?token=secret", "$uri#fragment",
                java.io.File(root.parentFile, "outside/artwork/${file.name}").toURI().toString(),
                "https://cdn.example/${file.name}", "content://media/${file.name}")) {
                assertNull(sourceArtworkLocation(root, invalid))
            }
            try { ScriptNetworkPolicy(listOf("other.example")).validateUrl(url); fail("Updates must use the current domain grant") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        } finally { root.deleteRecursively() }
    }

    @Test fun artworkDescriptorsRetainOnlyBoundedSourceDataAndRedactTheirStringForm() {
        val values = mapOf("url" to "https://cdn.example/cover?token=private-token", "account" to "opaque-account-digest")
        val descriptor = SourceArtworkDescriptor.parse(values)
        assertEquals(values, descriptor.values())
        assertNull(descriptor.sourceVersion)
        assertFalse(descriptor.toString().contains("private-token"))
        val version = "1.2.3+42"
        val current = SourceArtworkDescriptor.parse(values + ("sourceVersion" to version))
        assertEquals(version, current.sourceVersion)
        assertEquals(values + ("sourceVersion" to version), current.values())
        assertEquals(current.values(), SourceArtworkDescriptor(descriptor.url, descriptor.account, version).values())
        val longestProviderVersion = "v".repeat(50) + "+" + Long.MIN_VALUE
        assertEquals(longestProviderVersion, SourceArtworkDescriptor.parse(values + ("sourceVersion" to longestProviderVersion)).sourceVersion)
        assertFalse(current.toString().contains(version))
        for (invalid in listOf(values + ("cookie" to "secret"), values - "account", values + ("url" to "x".repeat(8193)),
            values + ("sourceVersion" to ""), values + ("sourceVersion" to " "), values + ("sourceVersion" to "v".repeat(81)),
            current.values() + ("cookie" to "secret"))) {
            assertThrows(IllegalArgumentException::class.java) { SourceArtworkDescriptor.parse(invalid) }
        }
    }

    @Test fun largeArtworkStreamsWithoutTheScriptJsonLimit() {
        val directory = java.nio.file.Files.createTempDirectory("source-artwork-stream").toFile()
        val policy = ScriptNetworkPolicy(listOf("cdn.example"))
        val url = "https://cdn.example/large-cover.jpg"
        val file = ScriptNetworkPolicy.artworkFile(url, directory)
        val bytes = ByteArray(1_128_119) { (it % 251).toByte() }
        try {
            for (length in listOf(bytes.size.toLong(), -1L)) {
                var largestRead = 0L
                var closed = false
                val data = Buffer().write(bytes)
                val source = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        largestRead = maxOf(largestRead, byteCount)
                        return data.read(sink, byteCount)
                    }
                    override fun close() { closed = true; data.close() }
                }.buffer()
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = length
                    override fun source() = source
                }
                val response = Response.Builder().request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build()
                response.use {
                    assertEquals(ScriptNetworkPolicy.artworkUri(file), policy.cacheArtwork(it, file) {})
                }
                assertArrayEquals(bytes, file.readBytes())
                assertTrue("Image reads must stay incremental", largestRead in 1..65_536)
                assertTrue(closed)
                assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedArtworkKeepsThePreviousFileAndRemovesItsPartialWrite() {
        val directory = java.nio.file.Files.createTempDirectory("source-artwork-interrupted").toFile()
        val policy = ScriptNetworkPolicy(listOf("cdn.example"))
        val url = "https://cdn.example/large-cover.jpg"
        val file = ScriptNetworkPolicy.artworkFile(url, directory)
        val previous = byteArrayOf(1, 2, 3)
        file.parentFile!!.mkdirs()
        try {
            for (cancelled in listOf(false, true)) {
                file.writeBytes(previous)
                var reads = 0
                var checks = 0
                var closed = false
                val data = Buffer().write(ByteArray(1_128_119))
                val source = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (++reads > 1 && !cancelled) throw IOException("Interrupted artwork")
                        return data.read(sink, byteCount)
                    }
                    override fun close() { closed = true; data.close() }
                }.buffer()
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                    override fun source() = source
                }
                val response = Response.Builder().request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build()
                val failure = runCatching {
                    response.use {
                        policy.cacheArtwork(it, file) {
                            if (++checks > 2 && cancelled) throw CancellationException("Source retired")
                        }
                    }
                }.exceptionOrNull()
                if (cancelled) assertTrue(failure is CancellationException) else assertTrue(failure is IOException)
                assertArrayEquals(previous, file.readBytes())
                assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
                assertTrue(closed)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun sourceNetworkCancellationStillOwnsTheCallAfterHeadersWhileTheBodyIsReading() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val body = object : ResponseBody() {
            private val data = Buffer().writeUtf8("{}")
            private val input = object : Source {
                override fun timeout() = Timeout.NONE
                override fun read(sink: Buffer, byteCount: Long): Long {
                    started.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    return data.read(sink, byteCount)
                }
                override fun close() { data.close(); closed.countDown() }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = input
        }
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build()
        }.build()
        val call = client.newCall(Request.Builder().url("https://source.example/fixture").build())
        val policy = ScriptNetworkPolicy(listOf("source.example"))
        val pending = async(kotlinx.coroutines.Dispatchers.IO) {
            policy.exchange(call, {}) { response, _ -> policy.responsePayload(response, "utf8") }
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
            pending.cancel()
            pending.join()
            assertTrue("Revocation must cancel the socket after its headers were delivered", call.isCanceled())
            release.countDown()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally {
            release.countDown(); pending.cancel()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun wireTransportClosesBodiesAndBoundsKnownAndUnknownLengthResponses() {
        val limit = ScriptNetworkPolicy.MAX_RESPONSE_BYTES
        var closed = false
        fun exchange(bytes: ByteArray, length: Long, failure: IOException? = null, status: Int = 200): Response {
            closed = false
            val body = object : ResponseBody() {
                private val input = Buffer().write(bytes)
                private val stream = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (failure != null) throw failure
                        return input.read(sink, byteCount)
                    }
                    override fun close() { closed = true; input.close() }
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength() = length
                override fun source() = stream
                override fun close() { closed = true; stream.close() }
            }
            // A terminal application interceptor prevents DNS/network access.
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(NeteaseWireCodec.interceptor(mapOf("mode" to "api")))
                .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(status).message("Fixture").body(body).build() }.build()
            return client.newCall(Request.Builder().url("https://source.example/fixture").post("{}".toRequestBody()).build()).execute()
        }
        for (bytes in listOf(byteArrayOf(), "{\"code\":200}".toByteArray())) {
            exchange(bytes, bytes.size.toLong(), status = 403).use { response ->
                assertEquals(403, response.code)
                assertArrayEquals(bytes, response.body!!.bytes())
            }
            assertTrue("The original streamed response must always close", closed)
        }
        val prefix = "{\"code\":200,\"padding\":\""
        val suffix = "\"}"
        val largeJson = prefix + "x".repeat(limit - prefix.length - suffix.length) + suffix
        val largeBytes = largeJson.toByteArray(Charsets.UTF_8)
        assertTrue(largeBytes.size > 1024 * 1024)
        assertArrayEquals(largeBytes, NeteaseWireCodec.decodeResponseBytes(largeBytes, "eapi"))
        for (length in listOf(-1L, largeBytes.size.toLong())) {
            exchange(largeBytes, length).use { response ->
                val payload = ScriptNetworkPolicy(emptyList()).responsePayload(response, "utf8")
                assertEquals(largeJson, payload["body"].asString)
                assertEquals(200, JsonParser.parseString(payload["body"].asString).asJsonObject["code"].asInt)
            }
            assertTrue("Accepted large responses must close their original body", closed)
        }
        for (length in listOf(-1L, limit + 1L)) {
            try { exchange(ByteArray(limit + 1), length); fail("Expected the response limit") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
            assertTrue("Oversized bodies must close before rejecting the response", closed)
        }
        val failure = IOException("Synthetic body failure")
        try { exchange(byteArrayOf(), -1, failure); fail("Expected the original read failure") }
        catch (error: IOException) { assertSame(failure, error) }
        assertTrue("Failed reads must also close the original body", closed)
    }

    @Test fun wireCodecsPreserveSourceOwnedRequestDataAndMatchIndependentCryptoVectors() {
        val payload = """{"id":"42","header":{},"e_r":false}"""
        val request = Request.Builder().url("https://source.example/unrelated/path?source=fixture")
            .header("Cookie", "source-owned-session").header("User-Agent", "Source fixture")
            .post(payload.toRequestBody()).build()
        val eapi = NeteaseWireCodec.encodeRequest(request, mapOf("mode" to "eapi",
            "signingPath" to "/arbitrary/signing/path", "formFields" to """{"proof":"source-owned-proof"}"""))
        assertEquals(request.url, eapi.url)
        assertEquals("source-owned-session", eapi.header("Cookie"))
        assertEquals("Source fixture", eapi.header("User-Agent"))
        assertNull(eapi.header("X-Music-U"))
        assertNull(eapi.header("X-Real-IP"))
        val eapiForm = eapi.body as FormBody
        assertEquals("1b0d7853ce1121d858dec5e64d415d3e183dbd1f7d9dfc1c38578844a18a3443e0a2bf6edef312e67a3fe8d346b4c25059386fcc1f294cdfac9d5a862f20016dffbc6c60beec14874768be51267fb8aae4d5a752f76d80c97fe89a084a0aa50296bcd8e55f14b7a302e2ff984f935db8459027e5c2e362466457579c44a0b17c", eapiForm.value(0))
        assertEquals("proof", eapiForm.name(1))
        assertEquals("source-owned-proof", eapiForm.value(1))
        val weapi = NeteaseWireCodec.encodeRequest(request, mapOf("mode" to "weapi", "weapiKey" to "0123456789abcdef")).body as FormBody
        assertEquals("NCzEpdBW+9y5ivnuOSBe6W/NFq6NXUcxmHjcLjVldx96PAB/dCMbVdWATl2Q1GMurXWwRDgQ9Tp0Ec7GBrVC0zejcvw9srzD6POn5+RYohY=", weapi.value(0))
        assertEquals("35701388baf89fed412e11269b9c76625d095ecaf17f03fa018abe19ea2d38b949debf242ee39a71ca1f6cda71b1b86a45aa909ee27f7e78e267d34e732f0de948206c3340a788d0003372183e2f753c1f78b66ac23d134ac1fc9b993156520ea826b8aa89a962d4491b4b8d7e08738e1da9b07aa39bf4a7ef0b1c210728cd52", weapi.value(1))
        val api = NeteaseWireCodec.encodeRequest(request, mapOf("mode" to "api"))
        assertEquals(request.url, api.url)
        assertEquals(listOf("42", "{}", "false"), (api.body as FormBody).let { form -> (0 until form.size).map(form::value) })
    }

    @Test fun wireDecodeBoundsEncryptedAndCompressedResponsesWithoutServiceKnowledge() {
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val plain = """{"code":200,"message":"雪"}""".toByteArray()
        val encrypted = bytes("4dc12a14eca8f30f33123e619c7b9c896947855aec4bbd3c709b194a0191abd6")
        assertArrayEquals(plain, NeteaseWireCodec.decodeResponseBytes(encrypted, "eapi"))
        assertArrayEquals(plain, NeteaseWireCodec.decodeResponseBytes(plain, "eapi"))
        assertArrayEquals(plain, NeteaseWireCodec.decodeResponseBytes(bytes("3727608801dc00f6f0bcd388f24799d10d9c3ecfd24566aaf091083aa767e93105bd45f8ba4ec8d5805388e03e31eaf8"), "eapi"))
        val malformed = "invalid encrypted response".toByteArray()
        assertArrayEquals(malformed, NeteaseWireCodec.decodeResponseBytes(malformed, "eapi"))
        try {
            NeteaseWireCodec.decodeResponseBytes(bytes("fba5b125f70d46b164ed584a21ec3e7af7176b4ac933f1b36f5cbd8fc6278d8b8906943155b096aab0b9b60d1ec86980"), "eapi", limit = 1024)
            fail("An inflated response must remain bounded")
        } catch (error: SourceException) {
            assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code)
            assertTrue(error is SourceResponseSizeException)
        }
        val request = Request.Builder().url("https://source.example/").post("{}".toRequestBody()).build()
        for (arguments in listOf(mapOf("mode" to "eapi"), mapOf("mode" to "weapi"),
            mapOf("mode" to "eapi", "signingPath" to "/path?query=forbidden"),
            mapOf("mode" to "api", "sessionType" to "mobile"),
            mapOf("mode" to "api", "formFields" to """{"params":"override"}"""))) {
            try { NeteaseWireCodec.encodeRequest(request, arguments); fail("Expected invalid explicit codec arguments") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
    }

    private val minimal = """{"id":"example.source","name":"Example","author":"Author","version":"1",
        "apiVersion":1,"capabilities":["catalog.search","playback.resolve"]}"""

    @Test fun trustedBundleMigrationPreservesLegacyMobileIdentityAndFallbackPosition() {
        val values = androidx.datastore.preferences.core.mutablePreferencesOf(
            com.ljyh.mei.constants.CookieKey to "fixture-token==",
            com.ljyh.mei.constants.NeteaseMusicAKey to "fixture-anonymous",
            com.ljyh.mei.constants.NeteaseCsrfKey to "fixture-csrf",
            com.ljyh.mei.constants.NeteaseUrsAppIdKey to "fixture-urs",
            com.ljyh.mei.constants.DeviceIdKey to "fixture-device",
            com.ljyh.mei.constants.UserIdKey to "1234",
        )
        val migrated = BundledSourceProvisioner.legacyConfiguration(values)
        assertEquals("mobile", migrated["sessionType"])
        assertEquals("MUSIC_U=fixture-token==; MUSIC_A=fixture-anonymous; __csrf=fixture-csrf", migrated["cookie"])
        assertEquals("fixture-device", migrated["deviceId"])
        assertEquals("1234", migrated["userId"])
        assertEquals(listOf("qq", "script:org.meilox.source.netease:default", "local", "missing"),
            BundledSourceProvisioner.legacyOrder(listOf("qq", "netease", "local", "missing")))
        val asset = java.io.File("src/main/assets/music-sources/netease.meisource").readBytes()
        assertTrue(BundledSourceProvisioner.matches(asset))
        asset[asset.lastIndex] = (asset.last().toInt() xor 1).toByte()
        assertFalse(BundledSourceProvisioner.matches(asset))
    }

    @Test fun bundledUpdatesOnlyOwnUnmodifiedPackagesAndNeverRemovedOrExternalSources() {
        val directory = java.nio.file.Files.createTempDirectory("source-provenance").toFile()
        try {
            listOf("manifest.json", "index.js", "LICENSE", "NOTICE").forEach { java.io.File(directory, it).writeText(it) }
            val fingerprint = BundledSourceProvisioner.contentFingerprint(directory)
            val provenance = "${"a".repeat(64)}:$fingerprint"
            assertTrue(BundledSourceProvisioner.ownsBundledInstallation(directory, provenance))
            java.io.File(directory, "state.json").writeText("{\"enabled\":false}")
            assertTrue(BundledSourceProvisioner.ownsBundledInstallation(directory, provenance))
            assertFalse(BundledSourceProvisioner.ownsBundledInstallation(directory, "removed"))
            assertFalse(BundledSourceProvisioner.ownsBundledInstallation(directory, "external"))
            java.io.File(directory, "index.js").appendText("updated separately")
            assertFalse(BundledSourceProvisioner.ownsBundledInstallation(directory, provenance))
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedBundledSwapsRecoverProvenanceWithoutOverwritingExternalPackages() {
        val root = java.nio.file.Files.createTempDirectory("source-transaction").toFile()
        try {
            val destination = java.io.File(root, BundledSourceProvisioner.NETEASE_ID).apply { mkdirs() }
            listOf("manifest.json", "index.js", "LICENSE", "NOTICE").forEach { java.io.File(destination, it).writeText(it) }
            val next = "${"a".repeat(64)}:${BundledSourceProvisioner.contentFingerprint(destination)}"
            val marker = java.io.File(root, ".netease-default-v1")
            val journal = java.io.File(root, ".netease-bundle-transaction")
            val backup = java.io.File(root, ".netease-bundle-backup")
            for (previous in listOf("absent", "b".repeat(64))) {
                if (previous == "absent") marker.delete() else marker.writeText(previous)
                backup.mkdirs()
                journal.writeText("$previous\n$next")
                BundledSourceProvisioner.recover(root)
                assertEquals(next, marker.readText())
                assertFalse(backup.exists())
                assertFalse(journal.exists())
            }
            marker.writeText("external")
            journal.writeText("${"b".repeat(64)}\n$next")
            BundledSourceProvisioner.recover(root)
            assertEquals("external", marker.readText())
            assertTrue(destination.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun manifestsKeepStableIdentityAndExplicitCapabilities() {
        val manifest = PluginManifest.parse(minimal)
        assertEquals("example.source", manifest.providerId)
        assertEquals("index.js", manifest.entry)
        assertTrue(manifest.permissions.network.isEmpty())
        assertFalse(manifest.permissions.storage)
        assertTrue(manifest.supported)
        assertTrue(PluginManifest.parse(minimal.replace("\"apiVersion\":1", "\"apiVersion\":2")).supported)
        assertFalse(PluginManifest.parse(minimal.replace("\"apiVersion\":1", "\"apiVersion\":3")).supported)
    }

    @Test fun batchCatalogIsAnOptionalV2SupplementToSingleTrackLookup() {
        val single = minimal.replace("\"catalog.search\"", "\"catalog.track\"")
        val batch = single.replace("\"apiVersion\":1", "\"apiVersion\":2")
            .replace("\"catalog.track\"", "\"catalog.track\",\"catalog.tracks\"")
        assertFalse("catalog.tracks" in PluginManifest.parse(single).capabilities)
        val manifest = PluginManifest.parse(batch)
        assertTrue("catalog.tracks" in manifest.capabilities)
        assertTrue(SourceCapability.TRACK in manifest.sourceCapabilities)
        rejects { PluginManifest.parse(batch.replace("\"apiVersion\":2", "\"apiVersion\":1")) }
        rejects { PluginManifest.parse(batch.replace("\"catalog.track\",", "")) }
    }

    @Test fun sharedFeaturesAndNativeCodecsRequireExplicitIndependentPermissions() {
        val value = """{"id":"example.source","name":"Example","author":"Author","version":"2","apiVersion":2,
            "capabilities":["feature.invoke","playback.qualities"],"features":{"messages":["conversations","send"]},
            "featureRoutes":["private_messages"],"permissions":{"nativeSdks":["netease.transport"]}}"""
        val manifest = PluginManifest.parse(value)
        assertEquals(setOf("conversations", "send"), manifest.features["messages"])
        assertEquals(setOf("netease.transport"), manifest.permissions.nativeSdks)
        assertTrue(manifest.permissions.network.isEmpty())
        rejects { PluginManifest.parse(value.replace("netease.transport", "android.reflect")) }
        rejects { PluginManifest.parse(value.replace("\"feature.invoke\",", "")) }
        rejects { PluginManifest.parse(value.replace("\"apiVersion\":2", "\"apiVersion\":1")) }
        rejects { PluginManifest.parse(value.replace("conversations", "../invoke")) }
        rejects { PluginManifest.parse(value.replace("\"id\":\"example.source\"", "\"id\":\"example.source\",\"providerId\":\"netease\"")) }
    }

    @Test fun responseCookiesPreserveEveryHeaderWithoutASharedJar() {
        val response = response(byteArrayOf()).newBuilder()
            .addHeader("Set-Cookie", "first=one; HttpOnly")
            .addHeader("Set-Cookie", "second=two; Secure").build()
        response.use {
            val payload = ScriptNetworkPolicy(emptyList()).responsePayload(it, "utf8")
            assertEquals(listOf("first=one; HttpOnly", "second=two; Secure"), payload.getAsJsonArray("setCookies").map { value -> value.asString })
        }
    }

    @Test fun apiTwoCapabilitiesRemainOptionalAndCannotExpandAnApiOneManifest() {
        val expanded = """{"id":"example.source","name":"Example","author":"Author","version":"2","apiVersion":2,
            "capabilities":["catalog.search","feed.home","playlist.get","library.createdPlaylists","collection.saved"],
            "searchTypes":["track","playlist"],"collectionTypes":["playlist"]}"""
        val manifest = PluginManifest.parse(expanded)
        assertTrue(manifest.supported)
        assertEquals(setOf("track", "playlist"), manifest.searchTypes)
        assertTrue(SourceCapability.FEED in manifest.sourceCapabilities)
        assertFalse(SourceCapability.PLAYLIST_EDIT in manifest.sourceCapabilities)
        rejects { PluginManifest.parse(expanded.replace("\"apiVersion\":2", "\"apiVersion\":1")) }
        rejects { PluginManifest.parse(expanded.replace("\"collectionTypes\":[\"playlist\"]", "\"collectionTypes\":[\"track\"]")) }
        rejects { PluginManifest.parse(expanded.replace("\"collectionTypes\":[\"playlist\"]", "\"collectionTypes\":[]")) }
    }

    @Test fun apiTwoCatalogParsingPreservesTypedRefsAndRoutesAllArtworkThroughTheHost() = runTest {
        val checked = mutableListOf<String>()
        val parser = ScriptContentParser("example.source", 2, setOf(PlaylistEditAction.UPDATE)) { checked += it; "checked:$it" }
        val track = parser.track(json("""{"trackId":"曲目:42/one","title":"Song","durationMs":180000,
            "artists":[{"name":"Artist","artistId":"artist/mid"}],"album":{"title":"Album","albumId":"album:mid"},
            "artworkUrl":"https://images.example/cover","providerId":"netease"}"""))
        assertEquals(TrackRef("example.source", "曲目:42/one"), track.ref)
        assertEquals(ArtistRef("example.source", "artist/mid"), track.artists.single().ref)
        assertEquals(AlbumRef("example.source", "album:mid"), track.album.ref)
        assertEquals("checked:https://images.example/cover", track.artworkUrl)
        assertEquals(listOf("https://images.example/cover"), checked)
        val playlist = parser.playlist(json("""{"playlistId":"chart:26","title":"Chart","trackCount":100,"editableActions":["update"]}"""))
        assertEquals(PlaylistRef("example.source", "chart:26"), playlist.ref)
        assertEquals(setOf(PlaylistEditAction.UPDATE), playlist.editableActions)
        assertNull(playlist.playCount)
        assertNull(playlist.subtitle)
        assertEquals("Recommended for you", parser.playlist(json("""{"playlistId":"p","title":"P","subtitle":"Recommended for you"}""")).subtitle)
        assertNull(parser.playlist(json("""{"playlistId":"p","title":"P","playCount":null}""")).playCount)
        for (count in listOf(0L, 4_500_000_000L)) {
            assertEquals(count, parser.playlist(json("""{"playlistId":"p","title":"P","playCount":$count}""")).playCount)
        }
        val feed = parser.feed(json("""{"sections":[{"id":"charts","title":"Charts","layout":"playlists",
            "items":[{"type":"playlist","playlist":{"playlistId":"chart:26","title":"Chart"}}]}],
            "categories":[{"id":"all","title":"All"}]}"""))
        assertEquals(playlist.ref, feed.sections.single().items.single().ref)
        val page = parser.page(json("""{"items":[{"trackId":"42","title":"Song"}],"hasMore":true,"nextCursor":"page:2"}"""), PageRequest(limit = 1), parser::track)
        assertEquals("page:2", page.nextCursor)
        assertEquals(TrackRef("example.source", "42"), page.items.single().ref)
    }

    @Test fun untrustedCatalogPagesRejectWrongKindsCountsCursorsAndMutationClaims() = runTest {
        val parser = ScriptContentParser("example.source", 2, emptySet()) { it }
        suspend fun rejected(block: suspend () -> Unit) {
            try { block(); fail("Expected invalid source content") } catch (error: SourceException) {
                assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code)
            }
        }
        rejected { parser.track(json("""{"trackId":"42","title":"Song","durationMs":1.5}""")) }
        rejected { parser.track(json("""{"trackId":42,"title":"Song"}""")) }
        rejected { parser.playlist(json("""{"playlistId":"p","title":"P","editableActions":["delete"]}""")) }
        for (count in listOf("-1", "1.5", "\"1\"", "true", "9223372036854775808")) {
            rejected { parser.playlist(json("""{"playlistId":"p","title":"P","playCount":$count}""")) }
        }
        rejected { parser.item(json("""{"type":"playlist","playlist":{"playlistId":"p","title":"P"}}"""), ContentType.TRACK) }
        rejected { parser.page(json("""{"items":[],"hasMore":true}"""), PageRequest(), parser::track) }
        rejected { parser.page(json("""{"items":[{"trackId":"t","title":"T"}],"hasMore":true,"nextCursor":"same"}"""), PageRequest(cursor = "same"), parser::track) }
        rejected { parser.page(json("""{"items":[{},{}]}"""), PageRequest(limit = 1), parser::track) }
        rejected { parser.boolean(com.google.gson.JsonPrimitive("true")) }
        rejected { parser.feed(json("""{"sections":[{"id":"one","title":"One","layout":"tracks","items":[]},
            {"id":"one","title":"Again","layout":"tracks","items":[]}]}""")) }
    }

    @Test fun feedActionsKeepOpaqueIdsAndRequireOwnedDeclaredCapabilities() = runTest {
        val parser = ScriptContentParser("example.source", 2, emptySet(),
            mapOf("discovery" to setOf("homePlayback", "personalRadio")), setOf("everyday")) { "checked:$it" }
        val feed = parser.feed(json("""{"sections":[{"id":"hero","title":"","layout":"HERO","items":[],"cards":[
            {"id":"daily","title":"Daily","artworkUrl":"https://images.example/daily","iconUrl":"https://images.example/icon","target":{"kind":"route","route":"everyday","parameters":{"category":"daily/新"}}},
            {"id":"queue","title":"Queue","target":{"kind":"queue","feature":"discovery","action":"homePlayback","parameters":{"episodeId":"episode:9223372036854775806"}}},
            {"id":"radio","title":"Radio","target":{"kind":"radio","seedIds":["track:mid/新"],"similar":true}},
            {"id":"playlist","title":"List","target":{"kind":"content","contentRef":{"type":"playlist","contentId":"chart:26"}}}
        ]}]}"""))
        val cards = feed.sections.single().cards
        assertEquals(listOf("daily", "queue", "radio", "playlist"), cards.map { it.id })
        assertEquals("checked:https://images.example/daily", cards[0].artworkUrl)
        assertEquals("checked:https://images.example/icon", cards[0].iconUrl)
        assertEquals(mapOf("category" to "daily/新"), (cards[0].target as SourceFeedTarget.Route).parameters)
        assertEquals("episode:9223372036854775806", (cards[1].target as SourceFeedTarget.Queue).parameters.get("episodeId").asString)
        assertEquals(listOf("track:mid/新"), (cards[2].target as SourceFeedTarget.Radio).seedIds)
        assertEquals(PlaylistRef("example.source", "chart:26"), (cards[3].target as SourceFeedTarget.Content).ref)
        suspend fun rejected(target: String, layout: String = "HERO") {
            try {
                parser.feed(json("""{"sections":[{"id":"hero","title":"","layout":"$layout","items":[],"cards":[{"id":"one","title":"One","target":$target}]}]}"""))
                fail("Expected invalid feed action")
            } catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
        rejected("""{"kind":"content","contentRef":{"providerId":"other","type":"track","contentId":"42"}}""")
        rejected("""{"kind":"route","route":"account/security","parameters":{}}""")
        rejected("""{"kind":"route","route":"everyday","parameters":{"providerId":"other"}}""")
        rejected("""{"kind":"queue","feature":"discovery","action":"arbitraryHttp","parameters":{}}""")
        rejected("""{"kind":"radio","seedIds":[],"similar":true}""")
        rejected("""{"kind":"radio","seedIds":[42]}""")
        rejected("""{"kind":"radio","seedIds":["42"]}""", "TRACKS")
    }

    @Test fun squareFeedCardsPreserveOriginalCaptionsAndRejectUntrustedPresentation() = runTest {
        val parser = ScriptContentParser("example.source", 2, emptySet(), emptyMap(), setOf("everyday")) { "checked:$it" }
        fun section(card: String, layout: String = "PLAYLISTS") = json("""{"sections":[{
            "id":"recommendations","title":"Cards","layout":"$layout","items":[],"cards":[$card]}]}""")
        val card = """{"id":"radar","title":"","subtitle":"Original playlist title",
            "artworkUrl":"https://images.example/processed","coverCaptions":["First caption","Second caption"],
            "detail":"12.3万","titleMaxLines":1,
            "target":{"kind":"content","contentRef":{"type":"playlist","contentId":"playlist/opaque:26"}}}"""
        val parsed = parser.feed(section(card)).sections.single().cards.single()
        assertEquals("", parsed.title)
        assertEquals("Original playlist title", parsed.subtitle)
        assertEquals(listOf("First caption", "Second caption"), parsed.coverCaptions)
        assertEquals("12.3万", parsed.detail)
        assertEquals(1, parsed.titleMaxLines)
        assertEquals("checked:https://images.example/processed", parsed.artworkUrl)
        assertEquals(PlaylistRef("example.source", "playlist/opaque:26"), (parsed.target as SourceFeedTarget.Content).ref)
        val defaults = parser.feed(section("""{"id":"plain","title":"Plain",
            "target":{"kind":"content","contentRef":{"type":"playlist","contentId":"26"}}}""")).sections.single().cards.single()
        assertEquals(emptyList<String>(), defaults.coverCaptions)
        assertNull(defaults.detail)
        assertEquals(2, defaults.titleMaxLines)
        suspend fun rejected(value: String, layout: String = "PLAYLISTS") {
            try { parser.feed(section(value, layout)); fail("Expected invalid square-card presentation") }
            catch (error: SourceException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.code) }
        }
        for (value in listOf("0", "3", "1.5", "\"1\"")) rejected(card.replace("\"titleMaxLines\":1", "\"titleMaxLines\":$value"))
        rejected(card.replace("[\"First caption\",\"Second caption\"]", "[42]"))
        rejected(card.replace("\"detail\":\"12.3万\"", "\"detail\":123"))
        rejected(card, "ALBUMS")
        rejected("""{"id":"route","title":"Route","target":{"kind":"route","route":"everyday"}}""")
    }

    @Test fun unavailableArtworkPreservesCatalogButPermissionErrorsAndCancellationStillStopIt() = runTest {
        val value = json("""{"trackId":"42","title":"Song","artworkUrl":"https://images.example/missing"}""")
        for (code in listOf(SourceErrorCode.NOT_FOUND, SourceErrorCode.NETWORK, SourceErrorCode.TIMEOUT)) {
            val parser = ScriptContentParser("example.source", 2, emptySet()) { throw SourceException(code) }
            assertEquals("", parser.track(value).artworkUrl)
        }
        val denied = ScriptContentParser("example.source", 2, emptySet()) { throw SourceException(SourceErrorCode.PLUGIN) }
        try { denied.track(value); fail("Expected a permission failure") }
        catch (error: SourceException) { assertEquals(SourceErrorCode.PLUGIN, error.code) }
        val cancelled = ScriptContentParser("example.source", 2, emptySet()) { throw CancellationException() }
        try { cancelled.track(value); fail("Expected cancellation") } catch (_: CancellationException) { }
    }

    @Test fun accountSessionRevisionsAreIsolatedAndNeverRetainThePluginSecret() = runTest {
        val parser = ScriptContentParser("example.source", 2, emptySet()) { it }
        val value = json("""{"accountId":"account:one","displayName":"User","sessionRevision":"plugin-owned-secret"}""")
        val instance = ProviderInstanceId("script:example.source:default")
        val first = parser.account(value, instance, "2+1")
        val next = parser.account(value, instance, "2+2")
        assertEquals(instance, first.ref.instanceId)
        assertEquals(ProviderAccountId("account:one"), first.ref.accountId)
        assertEquals(64, first.sessionRevision.length)
        assertFalse(first.sessionRevision.contains("plugin-owned-secret"))
        assertNotEquals(first.sessionRevision, next.sessionRevision)
    }

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test
    fun manifestsRejectReservedIdentityAndUnknownPrivileges() {
        fun withHosts(count: Int) = minimal.dropLast(1) + ",\"permissions\":{\"network\":[" +
            (1..count).joinToString(",") { "\"cdn$it.example.com\"" } + "]}}"
        assertEquals(32, PluginManifest.parse(withHosts(32)).permissions.network.size)
        rejects { PluginManifest.parse(withHosts(33)) }
        listOf(
            minimal.replace("\"example.source\"", "\"netease\""),
            minimal.replace("\"example.source\"", "\"local\""),
            minimal.replace("\"catalog.search\"", "\"android.exec\""),
            minimal.replace("\"apiVersion\":1", "\"apiVersion\":1,\"entry\":\"../index.js\""),
            minimal.dropLast(1) + ",\"permissions\":{\"network\":[\"*.example.com\"]}}",
        ).forEach { json -> rejects { PluginManifest.parse(json) } }
    }

    @Test
    fun networkingRequiresExactDeclaredHttpsHostAndSafeHeaders() {
        val policy = ScriptNetworkPolicy(listOf("api.example.com"))
        assertEquals("api.example.com", policy.validateUrl("https://api.example.com/path").host)
        listOf(
            "http://api.example.com/path", "https://evil.example.com/", "https://api.example.com.evil.com/",
            "https://api.example.com:8443/", "https://user:secret@api.example.com/", "https://127.0.0.1/",
            "https://api.example.com/path#fragment",
        ).forEach { value -> rejects { policy.validateUrl(value) } }
        rejects { policy.validateHeaders(mapOf("Host" to "localhost")) }
        rejects { policy.validateHeaders(mapOf("Authorization" to "Bearer abc\r\nInjected: yes")) }
        policy.validateHeaders(mapOf("Authorization" to "Bearer plugin-owned-secret"))
        assertFalse(policy.client.followRedirects)
        assertFalse(policy.client.followSslRedirects)
    }

    @Test
    fun everyEvaluationContextGetsFreshBoundedCsprngEntropyWithoutSharingStorage() {
        val manifest = PluginManifest.parse(minimal.replace("\"apiVersion\":1", "\"apiVersion\":2"))
        val first = ScriptRuntime.newContext(manifest, mapOf("configuration" to "value"), mapOf("private" to "unshared"))
        val next = ScriptRuntime.newContext(manifest, emptyMap(), emptyMap())
        val firstPool = first["secureRandomBase64"].asString
        val nextPool = next["secureRandomBase64"].asString
        assertEquals(256, Base64.getDecoder().decode(firstPool).size)
        assertEquals(256, Base64.getDecoder().decode(nextPool).size)
        assertNotEquals(firstPool, nextPool)
        assertEquals("value", first["config"].asJsonObject["configuration"].asString)
        assertEquals(0, first["storage"].asJsonObject.size())
        assertEquals(0, next["config"].asJsonObject.size())
        assertEquals(0, first["account"].asJsonObject.size())
        val verified = ScriptRuntime.newContext(manifest, emptyMap(), mapOf("userId" to "unverified"), verifiedUserId = "verified")
        assertEquals("verified", verified["account"].asJsonObject["userId"].asString)
        assertFalse(verified.has("cache"))
        assertEquals(1, ScriptRuntime.newContext(PluginManifest.parse(minimal), emptyMap(), emptyMap())["apiVersion"].asInt)
    }

    @Test
    fun binaryResponsesPreserveRawBytesAndKeepTheSameEncodingAndSizeRestrictions() {
        val policy = ScriptNetworkPolicy(listOf("api.example.com"))
        assertEquals("utf8", policy.responseEncoding(json("{}")))
        assertEquals("base64", policy.responseEncoding(json("""{"responseEncoding":"base64"}""")))
        listOf("null", "true", "1", "\"hex\"", "\"BASE64\"").forEach {
            rejects { policy.responseEncoding(json("""{"responseEncoding":$it}""")) }
        }
        val binary = ByteArray(256) { it.toByte() }
        response(binary).use { response ->
            val payload = policy.responsePayload(response, "base64")
            assertEquals(200, payload["status"].asInt)
            assertEquals("application/octet-stream", payload["headers"].asJsonObject["content-type"].asString)
            assertEquals("", payload["body"].asString)
            assertArrayEquals(binary, Base64.getDecoder().decode(payload["bodyBase64"].asString))
        }
        response("Text 中文".toByteArray()).use { response ->
            val payload = policy.responsePayload(response, "utf8")
            assertEquals("Text 中文", payload["body"].asString)
            assertFalse(payload.has("bodyBase64"))
        }
        response(ByteArray(ScriptNetworkPolicy.MAX_RESPONSE_BYTES)).use { response ->
            assertEquals(ScriptNetworkPolicy.MAX_RESPONSE_BYTES,
                Base64.getDecoder().decode(policy.responsePayload(response, "base64")["bodyBase64"].asString).size)
        }
        listOf(-1L, ScriptNetworkPolicy.MAX_RESPONSE_BYTES + 1L).forEach { length ->
            response(ByteArray(ScriptNetworkPolicy.MAX_RESPONSE_BYTES + 1), length).use { response ->
                assertEquals(SourceErrorCode.INVALID_RESPONSE, assertThrows(SourceException::class.java) {
                    policy.responsePayload(response, "base64")
                }.code)
            }
        }
        response(binary, code = 302).use { response ->
            assertEquals(SourceErrorCode.NETWORK, assertThrows(SourceException::class.java) {
                policy.responsePayload(response, "base64")
            }.code)
        }
    }

    @Test
    fun responseReadFailuresRemainTransportErrorsAndPreserveCancellationAndTypedErrors() {
        val policy = ScriptNetworkPolicy(listOf("api.example.com"))
        listOf(IOException("signed URL and credentials") to SourceErrorCode.NETWORK,
            SocketTimeoutException("private timeout details") to SourceErrorCode.TIMEOUT).forEach { (failure, expected) ->
            listOf("utf8", "base64").forEach { encoding ->
                failingResponse(failure).use { response ->
                    try { policy.responsePayload(response, encoding); fail("Expected a transport failure") }
                    catch (error: SourceException) {
                        assertEquals(expected, error.code)
                        assertEquals(expected.name, error.message)
                        assertNull(error.cause)
                    }
                }
            }
        }
        val typed = SourceException(SourceErrorCode.AUTH_EXPIRED)
        failingResponse(typed).use { response ->
            try { policy.responsePayload(response, "base64"); fail("Expected the typed error") }
            catch (error: SourceException) { assertSame(typed, error) }
        }
        val cancelled = CancellationException("Cancelled source")
        failingResponse(cancelled).use { response ->
            try { policy.responsePayload(response, "utf8"); fail("Expected cancellation") }
            catch (error: CancellationException) { assertSame(cancelled, error) }
        }
    }

    @Test
    fun packagesPermitOnlyRootUtf8LegalTextWithinTheirExistingSizeBudget() {
        assertEquals(64 * 1024, ScriptPluginManager.packageFileLimit("LICENSE"))
        assertEquals(64 * 1024, ScriptPluginManager.packageFileLimit("NOTICE"))
        assertEquals(256 * 1024, ScriptPluginManager.packageFileLimit("index.js"))
        assertEquals(4 * 1024 * 1024, ScriptPluginManager.packageFileLimit("assets/test.wav"))
        listOf("helper.js", "assets/helper.js", "../LICENSE", "nested/LICENSE", "LICENSE.txt", "license", "NOTICE/").forEach {
            rejects { ScriptPluginManager.packageFileLimit(it) }
        }
        ScriptPluginManager.validateLegalText("MIT License\n版权所有\t\r\n".toByteArray())
        ScriptPluginManager.validateLegalText(ByteArray(64 * 1024) { 'a'.code.toByte() })
        listOf(byteArrayOf(0), byteArrayOf(0xff.toByte()), ByteArray(64 * 1024 + 1)).forEach {
            rejects { ScriptPluginManager.validateLegalText(it) }
        }
    }

    private fun response(bytes: ByteArray, length: Long = bytes.size.toLong(), code: Int = 200): Response {
        val buffer = Buffer().write(bytes)
        return Response.Builder().request(Request.Builder().url("https://api.example.com/binary").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("Fixture").header("content-type", "application/octet-stream")
            .body(object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = length
                override fun source() = buffer
            }).build()
    }

    private fun failingResponse(failure: Exception): Response {
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        return Response.Builder().request(Request.Builder().url("https://api.example.com/binary").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
    }

    @Test
    fun dnsRejectsPrivateLoopbackSharedAndMappedAddresses() {
        val blocked = listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
            "192.168.1.1", "100.64.0.1", "198.18.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2002:7f00:1::")
        blocked.forEach { assertFalse(it, ScriptNetworkPolicy.isPublicAddress(InetAddress.getByName(it))) }
        assertTrue(ScriptNetworkPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(ScriptNetworkPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        for (address in listOf("198.18.0.0", "198.19.255.255", "8.8.8.8", "2606:4700:4700::1111")) {
            ScriptNetworkPolicy.validateResolvedAddresses(listOf(InetAddress.getByName(address)))
        }
        val policy = ScriptNetworkPolicy(listOf("api.example.com"))
        assertEquals(SourceErrorCode.PLUGIN, assertThrows(SourceException::class.java) {
            policy.validateUrl("https://198.18.0.1/api")
        }.code)
        for (addresses in listOf(emptyList<InetAddress>()) + blocked.filter { it != "198.18.0.1" }.map { blockedAddress ->
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName(blockedAddress))
        }) {
            assertEquals(SourceErrorCode.NETWORK, assertThrows(SourceException::class.java) {
                ScriptNetworkPolicy.validateResolvedAddresses(addresses)
            }.code)
        }
    }

    @Test
    fun accountReplacementRejectsStaleReadsStorageAndDiagnosticsIncludingReinstall() {
        val gate = PluginRevisionGate()
        var account = "first"
        var storage = "first-account-token"
        var diagnostic = ""
        val old = gate.change { it }
        assertEquals("first", gate.read(old) { account })
        val replacement = gate.change { revision -> account = "second"; storage = ""; revision }
        assertFalse(gate.commit(old) { storage = "stale-token"; diagnostic = "stale-error" })
        try { gate.read(old) { account }; fail("Expected stale account read to cancel") } catch (_: CancellationException) { }
        assertTrue(gate.commit(replacement) { storage = "second-account-token" })
        assertEquals("second-account-token", storage)
        assertEquals("", diagnostic)
        gate.change { storage = "" }
        val reinstalled = gate.change { it }
        assertFalse(gate.commit(replacement) { storage = "removed-account-token" })
        assertTrue(gate.isCurrent(reinstalled))
        assertEquals("", storage)
    }

    @Test
    fun accountChangeIsAtomicWithAStorageCommitAlreadyInProgress() {
        val gate = PluginRevisionGate()
        val old = gate.change { it }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        var storage = ""
        try {
            val writer = executor.submit<Boolean> {
                gate.commit(old) {
                    entered.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    storage = "old-token"
                }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val replacement = executor.submit<PluginRevisionGate.Revision> {
                gate.change { revision -> storage = "new-account"; revision }
            }
            release.countDown()
            assertTrue(writer.get(2, java.util.concurrent.TimeUnit.SECONDS))
            val current = replacement.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("new-account", gate.read(current) { storage })
            assertFalse(gate.commit(old) { storage = "late-old-token" })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected permission or manifest rejection") } catch (_: SourceException) { }
    }
}
