package com.ljyh.mei.ui.screen.search

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.source.api.CatalogCapability
import com.ljyh.mei.source.api.MusicProvider
import com.ljyh.mei.source.api.PageRequest
import com.ljyh.mei.source.api.SourceArtist
import com.ljyh.mei.source.api.SourceCapability
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourcePage
import com.ljyh.mei.source.api.SourceStatus
import com.ljyh.mei.source.api.Track
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real preferences in a private fixture file; no host settings or playback queue are changed. */
@RunWith(AndroidJUnit4::class)
class SourceSearchInstrumentedTest {
    private class Catalog(override val id: String) : MusicProvider, CatalogCapability {
        override val displayName = id
        override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TRACK)
        override var status = SourceStatus.READY
        val requests = CopyOnWriteArrayList<String>()
        val slowCancelled = AtomicBoolean(false)
        override suspend fun search(query: String, page: PageRequest): SourcePage<Track> {
            requests += "$query:${page.offset}"
            if (id == "a" && query == "slow") {
                try { delay(10_000) } catch (cancelled: CancellationException) {
                    slowCancelled.set(true)
                    throw cancelled
                }
            }
            if (query == "bad") throw SourceException(SourceErrorCode.TIMEOUT)
            val first = Track(TrackRef(id, "$query:first"), "$id $query", listOf(SourceArtist("Artist")))
            return if (page.offset == 0) SourcePage(listOf(first), hasMore = true)
                else SourcePage(listOf(first, first.copy(ref = TrackRef(id, "$query:second"))))
        }
        override suspend fun getTrack(ref: TrackRef): Track? = null
    }

    @Test fun searchSwitchingCancelsOldRequestsAndKeepsBrowsePreferenceIndependent(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "source-search-${UUID.randomUUID()}").apply { mkdirs() }
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = dataScope,
            produceFile = { File(directory, "settings.preferences_pb") })
        val preferences = SourcePreferences(data)
        preferences.update { it.copy(preferredSearchProvider = "a", preferredBrowseProvider = "a") }
        val a = Catalog("a")
        val b = Catalog("b")
        val registry = ProviderRegistry(listOf(a, b))
        val model = withContext(Dispatchers.Main) { SourceSearchViewModel(SourceRouter(registry), preferences, registry) }
        try {
            withTimeout(5_000) { model.selectedProviderId.first { it == "a" } }
            withContext(Dispatchers.Main) { model.search("slow") }
            withTimeout(5_000) { while (a.requests.isEmpty()) delay(10) }
            withContext(Dispatchers.Main) { model.selectProvider("b") }
            withTimeout(5_000) { model.state.first { !it.loading && it.tracks.firstOrNull()?.ref?.providerId == "b" } }
            withTimeout(5_000) { while (!a.slowCancelled.get()) delay(10) }
            assertEquals("b", preferences.snapshot().preferredSearchProvider)
            assertEquals("a", preferences.snapshot().preferredBrowseProvider)

            withContext(Dispatchers.Main) { model.search("fast") }
            withTimeout(5_000) { model.state.first { it.query == "fast" && !it.loading && it.tracks.isNotEmpty() } }
            withContext(Dispatchers.Main) { model.loadMore() }
            val paged = withTimeout(5_000) { model.state.first { !it.loading && it.tracks.size == 2 } }
            assertEquals(listOf(TrackRef("b", "fast:first"), TrackRef("b", "fast:second")), paged.tracks.map { it.ref })
            assertFalse(paged.hasMore)

            withContext(Dispatchers.Main) { model.search("bad") }
            withTimeout(5_000) { model.state.first { it.error == SourceErrorCode.TIMEOUT } }
            withContext(Dispatchers.Main) { model.useBrowsePreference(); model.search("browse") }
            withTimeout(5_000) { model.state.first { !it.loading && it.tracks.firstOrNull()?.ref?.providerId == "a" } }
            withContext(Dispatchers.Main) { a.status = SourceStatus.FAILED; registry.refresh() }
            withTimeout(5_000) { model.state.first { it.error == SourceErrorCode.MISSING_PROVIDER } }
            withTimeout(5_000) { model.sourceChoices.first { it.map(MusicProvider::id) == listOf("b") } }
            assertEquals("b", preferences.snapshot().preferredSearchProvider)
        } finally {
            withContext(Dispatchers.Main) { model.viewModelScope.cancel() }
            dataScope.cancel()
            directory.deleteRecursively()
        }
    }
}
