package com.ljyh.mei.source

import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceResolutionTest {
    private val original = track("original")
    private fun track(provider: String, title: String = "Song", duration: Long = 200_000) = Track(TrackRef(provider, "same:id/雪"), title, listOf(SourceArtist("Artist")), SourceAlbum("Album"), duration)
    private class Provider(override val id: String, val candidate: Track, val failure: SourceErrorCode? = null) : MusicProvider, CatalogCapability, PlaybackCapability {
        override val displayName = id
        override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TRACK, SourceCapability.PLAYBACK)
        var calls = 0
        override suspend fun search(query: String, page: PageRequest) = SourcePage(listOf(candidate))
        override suspend fun getTrack(ref: TrackRef) = candidate
        override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource {
            calls++
            failure?.let { throw SourceException(it) }
            return AudioResource("https://audio.example/$id", "standard", contentId = "bytes")
        }
    }
    private fun resolver(providers: List<MusicProvider>, settings: SourceSettings = SourceSettings(autoFallbackEnabled = true, fallbackOrder = listOf("a", "b", "c"))) = PlaybackResolver(ProviderRegistry(providers), { settings })

    @Test fun originalSuccessStopsFallbackAndPreservesIdentity() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val result = resolver(listOf(origin, a)).resolve(original, "standard")
        assertEquals(original.ref, result.originTrackRef)
        assertEquals(original.ref, result.resolvedTrackRef)
        assertEquals(0, a.calls)
    }
    @Test fun failuresFollowOrderStopOnSuccessAndKeepOrigin() = runTest {
        val origin = Provider("original", original, SourceErrorCode.UNAVAILABLE)
        val a = Provider("a", track("a"), SourceErrorCode.TIMEOUT)
        val b = Provider("b", track("b"))
        val c = Provider("c", track("c"))
        val result = resolver(listOf(origin, a, b, c)).resolve(original, "standard")
        assertEquals(listOf("original", "a", "b"), result.attempts.map { it.instanceId.value })
        assertEquals(original.ref, result.originTrackRef)
        assertEquals("b", result.resolvedTrackRef.providerId)
        assertEquals(0, c.calls)
    }
    @Test fun disabledFallbackNeverCallsOtherSources() = runTest {
        val origin = Provider("original", original, SourceErrorCode.AUTH_EXPIRED)
        val a = Provider("a", track("a"))
        try { resolver(listOf(origin, a), SourceSettings()).resolve(original, "standard"); fail() }
        catch (error: PlaybackResolutionException) { assertEquals(SourceErrorCode.AUTH_EXPIRED, error.attempts.single().error) }
        assertEquals(0, a.calls)
    }
    @Test fun duplicateSourcesAreAttemptedOnce() = runTest {
        val a = Provider("a", track("a"), SourceErrorCode.UNAVAILABLE)
        try { resolver(listOf(a), SourceSettings(true, true, listOf("a", "a", "a"))).resolve(original, "standard"); fail() }
        catch (_: PlaybackResolutionException) { assertEquals(1, a.calls) }
    }
    @Test fun orderCanPrecedeOriginalWhenExplicitlyConfigured() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val result = resolver(listOf(origin, a), SourceSettings(true, false, listOf("a"))).resolve(original, "standard")
        assertEquals("a", result.actualAudioProviderId)
        assertEquals(0, origin.calls)
    }
    @Test fun originalCallbackCompletesItsQualityLadderBeforeCrossSource() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val attempted = mutableListOf<String>()
        val result = resolver(listOf(origin, a)).resolve(original, "lossless", originalResolver = {
            attempted += listOf("lossless", "exhigh", "standard")
            AudioResource("https://original.example/song", "standard")
        })
        assertEquals(listOf("lossless", "exhigh", "standard"), attempted)
        assertEquals("standard", result.resource.quality)
        assertEquals(0, a.calls)
    }
    @Test fun cancellationDoesNotBecomeFallbackFailure() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        try { resolver(listOf(origin, a)).resolve(original, "standard", originalResolver = { throw CancellationException() }); fail() }
        catch (_: CancellationException) { assertEquals(0, a.calls) }
    }
    @Test fun timeoutIsBoundedAndContinues() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val resolver = PlaybackResolver(ProviderRegistry(listOf(origin, a)), { SourceSettings(true, true, listOf("a")) }, 100)
        val result = resolver.resolve(original, "standard", originalResolver = { delay(500); error("unreachable") })
        assertEquals(SourceErrorCode.TIMEOUT, result.attempts.first().error)
        assertEquals("a", result.actualAudioProviderId)
    }
    @Test fun repeatedConcurrentRequestsShareTheSuccessfulResolution() = runTest {
        val origin = Provider("original", original)
        val resolver = resolver(listOf(origin))
        coroutineScope { List(5) { async { resolver.resolve(original, "standard") } }.awaitAll() }
        assertEquals(1, origin.calls)
    }
    @Test fun rejectedActualBytesNeverResolveAgain() = runTest {
        val origin = Provider("original", original)
        val a = Provider("a", track("a"))
        val resolver = resolver(listOf(origin, a))
        val first = resolver.resolve(original, "standard")
        val next = resolver.resolve(original, "standard", setOf(audioResourceIdentity(first)))
        assertEquals("a", next.actualAudioProviderId)
        assertNotEquals(audioResourceIdentity(first), audioResourceIdentity(next))
    }
    @Test fun matchingRejectsVersionsCoversAndAmbiguity() {
        val matcher = TrackMatcher()
        assertTrue(matcher.rank(original, listOf(track("a", "Song (Live)"), track("b").copy(version = "cover"))).isEmpty())
        assertNull(matcher.automatic(matcher.rank(original, listOf(track("a"), track("b")))))
        assertNull(matcher.automatic(matcher.rank(original, listOf(track("a", duration = 230_000)))))
        assertNotNull(matcher.automatic(matcher.rank(original, listOf(track("a")))))
        assertTrue(matcher.rank(original, listOf(track("a").copy(album = SourceAlbum("Live")))).isEmpty())
    }
    @Test fun manualMatchesAndPerTrackDisableArePersistablePolicyInputs() = runTest {
        val origin = Provider("original", original, SourceErrorCode.UNAVAILABLE)
        val a = Provider("a", track("a", "User-selected alternate"))
        val mappingKey = TrackRefCodec.encode(original.ref) + "|a"
        val settings = SourceSettings(true, true, listOf("a"), manualMatches = mapOf(mappingKey to TrackRefCodec.encode(a.candidate.ref)))
        assertEquals(a.candidate.ref, resolver(listOf(origin, a), settings).resolve(original, "standard").resolvedTrackRef)
        val disabled = settings.copy(disabledTrackFallbacks = setOf(TrackRefCodec.encode(original.ref)))
        try { resolver(listOf(origin, a), disabled).resolve(original, "standard"); fail() }
        catch (_: PlaybackResolutionException) { assertEquals(1, a.calls) }
    }
    @Test fun reservedProviderCannotBeReplacedAndCapabilitiesAreExplicit() = runTest {
        try { ProviderRegistry(listOf(Provider(ProviderIds.NETEASE, track(ProviderIds.NETEASE)))); fail() }
        catch (_: IllegalArgumentException) { }
        val lyricsOnly = object : MusicProvider {
            override val id = "lyrics"
            override val displayName = "Lyrics"
            override val capabilities = setOf(SourceCapability.LYRICS)
        }
        val registry = ProviderRegistry(listOf(lyricsOnly))
        assertTrue(registry.playable().isEmpty())
        try { SourceRouter(registry).search("lyrics", "song"); fail() }
        catch (error: SourceException) { assertEquals(SourceErrorCode.UNSUPPORTED, error.code) }
    }

    @Test fun parentTimeoutStopsInsteadOfTryingAnotherProvider() = runTest {
        val origin = Provider("original", original)
        val alternate = Provider("a", track("a"))
        val resolver = resolver(listOf(origin, alternate))
        try {
            withTimeout(10) {
                resolver.resolve(original, "standard", originalResolver = { delay(1000); error("unreachable") })
            }
            fail("Expected parent cancellation")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(0, alternate.calls)
        assertTrue(resolver.lastAttempts.value.isEmpty())
    }

    @Test fun invalidationPreventsOldAccountRequestFromPublishingOrCaching() = runTest {
        val origin = Provider("original", original)
        val resolver = resolver(listOf(origin))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            resolver.resolve(original, "standard", originalResolver = {
                entered.complete(Unit)
                release.await()
                AudioResource("https://audio.example/old-account", "standard")
            })
        }
        entered.await()
        resolver.invalidate()
        release.complete(Unit)
        try { old.await(); fail("Expected invalidated resolution to cancel") } catch (_: CancellationException) { }
        assertNull(resolver.lastResolution.value)
        assertTrue(resolver.lastAttempts.value.isEmpty())
        assertEquals("https://audio.example/original", resolver.resolve(original, "standard").resource.uri)
    }

    @Test fun routerRestoresMixedProviderOrderAndDuplicates() = runTest {
        val a = Provider("a", track("a"))
        val b = Provider("b", track("b"))
        val router = SourceRouter(ProviderRegistry(listOf(a, b)))
        val refs = listOf(a.candidate.ref, b.candidate.ref, a.candidate.ref)
        assertEquals(refs, router.getTracks(refs).map { it.ref })
    }

    @Test fun manualBindingRejectsAProviderReturningAnotherTrack() = runTest {
        val origin = Provider("original", original, SourceErrorCode.UNAVAILABLE)
        val alternate = Provider("a", track("a"))
        val selected = TrackRef("a", "another-track")
        val settings = SourceSettings(true, true, listOf("a"), manualMatches = mapOf(
            TrackRefCodec.encode(original.ref) + "|a" to TrackRefCodec.encode(selected),
        ))
        try { resolver(listOf(origin, alternate), settings).resolve(original, "standard"); fail() }
        catch (error: PlaybackResolutionException) { assertEquals(SourceErrorCode.INVALID_RESPONSE, error.attempts.last().error) }
        assertEquals(0, alternate.calls)
    }

    @Test fun invalidStrongIdsCannotBypassRecordingEvidence() {
        val matcher = TrackMatcher()
        val claimed = original.copy(isrc = "unknown")
        assertTrue(matcher.rank(claimed, listOf(track("a", title = "Different Song").copy(isrc = "unknown"))).isEmpty())
        val identified = original.copy(isrc = "USABC2600001")
        assertTrue(matcher.rank(identified, listOf(track("a", duration = 300_000).copy(isrc = identified.isrc))).isEmpty())
        assertNotNull(matcher.automatic(matcher.rank(identified, listOf(track("a").copy(isrc = "US-ABC-26-00001")))))
    }
}
