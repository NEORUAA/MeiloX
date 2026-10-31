package com.ljyh.mei.ui.screen.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.AutoCacheEnabledKey
import com.ljyh.mei.constants.AutoCachePlaybackThresholdKey
import com.ljyh.mei.constants.AutoCacheQualityKey
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQuality
import com.ljyh.mei.constants.DownloadQualityKey
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import com.ljyh.mei.ui.component.MeiloXSwitch
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioStepper
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.utils.DownloadManager
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSetting(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (downloadPath, onDownloadPathChange) = rememberPreference(
        DownloadPathKey,
        DownloadManager.getDefaultDownloadPath(),
    )
    val (downloadQuality, onDownloadQualityChange) = rememberEnumPreference(
        DownloadQualityKey,
        DownloadQuality.EXHIGH,
    )
    val (automaticCache, onAutomaticCacheChange) = rememberPreference(AutoCacheEnabledKey, false)
    val (threshold, onThresholdChange) = rememberPreference(AutoCachePlaybackThresholdKey, 5)
    val (automaticQuality, onAutomaticQualityChange) = rememberEnumPreference(
        AutoCacheQualityKey,
        DownloadQuality.EXHIGH,
    )

    MeiloXPinnedListPage(
        title = stringResource(R.string.download_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.download_storage)) {
                GlasmioListRow(
                    title = stringResource(R.string.download_save_location),
                    systemName = "folder",
                )
                GlasmioTextField(
                    value = downloadPath,
                    onValueChange = onDownloadPathChange,
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.download_quality)) {
                DownloadQuality.entries.forEach { quality ->
                    DownloadQualityCard(quality = quality, selected = quality == downloadQuality, onClick = { onDownloadQualityChange(quality) })
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.automatic_cache)) {
                SettingsCard(Modifier.fillMaxWidth(), onClick = { onAutomaticCacheChange(!automaticCache) }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SfIcon("arrow.down.circle", null)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.automatic_cache_by_play_count))
                            Text(stringResource(R.string.automatic_cache_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MeiloXSwitch(automaticCache, onAutomaticCacheChange)
                    }
                }
            }
        }
        if (automaticCache) {
            item {
                SettingsGroup(stringResource(R.string.automatic_cache_threshold)) {
                    SettingsCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.automatic_cache_count, threshold), modifier = Modifier.weight(1f))
                            GlasmioStepper(value = threshold, onValueChange = onThresholdChange, range = 1..100)
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.automatic_cache_quality)) {
                    DownloadQuality.entries.forEach { quality ->
                        DownloadQualityCard(quality = quality, selected = quality == automaticQuality, onClick = { onAutomaticQualityChange(quality) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQualityCard(
    quality: DownloadQuality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SfIcon(if (selected) "checkmark.circle.fill" else "circle", null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(quality.label)
                Text(
                    quality.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
