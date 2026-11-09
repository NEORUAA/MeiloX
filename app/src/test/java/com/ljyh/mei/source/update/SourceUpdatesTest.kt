package com.ljyh.mei.source.update

import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.script.PluginConfigField
import com.ljyh.mei.source.script.PluginManifest
import com.ljyh.mei.source.script.PluginPermissions
import com.ljyh.mei.source.script.PluginInstallJournal
import com.ljyh.mei.source.script.PluginInstallTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceUpdatesTest {
    private val listing = SourceMarketListing("org.meilox.fixture", "fixture.music", "Fixture",
        "https://github.com/Owner/Fixture", "dist/fixture.meisource", 2)
    private val entry = SourceMarketEntry("org.meilox.fixture", "fixture.music", "Fixture", "1.2.3",
        "https://github.com/Owner/Fixture", "https://raw.githubusercontent.com/Owner/Fixture/${"a".repeat(40)}/dist/fixture.meisource", "b".repeat(64), 2)

    private fun json(value: SourceMarketListing = listing): String = """{"schemaVersion":2,"sources":[{
        "packageId":"${value.packageId}","providerId":"${value.providerId}","name":"${value.name}",
        "repository":"${value.repository}","packagePath":"${value.packagePath}","apiVersion":${value.apiVersion}}]}"""

    @Test
    fun registryAcceptsDynamicOwnedPackagesAndRejectsAmbiguousOrExecutableMetadata() {
        assertEquals(listOf(listing), SourceMarketIndex.parse(json()))
        assertTrue(SourceMarketIndex.parse("""{"schemaVersion":2,"sources":[]}""").isEmpty())
        SourceMarketIndex.validate(entry.copy(packageUrl = entry.packageUrl.replace("/" + "a".repeat(40) + "/", "/HEAD/")))
        try { SourceMarketIndex.validate(entry.copy(packageUrl = entry.packageUrl.replace("/" + "a".repeat(40) + "/", "/main/"))); fail("Accepted arbitrary mutable branch") }
        catch (_: IllegalArgumentException) { }
        val invalid = listOf(
            json().replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
            json().replace("\"providerId\":", "\"unexpected\":\"value\",\"providerId\":"),
            json().replace("\"apiVersion\":2", "\"apiVersion\":\"2\""),
            json().replace("\"name\":\"Fixture\"", "\"name\":\"Fixture\",\"name\":\"Other\""),
            json().replace("}]}", "},${json().substringAfter("[").substringBeforeLast("]")}]}") ,
            json(listing.copy(packagePath = "main")),
            json(listing.copy(packagePath = "../fixture.meisource")),
            json(listing.copy(packagePath = "dist/fixture.txt")),
            json(listing.copy(repository = "https://github.com/Owner/Fixture.git")),
            json(listing.copy(packagePath = "dist/../fixture.meisource")),
            json(listing.copy(apiVersion = 3)),
            json().replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
            json() + " true",
            " ".repeat(SourceMarketIndex.MAX_BYTES) + json(),
        )
        invalid.forEachIndexed { index, value ->
            try { SourceMarketIndex.parse(value); fail("Accepted invalid registry case $index") }
            catch (_: SourceException) { }
        }
        val other = json(listing.copy(packageId = "org.meilox.other")).substringAfter("[").substringBeforeLast("]")
        try { SourceMarketIndex.parse(json().replace("}]}", "},$other]}")); fail("Duplicate provider identity") }
        catch (_: SourceException) { }
    }

    @Test
    fun semanticVersionsNeverDowngradeAndOrderPrereleaseWithoutNumericOverflow() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
        versions.zipWithNext().forEach { (old, next) -> assertTrue("$next after $old", isNewer(next, old)); assertFalse(isNewer(old, next)) }
        assertEquals(0, SourceVersion.parse("1.2.3+build.1")!!.compareTo(SourceVersion.parse("1.2.3+other")!!))
        assertTrue(isNewer("999999999999999999999.0.0", "999999999999999999998.0.0"))
        listOf("1", "01.0.0", "1.2.3-01", "1.2.3-", "1.2.3+", "v1.2.3", "1.2.3.4").forEach { assertNull(it, SourceVersion.parse(it)) }
        assertFalse(isNewer("1.2.3", "legacy-version"))
        assertFalse(isNewer("1.2.3+build", "1.2.3"))
    }

    @Test
    fun automaticConsentRequiresTheSameOriginAndNoExpandedAuthority() {
        val previous = PluginManifest("org.meilox.fixture", "fixture.music", "Fixture", "Tests", "1.0.0", 2,
            capabilities = setOf("catalog.track"), permissions = PluginPermissions(network = listOf("api.example.com")),
            config = listOf(PluginConfigField("cookie", "Cookie", secret = true, required = true)))
        val next = previous.copy(version = "1.1.0")
        assertTrue(sourceUpdateReviewReasons(previous, next, entry.repository, entry.repository).isEmpty())
        assertEquals(setOf(SourceUpdateReviewReason.UNKNOWN_ORIGIN), sourceUpdateReviewReasons(previous, next, null, entry.repository))
        assertEquals(setOf(SourceUpdateReviewReason.REPOSITORY_CHANGED), sourceUpdateReviewReasons(previous, next, "https://github.com/Other/Repo", entry.repository))
        val expanded = next.copy(
            permissions = PluginPermissions(listOf("api.example.com", "new.example.com"), storage = true, nativeSdks = setOf("netease.urs"), effects = setOf("web.open")),
            capabilities = next.capabilities + "links.resolve", linkHosts = setOf("new.example.com"),
            sessionKeys = setOf("account"), sessionCookieNames = mapOf("account" to setOf("MUSIC_U")),
            config = next.config + PluginConfigField("account", "Account", required = true))
        assertEquals(setOf(SourceUpdateReviewReason.NETWORK, SourceUpdateReviewReason.STORAGE, SourceUpdateReviewReason.NATIVE_SDK,
            SourceUpdateReviewReason.EFFECTS, SourceUpdateReviewReason.LINKS, SourceUpdateReviewReason.SESSION,
            SourceUpdateReviewReason.CAPABILITIES, SourceUpdateReviewReason.REQUIRED_CONFIGURATION),
            sourceUpdateReviewReasons(previous, expanded, entry.repository, entry.repository))
        val reduced = expanded.copy(permissions = previous.permissions, sessionKeys = expanded.sessionKeys, sessionCookieNames = expanded.sessionCookieNames,
            capabilities = previous.capabilities, linkHosts = emptySet(), config = previous.config)
        assertTrue(sourceUpdateReviewReasons(expanded, reduced, entry.repository, entry.repository).isEmpty())
        assertEquals(setOf(SourceUpdateReviewReason.SESSION), sourceUpdateReviewReasons(expanded,
            expanded.copy(sessionKeys = emptySet(), sessionCookieNames = emptyMap()), entry.repository, entry.repository))
        val wildcard = next.copy(permissions = next.permissions.copy(network = listOf("*.example.com")))
        assertEquals(setOf(SourceUpdateReviewReason.NETWORK),
            sourceUpdateReviewReasons(previous, wildcard, entry.repository, entry.repository))
        assertTrue(sourceUpdateReviewReasons(wildcard, wildcard.copy(version = "1.2.0"),
            entry.repository, entry.repository).isEmpty())
    }

    @Test
    fun processInterruptionKeepsOnlyCompletePayloadsAndTheirOriginalEnabledState() {
        fun fingerprint(directory: File): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(File(directory, "index.js").readBytes()).joinToString("") { "%02x".format(it) }
        for (phase in 0..3) {
            val root = kotlin.io.path.createTempDirectory("source-update-recovery-").toFile()
            try {
                val destination = File(root, entry.packageId).apply { mkdirs() }
                File(destination, "index.js").writeText("previous")
                File(destination, "state.json").writeText("disabled")
                val stage = File(root, ".source-stage-${entry.packageId}-${UUID.randomUUID()}").apply { mkdirs() }
                File(stage, "index.js").writeText("next")
                File(stage, "state.json").writeText("disabled-next-revision")
                val backup = File(root, ".${entry.packageId}-${UUID.randomUUID()}")
                PluginInstallJournal.write(root, PluginInstallTransaction(entry.packageId, stage.name, backup.name, fingerprint(stage), fingerprint(destination)))
                if (phase >= 1) assertTrue(destination.renameTo(backup))
                if (phase == 2) { destination.mkdirs(); File(destination, "index.js").writeText("incomplete") }
                if (phase == 3) assertTrue(stage.renameTo(destination))
                assertTrue(PluginInstallJournal.recover(root, ::fingerprint).isEmpty())
                assertEquals(if (phase == 3) "next" else "previous", File(destination, "index.js").readText())
                assertEquals(if (phase == 3) "disabled-next-revision" else "disabled", File(destination, "state.json").readText())
                assertFalse(backup.exists())
                assertFalse(stage.exists())
                assertTrue("Recovery must be idempotent", PluginInstallJournal.recover(root, ::fingerprint).isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test
    fun pausedSourcesAndRestoreReservationsDeferAutomaticReplacementWithoutBlockingTheUi() = runTest {
        val guard = SourceUpdatePlaybackGuard()
        guard.updateSources(setOf("origin.music", "actual.music"))
        for (provider in listOf("origin.music", "actual.music")) {
            try { guard.installIfIdle(provider) { fail("An owned source cannot be replaced") }; fail("Expected deferred update") }
            catch (_: SourceUpdateDeferred) { }
        }
        assertEquals("unrelated", guard.installIfIdle("other.music") { "unrelated" })
        var restoration: kotlinx.coroutines.Deferred<Unit>? = null
        guard.release()
        guard.installIfIdle("other.music") {
            restoration = guard.beginRestore()
            assertFalse("A new service awaits the already reserved transaction", restoration!!.isCompleted)
        }
        assertTrue(restoration!!.isCompleted)
        try { guard.installIfIdle("other.music") { fail("Restoration owns its boundary") }; fail("Expected restore deferral") }
        catch (_: SourceUpdateDeferred) { }
        guard.finishRestore(emptySet())
        assertEquals("ready", guard.installIfIdle("other.music") { "ready" })
        val resolvingStarted = CompletableDeferred<Unit>()
        val ongoing = launch(start = CoroutineStart.UNDISPATCHED) {
            guard.withResolution({ setOf("origin.music", "fallback.music") }) {
                resolvingStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        resolvingStarted.await()
        for (provider in listOf("origin.music", "fallback.music")) {
            try { guard.installIfIdle(provider) { fail("A pending resolver owns every possible source") }; fail("Expected resolving deferral") }
            catch (_: SourceUpdateDeferred) { }
        }
        ongoing.cancelAndJoin()
        assertEquals("released", guard.installIfIdle("origin.music") { "released" })
        var calls = 0
        var waiting: kotlinx.coroutines.Job? = null
        guard.installIfIdle("origin.music") {
            waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                guard.withResolution({ setOf("origin.music") }) {
                    calls++
                    try { guard.installIfIdle("origin.music") { fail("Resolution acquired its lease atomically") }; fail("Expected resolving deferral") }
                    catch (_: SourceUpdateDeferred) { }
                }
            }
            assertEquals(0, calls)
        }
        advanceUntilIdle()
        waiting!!.join()
        assertEquals("Waiting never replays an operation", 1, calls)
    }
}
