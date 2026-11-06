package com.ljyh.mei.ui.screen.source

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.account.rememberNeteaseSessionType
import com.ljyh.mei.data.network.NeteaseSessionType
import io.github.neoruaa.glasmio.ui.*

@Composable
fun SourceManagementScreen(
    viewModel: SourceManagementViewModel = hiltViewModel(),
    pluginId: String? = null,
) {
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
    val displayedOrder by viewModel.fallbackOrder.collectAsState()
    val nav = LocalNavController.current
    val neteaseSession = rememberNeteaseSessionType()
    val padding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::inspect) }
    val ready = providers.filter { it.status == SourceStatus.READY && SourceCapability.SEARCH in it.capabilities }
    val browseReady = providers.filter { it.status == SourceStatus.READY &&
        (SourceCapability.FEED in it.capabilities || SourceCapability.SEARCH in it.capabilities || SourceCapability.LIBRARY in it.capabilities) }
    val providersByInstance = providers.associateBy { it.instanceId.value }
    val availablePlugin = plugins.firstOrNull { it.manifest.id == pluginId }
    val plugin = editing?.takeIf { it.manifest.id == pluginId } ?: availablePlugin
    val configurationReady = editing?.manifest?.id == pluginId
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(pluginId, availablePlugin?.manifest?.id) {
        if (pluginId != null && availablePlugin != null) viewModel.edit(availablePlugin)
    }

    MeiloXPinnedListPage(
        title = plugin?.manifest?.name ?: "音源管理",
        onNavigateBack = nav::navigateUp,
        bottomPadding = padding.calculateBottomPadding(),
    ) {
        if (pluginId != null) {
            if (plugin != null && configurationReady) {
                item {
                    Column {
                        GlasmioGroupedList {
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
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
                                        autoCorrectEnabled = false,
                                    ),
                                )
                            }
                            GlasmioListRow(title = "保存配置", enabled = !busy, onClick = { viewModel.saveConfiguration { nav.navigateUp() } })
                            GlasmioListRow(title = "从文件更新", enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) })
                            GlasmioListRow(title = "移除音源", enabled = !busy, onClick = { removing = true })
                        }
                        plugin.manifest.description?.takeIf(String::isNotBlank)?.let { GlasmioGroupedTableFooter(it) }
                    }
                }
            } else {
                item {
                    if (busy) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else GlasmioGroupedList {
                        GlasmioListRow(
                            title = if (plugin == null) "音源已移除或未安装" else "重新加载配置",
                            showDisclosure = false,
                            onClick = plugin?.let { installed -> { viewModel.edit(installed) } },
                        )
                    }
                }
            }
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
                        onClick = { Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(installed.manifest.id)) } },
                    )
                }
            } }
            item { GlasmioGroupedList {
                ProviderSetting("默认浏览源", settings.preferredBrowseProvider, browseReady) { id -> viewModel.update { it.copy(preferredBrowseProvider = id) } }
                ProviderSetting("默认搜索源", settings.preferredSearchProvider, ready) { id -> viewModel.update { it.copy(preferredSearchProvider = id) } }
            } }
            item { GlasmioGroupedList {
                GlasmioListRow(title = "自动切换播放音源", showDisclosure = false, trailing = {
                    MeiloXSwitch(settings.autoFallbackEnabled, { checked -> viewModel.update { it.copy(autoFallbackEnabled = checked) } })
                })
                GlasmioListRow(title = "优先原始音源", showDisclosure = false, trailing = {
                    MeiloXSwitch(settings.preferOriginalProvider, { checked -> viewModel.update { it.copy(preferOriginalProvider = checked) } })
                })
            } }
            displayedOrder?.takeIf { it.isNotEmpty() }?.let { order ->
                item { Text("切换顺序", Modifier.padding(start = 16.dp, top = 12.dp)) }
                item {
                    GlasmioReorderableGroup(
                        items = order,
                        onReorder = viewModel::reorderFallbackOrder,
                        dragHandleContentDescription = stringResource(R.string.reorder_drag_handle),
                        moveUpLabel = stringResource(R.string.move_up),
                        moveDownLabel = stringResource(R.string.move_down),
                    ) { instanceId ->
                        Text(providersByInstance[instanceId]?.displayName ?: instanceId, modifier = Modifier.weight(1f))
                    }
                }
            }
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
            GlasmioAlertButtonSpec("移除", onClick = {
                removing = false
                plugin?.let { installed -> viewModel.uninstall(installed) { nav.navigateUp() } }
            }),
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
private fun capabilityLabel(capabilities: Set<String>): String = buildSet {
    if ("feed.home" in capabilities) add("首页")
    if ("feed.discover" in capabilities) add("发现")
    if ("catalog.search" in capabilities) add("搜索")
    if ("catalog.track" in capabilities) add("歌曲")
    if ("album.get" in capabilities) add("专辑")
    if ("artist.get" in capabilities) add("歌手")
    if ("playlist.get" in capabilities) add("歌单")
    if ("account.get" in capabilities) add("账号")
    if (capabilities.any { it.startsWith("library.") }) add("媒体库")
    if ("track.like" in capabilities || "collection.save" in capabilities) add("收藏")
    if (capabilities.any { it in PlaylistEditAction.entries.map(PlaylistEditAction::operation) }) add("歌单管理")
    if ("playback.resolve" in capabilities) add("播放")
    if ("lyrics.get" in capabilities) add("歌词")
}.joinToString(" · ")
