package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.source.script.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SourceManagementViewModel @Inject constructor(
    val registry: ProviderRegistry,
    val preferences: SourcePreferences,
    private val manager: ScriptPluginManager,
    val resolver: PlaybackResolver,
) : ViewModel() {
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceSettings())
    val providers = registry.providers
    val plugins = manager.installed
    val pending = MutableStateFlow<PreparedPlugin?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val editing = MutableStateFlow<InstalledPlugin?>(null)
    val configuration = MutableStateFlow<Map<String, String>>(emptyMap())

    init { action { manager.loadInstalled() } }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        busy.value = true
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message.value = sourceErrorMessage((error as? SourceException)?.code) }
        finally { busy.value = false }
    } }
    fun inspect(uri: Uri) = action { pending.value = manager.inspect(uri) }
    fun cancelImport() { pending.value = null }
    fun install() = action {
        val prepared = pending.value ?: return@action
        manager.install(prepared, approved = true)
        preferences.update { settings ->
            settings.copy(fallbackOrder = (settings.fallbackOrder + registry.playable().map { it.instanceId.value }).distinct())
        }
        pending.value = null
        editing.value?.takeIf { it.manifest.id == prepared.manifest.id }?.let {
            editing.value = manager.installed.value.find { installed -> installed.manifest.id == it.manifest.id }
        }
        resolver.invalidate()
    }
    fun enabled(plugin: InstalledPlugin, enabled: Boolean) = action {
        manager.setEnabled(plugin.manifest.id, enabled)
        if (editing.value?.manifest?.id == plugin.manifest.id) {
            editing.value = manager.installed.value.find { it.manifest.id == plugin.manifest.id }
        }
        resolver.invalidate()
    }
    fun uninstall(plugin: InstalledPlugin) = action {
        manager.uninstall(plugin.manifest.id)
        editing.value = null
        resolver.invalidate()
    }
    fun edit(plugin: InstalledPlugin) = action {
        configuration.value = manager.configuration(plugin.manifest.id)
        editing.value = plugin
    }
    fun saveConfiguration() = action {
        val plugin = editing.value ?: return@action
        manager.configure(plugin.manifest.id, configuration.value)
        editing.value = null
        resolver.invalidate()
    }
    fun update(transform: (SourceSettings) -> SourceSettings) = action { preferences.update(transform) }
    fun move(instance: String, delta: Int, displayedOrder: List<String>) = update { settings ->
        val order = displayedOrder.toMutableList()
        val index = order.indexOf(instance)
        val target = index + delta
        if (index >= 0 && target in order.indices) java.util.Collections.swap(order, index, target)
        settings.copy(fallbackOrder = order)
    }
    fun chooseMatch(pending: PendingSourceMatch, candidate: MatchCandidate) = action {
        preferences.bind(pending.origin, candidate.track.ref)
        resolver.invalidate()
        message.value = "已保存匹配，下次播放时生效"
    }
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
