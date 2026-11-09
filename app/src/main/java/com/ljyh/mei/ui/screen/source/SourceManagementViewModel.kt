package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.source.script.*
import com.ljyh.mei.source.update.PreparedSourceUpdate
import com.ljyh.mei.source.update.SourceUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class SourceManagementViewModel @Inject constructor(
    val registry: ProviderRegistry,
    val preferences: SourcePreferences,
    private val manager: ScriptPluginManager,
    val resolver: PlaybackResolver,
    private val updates: SourceUpdateManager,
    private val features: SourceFeatureClient,
) : ViewModel() {
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceSettings())
    val providers = registry.providers
    val plugins = manager.installed
    val startupError = manager.startupError
    val pending = MutableStateFlow<PreparedPlugin?>(null)
    val pendingUpdate = MutableStateFlow<PreparedSourceUpdate?>(null)
    val updateState = updates.state
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val editing = MutableStateFlow<InstalledPlugin?>(null)
    val configuration = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableFallbackOrder = MutableStateFlow<List<String>?>(null)
    val fallbackOrder = mutableFallbackOrder.asStateFlow()
    private var savedFallbackOrder = emptyList<String>()
    private var pendingFallbackOrder: List<String>? = null
    private var pendingPersistedOrder: List<String>? = null
    private var fallbackOrderVersion = 0L
    private val fallbackOrderMutex = Mutex()
    private val actionMutex = Mutex()

    init {
        viewModelScope.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { runCatching { discardPending() } } }
        }
        viewModelScope.launch {
            combine(preferences.settings, registry.revision) { settings, _ -> settings.fallbackOrder }
                .collect { order ->
                    savedFallbackOrder = order
                    if (pendingPersistedOrder == order) {
                        pendingFallbackOrder = null
                        pendingPersistedOrder = null
                    }
                    refreshFallbackOrder()
                }
        }
        action { manager.loadInstalled() }
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        actionMutex.withLock {
            busy.value = true
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message.value = sourceErrorMessage((error as? SourceException)?.code) }
            finally { busy.value = false }
        }
    } }
    fun reload() { action { manager.loadInstalled() } }

    fun inspect(uri: Uri) = action {
        discardPending()
        pending.value = manager.inspect(uri)
    }
    fun refreshMarket() = action {
        updates.refreshMarket()
        message.value = updates.state.value.message
    }
    fun checkUpdates() = action {
        updates.checkNow()
        message.value = updates.state.value.message
    }
    fun setAutoUpdates(enabled: Boolean) = action { updates.setAutoUpdates(enabled) }
    fun prepareMarketInstall(packageId: String) = action {
        discardPending()
        val prepared = updates.prepareMarketInstall(packageId)
        pendingUpdate.value = prepared
        pending.value = prepared.plugin
    }
    fun prepareUpdate(packageId: String) = action {
        discardPending()
        val prepared = updates.prepareUpdate(packageId)
        pendingUpdate.value = prepared
        pending.value = prepared.plugin
    }
    fun cancelImport() { viewModelScope.launch { withContext(NonCancellable) { discardPending() } } }
    private suspend fun discardPending() {
        val prepared = pending.value
        val update = pendingUpdate.value
        pending.value = null
        pendingUpdate.value = null
        if (update != null) updates.discardPrepared(update)
        else if (prepared != null) manager.discardPrepared(prepared)
    }
    fun install(onInstalled: (InstalledPlugin) -> Unit = {}) = action {
        val prepared = pending.value ?: return@action
        val installed = pendingUpdate.value?.let { updates.installReviewed(it) }
            ?: manager.install(prepared, approved = true)
        includePlayableFallbacks()
        pending.value = null
        pendingUpdate.value = null
        editing.value?.takeIf { it.manifest.id == prepared.manifest.id }?.let {
            editing.value = manager.installed.value.find { installed -> installed.manifest.id == it.manifest.id }
        }
        resolver.invalidate()
        onInstalled(installed)
    }
    fun enabled(plugin: InstalledPlugin, enabled: Boolean) = action {
        manager.setEnabled(plugin.manifest.id, enabled)
        includePlayableFallbacks()
        if (editing.value?.manifest?.id == plugin.manifest.id) {
            editing.value = manager.installed.value.find { it.manifest.id == plugin.manifest.id }
        }
        resolver.invalidate()
    }
    fun uninstall(plugin: InstalledPlugin, onRemoved: () -> Unit = {}) = action {
        manager.uninstall(plugin.manifest.id)
        resolver.invalidate()
        onRemoved()
    }
    fun edit(plugin: InstalledPlugin) = action {
        configuration.value = manager.configuration(plugin.manifest.id)
        editing.value = plugin
    }
    fun saveConfiguration(onSaved: () -> Unit = {}) = action {
        val plugin = editing.value ?: return@action
        val values = configuration.value.toMap()
        if (values != manager.configuration(plugin.manifest.id)) manager.configure(plugin.manifest.id, values)
        includePlayableFallbacks()
        resolver.invalidate()
        onSaved()
    }
    fun webLogin(providerId: String) = action {
        features.bind(providerId, "source-web-login:${java.util.UUID.randomUUID()}").webLogin()
        includePlayableFallbacks()
        resolver.invalidate()
    }
    private suspend fun includePlayableFallbacks() {
        preferences.update { settings ->
            settings.copy(fallbackOrder = (settings.fallbackOrder + registry.playable().map { it.instanceId.value }).distinct())
        }
    }
    fun update(transform: (SourceSettings) -> SourceSettings) = action { preferences.update(transform) }
    /** Glasmio calls this once a drag settles, with every visible instance in its new position. */
    fun reorderFallbackOrder(instanceIds: List<String>) {
        val reordered = orderedPlayableInstances(instanceIds, registry.playable().map { it.instanceId.value })
        if (reordered == mutableFallbackOrder.value) return
        pendingFallbackOrder = reordered
        pendingPersistedOrder = null
        mutableFallbackOrder.value = reordered
        val version = ++fallbackOrderVersion
        viewModelScope.launch {
            var stored: List<String>? = null
            var changed = false
            var committed = false
            try {
                fallbackOrderMutex.withLock {
                    if (version != fallbackOrderVersion) return@withLock
                    preferences.update { settings ->
                        val completeOrder = mergeFallbackOrder(settings.fallbackOrder, reordered)
                        stored = completeOrder
                        changed = completeOrder != settings.fallbackOrder
                        if (version == fallbackOrderVersion) pendingPersistedOrder = completeOrder
                        settings.copy(fallbackOrder = completeOrder)
                    }
                    committed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == fallbackOrderVersion) message.value = sourceErrorMessage((error as? SourceException)?.code)
            } finally {
                if (version == fallbackOrderVersion) {
                    if (committed) stored?.let { savedFallbackOrder = it }
                    if (!committed || !changed) {
                        pendingFallbackOrder = null
                        pendingPersistedOrder = null
                    }
                    refreshFallbackOrder()
                }
            }
        }
    }

    private fun refreshFallbackOrder() {
        mutableFallbackOrder.value = orderedPlayableInstances(
            pendingFallbackOrder ?: savedFallbackOrder,
            registry.playable().map { it.instanceId.value },
        )
    }
    fun chooseMatch(pending: PendingSourceMatch, candidate: MatchCandidate) = action {
        preferences.bind(pending.origin, candidate.track.ref)
        resolver.invalidate()
        message.value = "已保存匹配，下次播放时生效"
    }
}

