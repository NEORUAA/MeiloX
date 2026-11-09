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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.R
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.*
import com.ljyh.mei.source.script.PluginManifest
import com.ljyh.mei.source.update.SourceUpdateReviewReason
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.screen.Screen
import io.github.neoruaa.glasmio.ui.*
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors

@Composable
fun SourceManagementScreen(
    viewModel: SourceManagementViewModel = hiltViewModel(),
    pluginId: String? = null,
    market: Boolean = false,
) {
    val settings by viewModel.settings.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    val startupError by viewModel.startupError.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val pendingUpdate by viewModel.pendingUpdate.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val editing by viewModel.editing.collectAsState()
    val configuration by viewModel.configuration.collectAsState()
    val pendingMatch by viewModel.resolver.pendingMatch.collectAsState()
    val attempts by viewModel.resolver.lastAttempts.collectAsState()
    val displayedOrder by viewModel.fallbackOrder.collectAsState()
    val nav = LocalNavController.current
    val padding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::inspect) }
    val ready = providers.filter { it.status == SourceStatus.READY && SourceCapability.SEARCH in it.capabilities }
    val browseReady = providers.filter { it.status == SourceStatus.READY &&
        (SourceCapability.FEED in it.capabilities || SourceCapability.SEARCH in it.capabilities || SourceCapability.LIBRARY in it.capabilities) }
    val providersByInstance = providers.associateBy { it.instanceId.value }
    val availablePlugin = plugins.firstOrNull { it.manifest.id == pluginId }
    val plugin = availablePlugin
    val configurationReady = editing?.manifest?.id == pluginId
    var removing by remember { mutableStateOf(false) }
    val update = updateState.items.firstOrNull { it.entry.packageId == pluginId && it.updateAvailable }
    val working = busy || updateState.checking || updateState.busyPackageId != null
    val reviewHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.4f).coerceAtMost(360.dp)
    LaunchedEffect(pluginId, availablePlugin?.manifest?.id) {
        if (pluginId != null && availablePlugin != null) viewModel.edit(availablePlugin)
    }
    LaunchedEffect(market) { if (market) viewModel.refreshMarket() }

    MeiloXPinnedListPage(
        title = if (market) "插件市场" else plugin?.manifest?.name ?: "音源管理",
        onNavigateBack = nav::navigateUp,
        bottomPadding = padding.calculateBottomPadding(),
        actions = {
            if (pluginId == null) listOf(meiloxHeaderAction(
                key = if (market) "source-market-refresh" else "source-check-updates",
                contentDescription = if (market) "刷新插件市场" else "检查更新",
                onClick = if (market) viewModel::refreshMarket else viewModel::checkUpdates,
                enabled = !working,
            ) {
                if (updateState.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else SfIcon("arrow.clockwise", null)
            }) else emptyList()
        },
    ) {
        if (!market && pluginId == null && startupError != null) item("source-startup-error") {
            GlasmioGroupedList { GlasmioListRow(startupError!!, onClick = viewModel::reload) }
        }
        if (market) {
            if (updateState.items.isEmpty()) item("source-market-empty") {
                if (working) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } else GlasmioGroupedList { GlasmioListRow("暂无可用插件", showDisclosure = false) }
            }
            if (updateState.items.isNotEmpty()) item("source-market-items") {
                GlasmioGroupedList {
                    updateState.items.forEach { item ->
                        val installed = item.installedVersion != null
                        GlasmioListRow(
                            title = item.entry.name,
                            subtitle = item.entry.version,
                            enabled = !working,
                            trailing = { Text(when {
                                updateState.busyPackageId == item.entry.packageId -> "处理中"
                                item.updateAvailable -> "更新"
                                installed -> "已安装"
                                else -> "安装"
                            }) },
                            onClick = {
                                when {
                                    item.updateAvailable -> viewModel.prepareUpdate(item.entry.packageId)
                                    !installed -> viewModel.prepareMarketInstall(item.entry.packageId)
                                    else -> Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(item.entry.packageId)) }
                                }
                            },
                        )
                    }
                }
            }
        } else if (pluginId != null) {
            if (plugin != null && configurationReady) {
                item {
                    Column {
                        GlasmioGroupedList {
                            GlasmioListRow(title = "版本", subtitle = plugin.manifest.version, showDisclosure = false)
                            update?.let { available ->
                                GlasmioListRow(title = "更新至 ${available.entry.version}", enabled = !working,
                                    onClick = { viewModel.prepareUpdate(available.entry.packageId) })
                            }
                            GlasmioListRow(title = "状态", subtitle = if (plugin.status == SourceStatus.FAILED) "插件执行失败，可停用后重新启用" else statusLabel(plugin.status), showDisclosure = false)
                            GlasmioListRow(title = "支持功能", subtitle = capabilityLabel(plugin.manifest.capabilities), showDisclosure = false)
                            GlasmioListRow(title = "启用", showDisclosure = false, trailing = {
                                MeiloXSwitch(plugin.enabled, { viewModel.enabled(plugin, it) }, enabled = !working)
                            })
                            if ("webLogin" in plugin.manifest.features["login"].orEmpty()) {
                                GlasmioListRow(title = "网页登录", systemName = "globe", enabled = !working && plugin.status == SourceStatus.READY,
                                    onClick = { viewModel.webLogin(plugin.manifest.providerId) })
                            }
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
                            GlasmioListRow(title = "保存配置", enabled = !working, onClick = { viewModel.saveConfiguration { nav.navigateUp() } })
                            GlasmioListRow(title = "从文件更新", enabled = !working, onClick = { importer.launch(arrayOf("*/*")) })
                            GlasmioListRow(title = "移除音源", enabled = !working, onClick = { removing = true })
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
                GlasmioListRow(title = "插件市场", systemName = "square.grid.2x2", enabled = !working,
                    onClick = { Screen.SourceMarket.navigate(nav) })
                GlasmioListRow(title = "从文件导入", systemName = "square.and.arrow.down", enabled = !working, onClick = { importer.launch(arrayOf("*/*")) })
                plugins.forEach { installed ->
                    GlasmioListRow(
                        title = installed.manifest.name,
                        subtitle = "${installed.manifest.version} · ${statusLabel(installed.status)}",
                        onClick = { Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(installed.manifest.id)) } },
                    )
                }
            } }
            item { GlasmioGroupedList {
                ProviderSetting("默认浏览源", settings.preferredBrowseProvider, browseReady) { id -> viewModel.update { it.copy(preferredBrowseProvider = id) } }
                ProviderSetting("默认搜索源", settings.preferredSearchProvider, ready) { id -> viewModel.update { it.copy(preferredSearchProvider = id) } }
            } }
            item { GlasmioGroupedList {
                GlasmioListRow(title = "启动时自动更新", showDisclosure = false, trailing = {
                    MeiloXSwitch(updateState.autoUpdates, viewModel::setAutoUpdates, enabled = !working)
                })
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
        onDismissRequest = { if (!busy) viewModel.cancelImport() },
        title = pending?.manifest?.name.orEmpty(),
        buttons = listOf(
            GlasmioAlertButtonSpec("取消", role = GlasmioAlertButtonRole.Cancel, enabled = !busy, onClick = viewModel::cancelImport),
            GlasmioAlertButtonSpec(if (plugins.any { it.manifest.id == pending?.manifest?.id }) "更新" else "安装", enabled = !working, onClick = {
                viewModel.install { installed ->
                    if (installed.status == SourceStatus.NEEDS_CONFIGURATION && pluginId != installed.manifest.id) {
                        Screen.SourcePluginSettings.navigate(nav) { addPath(Uri.encode(installed.manifest.id)) }
                    }
                }
            }),
        ),
    ) {
        key(pending) {
        Text(
            pending?.manifest?.let { pluginReviewText(it, pendingUpdate?.reviewReasons.orEmpty(), pendingUpdate?.entry?.repository) }.orEmpty(),
            style = IosTypography.body,
            color = LocalGlassColors.current.content,
            modifier = Modifier.fillMaxWidth().heightIn(max = reviewHeight)
                .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        )
        }
        Spacer(Modifier.height(24.dp))
    }
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
private fun pluginReviewText(manifest: PluginManifest, reasons: List<SourceUpdateReviewReason>, repository: String?): String = buildList {
    add("${manifest.author} · ${manifest.version}")
    repository?.let { add("发布仓库：$it") }
    add(capabilityLabel(manifest.capabilities))
    if (reasons.isNotEmpty()) add("需要确认：" + reasons.distinct().joinToString("、") { reason -> when (reason) {
        SourceUpdateReviewReason.UNKNOWN_ORIGIN -> "来源未知"
        SourceUpdateReviewReason.REPOSITORY_CHANGED -> "发布仓库变更"
        SourceUpdateReviewReason.NETWORK -> "网络权限变更"
        SourceUpdateReviewReason.STORAGE -> "存储权限变更"
        SourceUpdateReviewReason.NATIVE_SDK -> "底层能力变更"
        SourceUpdateReviewReason.EFFECTS -> "系统权限变更"
        SourceUpdateReviewReason.LINKS -> "支持链接变更"
        SourceUpdateReviewReason.SESSION -> "账号会话变更"
        SourceUpdateReviewReason.CAPABILITIES -> "支持功能变更"
        SourceUpdateReviewReason.REQUIRED_CONFIGURATION -> "需要补充配置"
    } })
    add(if (manifest.permissions.network.isEmpty()) "无网络权限" else "网络：${manifest.permissions.network.joinToString()}")
    if (manifest.permissions.storage) add("允许独立存储")
    if (manifest.permissions.nativeSdks.isNotEmpty()) add("底层能力：" + manifest.permissions.nativeSdks.map { when (it) {
        "netease.transport" -> "网易云安全通信"
        "netease.urs" -> "网易云账号登录"
        "netease.securityHeaders" -> "网易云设备安全"
        "netease.ursPuzzle", "netease.ursCaptcha" -> "网易云登录验证"
        "netease.history" -> "历史播放上报"
        else -> it
    } }.distinct().joinToString("、"))
    if (manifest.permissions.effects.isNotEmpty()) add("系统能力：" + manifest.permissions.effects.map { when (it) {
        "files.pick" -> "选择文件"
        "files.upload" -> "上传所选文件"
        "sdk.call" -> "使用底层服务"
        "web.auth" -> "网页登录"
        "web.page" -> "打开网页"
        "qr.scan" -> "扫描二维码"
        "audio.capture" -> "录音"
        "audio.fingerprint" -> "识别音乐"
        "sms.compose" -> "编写短信"
        else -> it
    } }.distinct().joinToString("、"))
    if (manifest.linkHosts.isNotEmpty()) add("支持链接：${manifest.linkHosts.joinToString()}")
    if (manifest.sessionKeys.isNotEmpty()) add("允许保存本音源账号会话")
    if (manifest.sessionCookieNames.isNotEmpty()) add("Cookie 会话：${manifest.sessionCookieNames.values.flatten().distinct().joinToString()}")
    manifest.config.filter { it.required }.takeIf { it.isNotEmpty() }?.let { fields -> add("必填配置：${fields.joinToString { it.label }}") }
    add("仅安装你信任的插件")
}.filter(String::isNotBlank).joinToString("\n")

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
    if ("feature.invoke" in capabilities) add("扩展功能")
}.joinToString(" · ")
