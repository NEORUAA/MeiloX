package com.ljyh.mei.ui.screen.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.BuildConfig
import com.ljyh.mei.R
import com.ljyh.mei.constants.DevModeKey
import com.ljyh.mei.constants.Github
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.component.VersionUpdateAlert
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.VersionUpdateChecker
import com.ljyh.mei.utils.VersionUpdateResult
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun AboutScreen(viewModel: AboutViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (devMode, onDevModeChange) = rememberPreference(DevModeKey, false)
    var clickCount by remember { mutableIntStateOf(0) }
    val updateCheckScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<VersionUpdateResult?>(null) }

    fun checkForUpdates() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        updateCheckScope.launch {
            updateResult = VersionUpdateChecker.check(BuildConfig.VERSION_NAME)
            isCheckingUpdate = false
        }
    }

    IosPinnedListPage(
        title = stringResource(R.string.settings_about),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = stringResource(R.string.about_logo),
                    modifier = Modifier
                        .size(84.dp)
                        .clip(ContinuousRoundedRectangle(22.dp))
                        .clickable {
                            clickCount++
                            if (clickCount >= 7 && !devMode) {
                                onDevModeChange(true)
                                Toast.makeText(context, R.string.about_developer_enabled, Toast.LENGTH_SHORT).show()
                            }
                        },
                )
                Text("MeiloX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            com.ljyh.mei.ui.screen.setting.SettingsGroup(stringResource(R.string.about_development)) {
                AboutEntry("chevron.left.forwardslash.chevron.right", stringResource(R.string.about_github)) { openUrl(context, Github) }
                AboutEntry("ladybug", stringResource(R.string.about_feedback)) { openUrl(context, "$Github/issues") }
                AboutEntry("apple.terminal", stringResource(R.string.about_logs)) { Screen.Log.navigate(navController) }
                AboutEntry(
                    "arrow.clockwise",
                    if (isCheckingUpdate) {
                        stringResource(R.string.about_checking_updates)
                    } else {
                        stringResource(R.string.about_check_updates)
                    },
//                    stringResource(R.string.about_check_updates_description),
                ) { checkForUpdates() }
            }
        }
        item {
            com.ljyh.mei.ui.screen.setting.SettingsGroup(stringResource(R.string.about_acknowledgements)) {
                AboutEntry("chevron.left.forwardslash.chevron.right", "Mei", stringResource(R.string.about_upstream_description)) { openUrl(context, "https://github.com/ljyh223/Mei") }
                AboutEntry("iphone", "MeloX", stringResource(R.string.about_melox_description)) { openUrl(context, "https://github.com/youshen2/MeloX") }
                AboutEntry("quote.bubble", "amll-ttml-db", stringResource(R.string.about_amll_description)) { openUrl(context, "https://github.com/Steve-xmh/amll-ttml-db") }
                AboutEntry("music.note.list", "accompanist-lyrics-ui", stringResource(R.string.about_lyrics_ui_description)) { openUrl(context, "https://github.com/6xingyv/accompanist-lyrics-ui") }
                AboutEntry("circle.lefthalf.filled", "Backdrop", stringResource(R.string.about_backdrop_description)) { openUrl(context, "https://github.com/Kyant0/AndroidLiquidGlass") }
            }
        }
        if (devMode) {
            item {
                com.ljyh.mei.ui.screen.setting.SettingsGroup(stringResource(R.string.about_developer_debug)) {
                    AboutEntry("cylinder.split.1x2", stringResource(R.string.about_delete_lyrics), stringResource(R.string.about_delete_lyrics_description)) {
                        viewModel.deleteAllCachedLyrics(); Toast.makeText(context, R.string.about_lyrics_deleted, Toast.LENGTH_SHORT).show()
                    }
                    AboutEntry("trash", stringResource(R.string.about_delete_qq_map), stringResource(R.string.about_delete_qq_map_description)) {
                        viewModel.deleteAllQQSongs(); Toast.makeText(context, R.string.about_qq_map_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp).fillMaxWidth()) {
                Text("MeiloX · ${LocalDate.now().year}", style = MaterialTheme.typography.labelSmall)
                Text(
                    stringResource(R.string.about_compose),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    VersionUpdateAlert(
        result = updateResult,
        onDismiss = { updateResult = null },
    )
}

@Composable
private fun AboutSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
private fun AboutEntry(systemName: String, title: String, subtitle: String? = null, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null, size = 21.dp)
            Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SfIcon("chevron.forward", null, size = 15.dp, tint = LocalGlassColors.current.separator)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
