package com.ljyh.mei.source.script

import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
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

class ScriptPluginPolicyTest {
    private val minimal = """{"id":"example.source","name":"Example","author":"Author","version":"1",
        "apiVersion":1,"capabilities":["catalog.search","playback.resolve"]}"""

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
        rejected { parser.item(json("""{"type":"playlist","playlist":{"playlistId":"p","title":"P"}}"""), ContentType.TRACK) }
        rejected { parser.page(json("""{"items":[],"hasMore":true}"""), PageRequest(), parser::track) }
        rejected { parser.page(json("""{"items":[{"trackId":"t","title":"T"}],"hasMore":true,"nextCursor":"same"}"""), PageRequest(cursor = "same"), parser::track) }
        rejected { parser.page(json("""{"items":[{},{}]}"""), PageRequest(limit = 1), parser::track) }
        rejected { parser.boolean(com.google.gson.JsonPrimitive("true")) }
        rejected { parser.feed(json("""{"sections":[{"id":"one","title":"One","layout":"tracks","items":[]},
            {"id":"one","title":"Again","layout":"tracks","items":[]}]}""")) }
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
                rejects { policy.responsePayload(response, "base64") }
            }
        }
        response(binary, code = 302).use { response -> rejects { policy.responsePayload(response, "base64") } }
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
