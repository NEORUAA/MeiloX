package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.local.LocalProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRegistry(providers: List<MusicProvider>, startupPending: Boolean = false) {
    @Inject constructor(local: LocalProvider) : this(listOf(local), startupPending = true)

    private val initialized = kotlinx.coroutines.CompletableDeferred<Unit>().apply { if (!startupPending) complete(Unit) }
    suspend fun awaitInitialized() = initialized.await()
    internal fun completeInitialization() { initialized.complete(Unit) }

    private val entries = ConcurrentHashMap<String, MusicProvider>()
    private val mutableProviders = MutableStateFlow<List<MusicProvider>>(emptyList())
    private val mutableRevision = MutableStateFlow(0L)
    val providers: StateFlow<List<MusicProvider>> = mutableProviders
    val revision: StateFlow<Long> = mutableRevision
    init { providers.forEach { register(it) } }

    @Synchronized fun register(provider: MusicProvider) {
        require(provider.id.isNotBlank() && provider.instanceId.value.isNotBlank())
        if (provider.id == ProviderIds.LOCAL) {
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
    fun requireProvider(id: String): MusicProvider = provider(id) ?: throw SourceException(
        when (providers.value.firstOrNull { it.id == id }?.status) {
            SourceStatus.NEEDS_CONFIGURATION -> SourceErrorCode.AUTH_REQUIRED
            SourceStatus.INCOMPATIBLE -> SourceErrorCode.UNSUPPORTED
            SourceStatus.FAILED -> SourceErrorCode.PLUGIN
            else -> SourceErrorCode.MISSING_PROVIDER
        },
    )
    fun supportsFeatureRoute(providerId: String, route: String): Boolean =
        (provider(providerId) as? FeatureContribution)?.featureRoutes?.contains(route) == true
    fun playable(): List<MusicProvider> = providers.value.filter { it.status == SourceStatus.READY && SourceCapability.PLAYBACK in it.capabilities && it is PlaybackCapability }
}
