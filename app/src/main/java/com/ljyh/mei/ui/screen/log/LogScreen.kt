package com.ljyh.mei.ui.screen.log

import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.navigation.MeiloXPageHeaders
import com.ljyh.mei.ui.navigation.MeiloXPageEntry
import com.ljyh.mei.ui.navigation.MeiRoute
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationScaffold
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavHeaderMotion
import androidx.compose.ui.platform.LocalDensity
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import androidx.compose.foundation.layout.asPaddingValues
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: LogViewModel = hiltViewModel()
) {
    val fileList by viewModel.logFiles.collectAsState()
    val content by viewModel.currentFileContent.collectAsState()
    val navController = LocalNavController.current
    val colors = LocalGlassColors.current
    val darkTheme = colors.isDark

    val crashColor = if (darkTheme) Color(0xFFCF6679) else Color.Red
    val logColor = if (darkTheme) Color(0xFFBB86FC) else Color.Blue
    val secondaryTextColor = colors.secondaryContent
    val logBackgroundColor = colors.elevatedBackground

    MeiloXPinnedListPage(
        title = "日志收集",
        onNavigateBack = navController::popBackStack,
        bottomPadding = LocalPlayerAwareWindowInsets.current
            .asPaddingValues()
            .calculateBottomPadding(),
        actions = {
            listOf(meiloxHeaderAction(
                key = "logs.clear",
                contentDescription = "Clear All",
                onClick = viewModel::clearAllLogs,
            ) { SfIcon("trash", null) })
        },
    ) {
            items(fileList) { file ->
                LogFileItem(
                    file = file,
                    crashColor = crashColor,
                    logColor = logColor,
                    secondaryTextColor = secondaryTextColor,
                    onClick = { viewModel.readContent(file) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
    }

    // 如果 content 不为空，显示详情弹窗
    if (content != null) {
        LogDetailDialog(
            content = content!!,
            fileName = "日志详情",
            onDismiss = { viewModel.closeContent() },
            onShare = {
                val currentFile = fileList.find { it.readText() == content }
                if (currentFile != null) {
                    viewModel.shareCurrentFile()
                }
            },
            logBackgroundColor = logBackgroundColor
        )
    }
}

@Composable
fun LogFileItem(
    file: File,
    crashColor: Color,
    logColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit
) {
    val isCrash = file.name.startsWith("crash")
    val date = Date(file.lastModified())
    val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isCrash) "崩溃错误" else "运行日志",
                    color = if (isCrash) crashColor else logColor,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = format.format(date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Size: ${file.length() / 1024} KB",
                style = MaterialTheme.typography.labelSmall,
                color = secondaryTextColor
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailDialog(
    content: String,
    fileName: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    logBackgroundColor: Color
) {
    val entryKey = remember { MeiRoute("log-detail-dialog") }
    val headers = remember { MeiloXPageHeaders() }
    val headerMotion = remember(entryKey) { GlasmioNavHeaderMotion(entryKey.route) }
    val scrollState = rememberScrollState()
    val blurDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
    val scrollProgress by remember(scrollState, blurDistancePx) {
        derivedStateOf { (scrollState.value / blurDistancePx).coerceIn(0f, 1f) }
    }
    SideEffect { headers.retain(setOf(entryKey.route)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            GlasmioNavigationScaffold(
                motion = headerMotion,
                header = { headers.entry(it).spec },
            ) {
                MeiloXPageEntry(entryKey, headers) {
                    MeiloXPinnedPage(
                        title = fileName,
                        onNavigateBack = onDismiss,
                        backgroundColor = logBackgroundColor,
                        topBarBlurProgress = scrollProgress,
                        actions = {
                            listOf(meiloxHeaderAction(
                                key = "log-detail.share",
                                contentDescription = "Share",
                                onClick = onShare,
                            ) { SfIcon("square.and.arrow.up", null) })
                        },
                    ) { padding ->
                        Box(
                            Modifier.fillMaxSize().verticalScroll(scrollState).padding(padding),
                        ) {
                            Text(
                                text = content,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}
