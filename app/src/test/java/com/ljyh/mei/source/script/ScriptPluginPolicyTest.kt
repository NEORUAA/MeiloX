package com.ljyh.mei.source.script

import com.ljyh.mei.source.api.SourceException
import java.net.InetAddress
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
        assertFalse(PluginManifest.parse(minimal.replace("\"apiVersion\":1", "\"apiVersion\":2")).supported)
    }

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
