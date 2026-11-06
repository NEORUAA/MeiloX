package com.ljyh.mei.ui.screen.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.account.rememberNeteaseSessionType
import com.ljyh.mei.data.network.NeteaseSessionType
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.*

@Composable
fun SourceManagementScreen(viewModel: SourceManagementViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val editing by viewModel.editing.collectAsState()
    val configuration by viewModel.configuration.collectAsState()
    val pendingMatch by viewModel.resolver.pendingMatch.collectAsState()
    val attempts by viewModel.resolver.lastAttempts.collectAsState()
    val nav = LocalNavController.current
    val neteaseSession = rememberNeteaseSessionType()
    val padding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::inspect) }
    val ready = providers.filter { it.status == SourceStatus.READY && SourceCapability.SEARCH in it.capabilities }
    val playable = providers.filter { it.status == SourceStatus.READY && SourceCapability.PLAYBACK in it.capabilities }
    val order = (settings.fallbackOrder.mapNotNull { id -> playable.find { it.instanceId.value == id } } + playable).distinctBy { it.instanceId }
    val displayedOrder = order.map { it.instanceId.value }
    val plugin = editing
    var removing by remember { mutableStateOf(false) }

    MeiloXPinnedListPage(
        title = plugin?.manifest?.name ?: "音源管理",
        onNavigateBack = { if (plugin != null) viewModel.editing.value = null else nav.navigateUp() },
        bottomPadding = padding.calculateBottomPadding(),
    ) {
        if (plugin != null) {
            item { GlasmioGroupedList {
                GlasmioListRow(title = "版本", subtitle = plugin.manifest.version, showDisclosure = false)
                GlasmioListRow(title = "状态", subtitle = if (plugin.status == SourceStatus.FAILED) "插件执行失败，可停用后重新启用" else statusLabel(plugin.status), showDisclosure = false)
                GlasmioListRow(title = "支持功能", subtitle = capabilityLabel(plugin.manifest.capabilities), showDisclosure = false)
                GlasmioListRow(title = "启用", showDisclosure = false, trailing = {
                    MeiloXSwitch(plugin.enabled, { viewModel.enabled(plugin, it) }, enabled = !busy)
                })
                plugin.manifest.config.forEach { field ->
                    GlasmioTextField(
                        value = configuration[field.key].orEmpty(),
                        onValueChange = { viewModel.configuration.value = configuration + (field.key to it) },
                        placeholder = field.label,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    )
                }
                GlasmioListRow(title = "保存配置", enabled = !busy, onClick = viewModel::saveConfiguration)
                GlasmioListRow(title = "从文件更新", enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) })
                GlasmioListRow(title = "移除音源", enabled = !busy, onClick = { removing = true })
            } }
        } else {
            item { GlasmioGroupedList {
                GlasmioListRow(title = "从文件导入", systemName = "square.and.arrow.down", enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) })
                providers.filter { it.isBuiltIn && it.id != ProviderIds.LOCAL }.forEach { provider ->
                    GlasmioListRow(title = provider.displayName,
                        subtitle = "内置 · ${provider.version} · " + if (neteaseSession == NeteaseSessionType.None) "未登录" else "已登录",
                        onClick = { if (neteaseSession == NeteaseSessionType.None) Screen.NeteaseLogin.navigate(nav) else Screen.AccountHome.navigate(nav) })
                }
                plugins.forEach { installed ->
                    GlasmioListRow(
                        title = installed.manifest.name,
                        subtitle = "${installed.manifest.version} · ${statusLabel(installed.status)} · ${capabilityLabel(installed.manifest.capabilities)}",
                        onClick = { viewModel.edit(installed) },
                    )
                }
            } }
            item { GlasmioGroupedList {
                ProviderSetting("默认浏览源", settings.preferredBrowseProvider, ready) { id -> viewModel.update { it.copy(preferredBrowseProvider = id) } }
                ProviderSetting("默认搜索源", settings.preferredSearchProvider, ready) { id -> viewModel.update { it.copy(preferredSearchProvider = id) } }
            } }
            item { GlasmioGroupedList {
                GlasmioListRow(title = "自动切换播放音源", showDisclosure = false, trailing = {
                    MeiloXSwitch(settings.autoFallbackEnabled, { checked -> viewModel.update { it.copy(autoFallbackEnabled = checked, fallbackOrder = displayedOrder) } })
                })
                GlasmioListRow(title = "优先原始音源", showDisclosure = false, trailing = {
                    MeiloXSwitch(settings.preferOriginalProvider, { checked -> viewModel.update { it.copy(preferOriginalProvider = checked) } })
                })
            } }
            item { Text("切换顺序", Modifier.padding(start = 16.dp, top = 12.dp)) }
            item { GlasmioGroupedList {
                if (playable.none { !it.isBuiltIn }) GlasmioListRow(title = "未安装第三方播放音源", showDisclosure = false)
                order.forEachIndexed { index, provider ->
                    GlasmioListRow(title = provider.displayName, showDisclosure = false, trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlasmioSheetToolbarButton(onClick = { viewModel.move(provider.instanceId.value, -1, displayedOrder) }, enabled = index > 0) {
                                SfIcon("arrow.up", "上移 ${provider.displayName}")
                            }
                            GlasmioSheetToolbarButton(onClick = { viewModel.move(provider.instanceId.value, 1, displayedOrder) }, enabled = index < order.lastIndex) {
                                SfIcon("arrow.down", "下移 ${provider.displayName}")
                            }
                        }
                    })
                }
            } }
            pendingMatch?.let { match ->
                item { Text("确认歌曲匹配 · ${match.originTitle.ifBlank { match.origin.trackId }}", Modifier.padding(start = 16.dp, top = 12.dp)) }
                item { GlasmioGroupedList {
                    match.candidates.forEach { candidate ->
                        GlasmioListRow(
                            title = candidate.track.title,
                            subtitle = candidate.track.artists.joinToString { it.name } + " · " + candidate.track.album.title,
                            onClick = { viewModel.chooseMatch(match, candidate) },
                        )
                    }
                    GlasmioListRow(title = "暂不选择", onClick = viewModel.resolver::dismissPendingMatch)
                } }
            }
            if (attempts.isNotEmpty()) {
                item { Text("最近解析", Modifier.padding(start = 16.dp, top = 12.dp)) }
                item { GlasmioGroupedList {
                    attempts.forEach { attempt ->
                        GlasmioListRow(
                            title = providers.find { it.instanceId == attempt.instanceId }?.displayName ?: attempt.instanceId.value,
                            subtitle = attempt.error?.let(::sourceErrorMessage) ?: "可播放",
                            showDisclosure = false,
                        )
                    }
                } }
            }
        }
    }
    GlasmioAlertDialog(
        visible = pending != null,
        onDismissRequest = viewModel::cancelImport,
        title = pending?.manifest?.name.orEmpty(),
        message = pending?.manifest?.let { manifest ->
            "${manifest.author} · ${manifest.version}\n${capabilityLabel(manifest.capabilities)}\n" +
                (if (manifest.permissions.network.isEmpty()) "无网络权限" else "网络：${manifest.permissions.network.joinToString()}") +
                (if (manifest.permissions.storage) "\n允许独立存储" else "") + "\n仅安装你信任的插件"
        }.orEmpty(),
        buttons = listOf(
            GlasmioAlertButtonSpec("取消", role = GlasmioAlertButtonRole.Cancel, onClick = viewModel::cancelImport),
            GlasmioAlertButtonSpec("安装", onClick = viewModel::install),
        ),
    )
    GlasmioAlertDialog(
        visible = removing && plugin != null,
        onDismissRequest = { removing = false },
        title = "移除音源？",
        message = "已保存的歌曲和下载文件会保留。",
        buttons = listOf(
            GlasmioAlertButtonSpec("取消", role = GlasmioAlertButtonRole.Cancel, onClick = { removing = false }),
            GlasmioAlertButtonSpec("移除", onClick = { removing = false; plugin?.let(viewModel::uninstall) }),
        ),
    )
    GlasmioAlertDialog(
        visible = message != null,
        onDismissRequest = { viewModel.message.value = null },
        title = "音源管理", message = message.orEmpty(),
        buttons = listOf(GlasmioAlertButtonSpec("好", onClick = { viewModel.message.value = null })),
    )
}

@Composable
private fun ProviderSetting(title: String, selected: String, providers: List<MusicProvider>, onSelected: (String) -> Unit) {
    GlasmioListRow(title = title, showDisclosure = false, trailing = {
        GlasmioPopUpButton(selected, providers.map { it.id }, onSelected, label = { id -> providers.find { it.id == id }?.displayName ?: "来源不可用" })
    })
}
private fun statusLabel(status: SourceStatus): String = when (status) {
    SourceStatus.READY -> "已启用"
    SourceStatus.DISABLED -> "已禁用"
    SourceStatus.NEEDS_CONFIGURATION -> "待配置"
    SourceStatus.INCOMPATIBLE -> "版本不兼容"
    SourceStatus.FAILED -> "加载失败"
}
private fun capabilityLabel(capabilities: Set<String>): String = capabilities.mapNotNull {
    when (it) { "catalog.search" -> "搜索"; "catalog.track" -> "歌曲"; "playback.resolve" -> "播放"; "lyrics.get" -> "歌词"; else -> null }
}.joinToString(" · ")
