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
import com.ljyh.mei.constants.CloudMusicEnabledKey
import com.ljyh.mei.constants.CloudMusicTabEnabledKey
import com.ljyh.mei.constants.DownloadsEnabledKey
import com.ljyh.mei.constants.DownloadsTabEnabledKey
import com.ljyh.mei.constants.FindMusicTabEnabledKey
import com.ljyh.mei.constants.LibraryTabEnabledKey
import com.ljyh.mei.constants.ListeningHistoryEnabledKey
import com.ljyh.mei.constants.ListeningHistoryTabEnabledKey
import com.ljyh.mei.constants.NavigationTabOrderKey
import com.ljyh.mei.constants.PodcastsEnabledKey
import com.ljyh.mei.constants.PodcastsTabEnabledKey
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassToggle
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosReorderableGroup
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.ContentFeature
import com.ljyh.mei.ui.screen.Index
import com.ljyh.mei.utils.rememberPreference

private data class ContentFeatureSetting(
    val feature: ContentFeature,
    val titleRes: Int,
    val descriptionRes: Int,
    val symbol: String,
)

private val contentFeatureSettings = listOf(
    ContentFeatureSetting(ContentFeature.Podcasts, R.string.content_podcasts, R.string.content_podcasts_description, "dot.radiowaves.left.and.right"),
    ContentFeatureSetting(ContentFeature.Downloads, R.string.content_downloads, R.string.content_downloads_description, "arrow.down.circle"),
    ContentFeatureSetting(ContentFeature.CloudMusic, R.string.content_cloud_music, R.string.content_cloud_music_description, "icloud"),
    ContentFeatureSetting(ContentFeature.ListeningHistory, R.string.content_history, R.string.content_history_description, "clock"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSetting(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (podcasts, setPodcasts) = rememberPreference(PodcastsEnabledKey, true)
    val (downloads, setDownloads) = rememberPreference(DownloadsEnabledKey, true)
    val (cloud, setCloud) = rememberPreference(CloudMusicEnabledKey, true)
    val (history, setHistory) = rememberPreference(ListeningHistoryEnabledKey, true)
    val (findMusicTab, setFindMusicTab) = rememberPreference(FindMusicTabEnabledKey, true)
    val (libraryTab, setLibraryTab) = rememberPreference(LibraryTabEnabledKey, true)
    val (podcastsTab, setPodcastsTab) = rememberPreference(PodcastsTabEnabledKey, false)
    val (downloadsTab, setDownloadsTab) = rememberPreference(DownloadsTabEnabledKey, false)
    val (cloudTab, setCloudTab) = rememberPreference(CloudMusicTabEnabledKey, false)
    val (historyTab, setHistoryTab) = rememberPreference(ListeningHistoryTabEnabledKey, false)
    val (order, setOrder) = rememberPreference(
        NavigationTabOrderKey,
        Index.DefaultOrder.joinToString(",", transform = Index::name),
    )
    val enabledByFeature = mapOf(
        ContentFeature.Podcasts to podcasts,
        ContentFeature.Downloads to downloads,
        ContentFeature.CloudMusic to cloud,
        ContentFeature.ListeningHistory to history,
    )
    val setFeature: (ContentFeature, Boolean) -> Unit = { feature, value ->
        when (feature) {
            ContentFeature.Podcasts -> setPodcasts(value)
            ContentFeature.Downloads -> setDownloads(value)
            ContentFeature.CloudMusic -> setCloud(value)
            ContentFeature.ListeningHistory -> setHistory(value)
        }
    }
    val tabEnabled: (ContentFeature) -> Boolean = {
        when (it) {
            ContentFeature.Podcasts -> podcastsTab
            ContentFeature.Downloads -> downloadsTab
            ContentFeature.CloudMusic -> cloudTab
            ContentFeature.ListeningHistory -> historyTab
        }
    }
    val setTabEnabled: (ContentFeature, Boolean) -> Unit = { feature, value ->
        when (feature) {
            ContentFeature.Podcasts -> setPodcastsTab(value)
            ContentFeature.Downloads -> setDownloadsTab(value)
            ContentFeature.CloudMusic -> setCloudTab(value)
            ContentFeature.ListeningHistory -> setHistoryTab(value)
        }
    }

    IosPinnedListPage(
        title = stringResource(R.string.content_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.content_modules)) {
                contentFeatureSettings.forEach { setting ->
                    val enabled = enabledByFeature.getValue(setting.feature)
                    GlassCard(Modifier.fillMaxWidth(), onClick = { setFeature(setting.feature, !enabled) }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SfIcon(setting.symbol, null)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(setting.titleRes))
                                Text(stringResource(setting.descriptionRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GlassToggle(enabled, onCheckedChange = { setFeature(setting.feature, it) })
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.content_tab_bar)) {
                listOf(
                    Triple(Index.FindMusic, findMusicTab, setFindMusicTab),
                    Triple(Index.Library, libraryTab, setLibraryTab),
                ).forEach { (item, enabled, setEnabled) ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { setEnabled(!enabled) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SfIcon(item.symbol, null)
                            Text(stringResource(item.labelRes), modifier = Modifier.weight(1f))
                            GlassToggle(checked = enabled, onCheckedChange = setEnabled)
                        }
                    }
                }
                contentFeatureSettings.forEach { setting ->
                    val moduleEnabled = enabledByFeature.getValue(setting.feature)
                    val enabled = tabEnabled(setting.feature)
                    val navigationItem = when (setting.feature) {
                        ContentFeature.Podcasts -> Index.Podcasts
                        ContentFeature.Downloads -> Index.Downloads
                        ContentFeature.CloudMusic -> Index.Cloud
                        ContentFeature.ListeningHistory -> Index.History
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = if (moduleEnabled) ({ setTabEnabled(setting.feature, !enabled) }) else null) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SfIcon(navigationItem.symbol, null)
                            Text(stringResource(setting.titleRes), modifier = Modifier.weight(1f))
                            GlassToggle(checked = enabled && moduleEnabled, onCheckedChange = { setTabEnabled(setting.feature, it) }, enabled = moduleEnabled)
                        }
                    }
                }
                Text(stringResource(R.string.content_tab_order_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
            }
        }
        val currentOrder = order.split(',')
            .mapNotNull { name -> Index.entries.firstOrNull { it.name == name } }
            .distinct()
            .toMutableList()
            .apply { Index.entries.forEach { if (it !in this) add(it) } }
        val reorderable = currentOrder.filter {
            it != Index.Home && it != Index.Settings && it != Index.Search
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSectionTitle(stringResource(R.string.content_tab_order))
                IosReorderableGroup(
                    items = reorderable,
                    onReorder = { reordered ->
                        val reorderedIterator = reordered.iterator()
                        val updatedOrder = currentOrder.map { item ->
                            if (item in reorderable) reorderedIterator.next() else item
                        }
                        setOrder(updatedOrder.joinToString(",", transform = Index::name))
                    },
                    dragHandleContentDescription = stringResource(R.string.reorder_drag_handle),
                    moveUpLabel = stringResource(R.string.move_up),
                    moveDownLabel = stringResource(R.string.move_down),
                ) { item ->
                    SfIcon(item.symbol, null)
                    Text(
                        text = stringResource(item.labelRes),
                        style = IosTypography.body,
                        color = LocalGlassColors.current.content,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
            }
        }
    }
}