internal fun orderedPlayableInstances(order: List<String>, playable: List<String>): List<String> {
    val available = playable.toSet()
    return (order.filter { it in available } + playable).distinct()
}

/** Preserve unavailable instances so disabling or reinstalling a source does not discard its slot. */
internal fun mergeFallbackOrder(saved: List<String>, reordered: List<String>): List<String> {
    val visible = reordered.distinct()
    val visibleIds = visible.toSet()
    var next = 0
    return (saved.distinct().map { id -> if (id in visibleIds) visible[next++] else id } + visible.drop(next)).distinct()
}

internal fun sourceErrorMessage(code: SourceErrorCode?): String = when (code) {
    SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED -> "音源需要重新登录或配置"
    SourceErrorCode.TIMEOUT -> "音源请求超时"
    SourceErrorCode.UNSUPPORTED -> "音源不支持此功能"
    SourceErrorCode.MISSING_PROVIDER -> "音源已禁用或未安装"
    SourceErrorCode.NOT_FOUND, SourceErrorCode.UNAVAILABLE -> "没有可用的音频资源"
    SourceErrorCode.AMBIGUOUS_MATCH -> "请选择匹配的歌曲"
    SourceErrorCode.INVALID_RESPONSE, SourceErrorCode.PLUGIN -> "插件格式、权限或返回数据无效"
    else -> "操作失败，请检查音源配置或网络"
}
