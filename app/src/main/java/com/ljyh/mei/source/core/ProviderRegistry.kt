package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.netease.NeteaseProvider
import com.ljyh.mei.source.local.LocalProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRegistry(providers: List<MusicProvider>) {
    @Inject constructor(netease: NeteaseProvider, local: LocalProvider) : this(listOf(netease, local))

    private val entries = ConcurrentHashMap<String, MusicProvider>()
    private val mutableProviders = MutableStateFlow<List<MusicProvider>>(emptyList())
    private val mutableRevision = MutableStateFlow(0L)
    val providers: StateFlow<List<MusicProvider>> = mutableProviders
    val revision: StateFlow<Long> = mutableRevision
    init { providers.forEach { register(it) } }

    @Synchronized fun register(provider: MusicProvider) {
        require(provider.id.isNotBlank() && provider.instanceId.value.isNotBlank())
        if (provider.id in setOf(ProviderIds.NETEASE, ProviderIds.LOCAL)) {
            require(provider.isBuiltIn) { "Reserved provider ID" }
        }
        val old = entries[provider.instanceId.value]
        require(old == null || !old.isBuiltIn || old === provider) { "Built-in provider cannot be replaced" }
        entries[provider.instanceId.value] = provider
        publish()
    }

    @Synchronized fun unregister(instanceId: ProviderInstanceId) {
        require(entries[instanceId.value]?.isBuiltIn != true) { "Built-in provider cannot be removed" }
        entries.remove(instanceId.value)
        publish()
    }

    @Synchronized fun refresh() = publish()
    private fun publish() {
        mutableProviders.value = entries.values.sortedWith(compareByDescending<MusicProvider> { it.isBuiltIn }.thenBy { it.displayName })
        mutableRevision.value += 1
    }
    fun instance(id: ProviderInstanceId): MusicProvider? = entries[id.value]
    fun provider(id: String): MusicProvider? = providers.value.firstOrNull { it.id == id && it.status == SourceStatus.READY }
    fun requireProvider(id: String): MusicProvider = provider(id) ?: throw SourceException(SourceErrorCode.MISSING_PROVIDER)
    fun playable(): List<MusicProvider> = providers.value.filter { it.status == SourceStatus.READY && SourceCapability.PLAYBACK in it.capabilities && it is PlaybackCapability }
}

@Singleton
class SourceRouter @Inject constructor(val registry: ProviderRegistry) {
    suspend fun search(providerId: String, query: String, page: PageRequest = PageRequest()): SourcePage<Track> {
        val provider = registry.requireProvider(providerId)
        if (SourceCapability.SEARCH !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).search(query, page)
    }
    suspend fun getTrack(ref: TrackRef): Track? {
        val provider = registry.requireProvider(ref.providerId)
        if (SourceCapability.TRACK !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? CatalogCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).getTrack(ref)
            ?.also { if (it.ref != ref) throw SourceException(SourceErrorCode.INVALID_RESPONSE) }
    }
    suspend fun getTracks(refs: List<TrackRef>): List<Track> {
        val requested = refs.toSet()
        val tracks = refs.groupBy { it.providerId }.flatMap { (id, group) ->
            val provider = registry.requireProvider(id)
            if (SourceCapability.TRACK !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
            if (provider is BatchCatalogCapability) provider.getTracks(group)
            else group.distinct().mapNotNull { getTrack(it) }
        }
        if (tracks.any { it.ref !in requested }) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val indexed = tracks.associateBy { it.ref }
        return refs.mapNotNull(indexed::get)
    }
    suspend fun availableQualities(ref: TrackRef): List<String> =
        (registry.requireProvider(ref.providerId) as? QualityCapability)?.availableQualities(ref).orEmpty()
    suspend fun lyrics(ref: TrackRef): SourceLyrics? {
        val provider = registry.requireProvider(ref.providerId)
        if (SourceCapability.LYRICS !in provider.capabilities) throw SourceException(SourceErrorCode.UNSUPPORTED)
        return (provider as? LyricsCapability ?: throw SourceException(SourceErrorCode.UNSUPPORTED)).getLyrics(ref)
    }
}
