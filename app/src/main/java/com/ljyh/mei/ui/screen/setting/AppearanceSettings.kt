package com.ljyh.mei.ui.screen.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.AccompanimentLyricTextBoldKey
import com.ljyh.mei.constants.AccentColorKey
import com.ljyh.mei.constants.AccompanimentLyricTextSizeKey
import com.ljyh.mei.constants.CoverStyle
import com.ljyh.mei.constants.CoverStyleKey
import com.ljyh.mei.constants.DebugKey
import com.ljyh.mei.constants.DynamicThemeKey
import com.ljyh.mei.constants.LyricTextSize
import com.ljyh.mei.constants.MeshFlowSpeedKey
import com.ljyh.mei.constants.MeshLowFreqVolumeKey
import com.ljyh.mei.constants.MeshPlayingKey
import com.ljyh.mei.constants.MeshRenderScaleKey
import com.ljyh.mei.constants.MeshStaticModeKey
import com.ljyh.mei.constants.MeshSubdivisionKey
import com.ljyh.mei.constants.NormalLyricTextBoldKey
import com.ljyh.mei.constants.NormalLyricTextSizeKey
import com.ljyh.mei.constants.OriginalCoverKey
import com.ljyh.mei.constants.PlayerStyle
import com.ljyh.mei.constants.PlayerKeepScreenOnKey
import com.ljyh.mei.constants.PlayerStyleKey
import com.ljyh.mei.constants.PlaylistCoverStyle
import com.ljyh.mei.constants.PlaylistCoverStyleKey
import com.ljyh.mei.constants.PlaylistTrackTableHeaderKey
import com.ljyh.mei.constants.ProgressBarStyle
import com.ljyh.mei.constants.ProgressBarStyleKey
import com.ljyh.mei.constants.TabletAnimationStyle
import com.ljyh.mei.constants.TabletAnimationStyleKey
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassToggle
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.glass.IosColorPicker
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosPopupButton
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (dynamicTheme, setDynamicTheme) = rememberPreference(DynamicThemeKey, true)
    val (accentColorArgb, setAccentColorArgb) = rememberPreference(AccentColorKey, 0xFFFF3B30L)
    var showColorPicker by remember { mutableStateOf(false) }
    val (playlistStyle, setPlaylistStyle) = rememberEnumPreference(PlaylistCoverStyleKey, PlaylistCoverStyle.Cover)
    val (playlistHeader, setPlaylistHeader) = rememberPreference(PlaylistTrackTableHeaderKey, false)
    val (playerStyle, setPlayerStyle) = rememberEnumPreference(PlayerStyleKey, PlayerStyle.AppleMusic)
    val (keepScreenOn, setKeepScreenOn) = rememberPreference(PlayerKeepScreenOnKey, false)
    val (originalCover, setOriginalCover) = rememberPreference(OriginalCoverKey, false)
    val (coverStyle, setCoverStyle) = rememberEnumPreference(CoverStyleKey, CoverStyle.Square)
    val (progressStyle, setProgressStyle) = rememberEnumPreference(ProgressBarStyleKey, ProgressBarStyle.LINEAR)
    val (tabletAnimation, setTabletAnimation) = rememberEnumPreference(TabletAnimationStyleKey, TabletAnimationStyle.FLIP_3D)
    val (meshFlowSpeed, setMeshFlowSpeed) = rememberPreference(MeshFlowSpeedKey, 0.25f)
    val (meshRenderScale, setMeshRenderScale) = rememberPreference(MeshRenderScaleKey, 0.75f)
    val (meshLowFrequency, setMeshLowFrequency) = rememberPreference(MeshLowFreqVolumeKey, 0.1f)
    val (meshSubdivision, setMeshSubdivision) = rememberPreference(MeshSubdivisionKey, 50)
    val (meshStatic, setMeshStatic) = rememberPreference(MeshStaticModeKey, false)
    val (meshPlaying, setMeshPlaying) = rememberPreference(MeshPlayingKey, true)
    val (primarySize, setPrimarySize) = rememberEnumPreference(NormalLyricTextSizeKey, LyricTextSize.Size28)
    val (primaryBold, setPrimaryBold) = rememberPreference(NormalLyricTextBoldKey, true)
    val (secondarySize, setSecondarySize) = rememberEnumPreference(AccompanimentLyricTextSizeKey, LyricTextSize.Size18)
    val (secondaryBold, setSecondaryBold) = rememberPreference(AccompanimentLyricTextBoldKey, true)
    val (debug, setDebug) = rememberPreference(DebugKey, false)

    IosPinnedListPage(
        title = stringResource(R.string.appearance_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.appearance_theme)) {
                AppearanceToggle(R.string.appearance_dynamic_theme, R.string.appearance_dynamic_theme_description, "paintpalette", dynamicTheme, setDynamicTheme)
                AppearanceChoice(
                    R.string.appearance_accent_color,
                    "paintpalette.fill",
                    accentColorArgb,
                    listOf(accentColorArgb),
                    { stringResource(if (dynamicTheme) R.string.appearance_accent_dynamic else R.string.appearance_accent_custom) },
                    { showColorPicker = true },
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.appearance_playlist)) {
                AppearanceChoice(
                    R.string.appearance_playlist_cover,
                    "photo.stack",
                    playlistStyle,
                    PlaylistCoverStyle.entries,
                    { style -> stringResource(when (style) {
                        PlaylistCoverStyle.Cover -> R.string.appearance_cover_playlist
                        PlaylistCoverStyle.FirstSongImage -> R.string.appearance_cover_first_song
                        PlaylistCoverStyle.Combination -> R.string.appearance_cover_combination
                    }) },
                    setPlaylistStyle,
                )
                AppearanceToggle(R.string.appearance_playlist_header, R.string.appearance_playlist_header_description, "rectangle.split.3x1.fill", playlistHeader, setPlaylistHeader)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.appearance_player)) {
                AppearanceChoice(
                    R.string.appearance_player_style, "music.note.house", playerStyle, PlayerStyle.entries,
                    { if (it == PlayerStyle.AppleMusic) "Apple Music" else stringResource(R.string.appearance_player_classic) }, setPlayerStyle,
                )
                AppearanceToggle(
                    R.string.appearance_keep_screen_on,
                    R.string.appearance_keep_screen_on_description,
                    "sun.max",
                    keepScreenOn,
                    setKeepScreenOn,
                )
                AppearanceToggle(R.string.appearance_original_cover, R.string.appearance_original_cover_description, "photo", originalCover, setOriginalCover)
                AppearanceChoice(
                    R.string.appearance_song_cover, "square.on.circle", coverStyle, CoverStyle.entries,
                    { if (it == CoverStyle.Circle) stringResource(R.string.appearance_circle) else stringResource(R.string.appearance_square) },
                    setCoverStyle, enabled = playerStyle == PlayerStyle.Classic,
                )
                AppearanceChoice(
                    R.string.appearance_progress_style, "waveform.path", progressStyle, ProgressBarStyle.entries,
                    { if (it == ProgressBarStyle.WAVE) stringResource(R.string.appearance_progress_wave) else stringResource(R.string.appearance_progress_linear) }, setProgressStyle,
                )
                AppearanceChoice(
                    R.string.appearance_tablet_animation, "rectangle.landscape.rotate", tabletAnimation, TabletAnimationStyle.entries,
                    { style -> stringResource(when (style) {
                        TabletAnimationStyle.SLIDE -> R.string.appearance_animation_slide
                        TabletAnimationStyle.CROSSFADE -> R.string.appearance_animation_crossfade
                        TabletAnimationStyle.ZOOM -> R.string.appearance_animation_zoom
                        TabletAnimationStyle.FLIP_3D -> R.string.appearance_animation_flip
                    }) }, setTabletAnimation,
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.appearance_fluid_background)) {
                AppearanceFloatChoice(R.string.appearance_flow_speed, "gauge.with.dots.needle.33percent", meshFlowSpeed, listOf(.05f, .1f, .15f, .25f, .5f), setMeshFlowSpeed)
                AppearanceFloatChoice(R.string.appearance_render_scale, "eye", meshRenderScale, listOf(.25f, .5f, .75f, 1f), setMeshRenderScale)
                AppearanceFloatChoice(R.string.appearance_beat_sensitivity, "waveform.path.ecg", meshLowFrequency, listOf(0f, .1f, .2f, .3f, .4f, .5f), setMeshLowFrequency)
                AppearanceChoice(R.string.appearance_mesh_subdivision, "circle.grid.2x2.fill", meshSubdivision, (1..8).map { it * 10 }, { it.toString() }, setMeshSubdivision)
                AppearanceToggle(R.string.appearance_static_mode, R.string.appearance_static_mode_description, "pause.circle", meshStatic, setMeshStatic)
                AppearanceToggle(R.string.appearance_background_animation, R.string.appearance_background_animation_description, "play.circle", meshPlaying, setMeshPlaying)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.appearance_lyrics)) {
                AppearanceToggle(R.string.appearance_primary_bold, null, "bold", primaryBold, setPrimaryBold)
                AppearanceChoice(R.string.appearance_primary_size, "textformat.size", primarySize, LyricTextSize.entries, { "${it.text} sp" }, setPrimarySize)
                AppearanceToggle(R.string.appearance_secondary_bold, null, "bold", secondaryBold, setSecondaryBold)
                AppearanceChoice(R.string.appearance_secondary_size, "textformat.size", secondarySize, LyricTextSize.entries, { "${it.text} sp" }, setSecondarySize)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.appearance_diagnostics)) {
                AppearanceToggle(R.string.appearance_player_debug, R.string.appearance_player_debug_description, "ladybug", debug, setDebug)
            }
        }
    }
    IosColorPicker(
        visible = showColorPicker,
        selectedColor = androidx.compose.ui.graphics.Color(accentColorArgb.toInt()),
        onColorSelected = { color ->
            setAccentColorArgb(color.toArgb().toLong() and 0xFFFFFFFFL)
            setDynamicTheme(false)
        },
        onDismiss = { showColorPicker = false },
        title = stringResource(R.string.appearance_accent_color),
    )
}

@Composable
private fun AppearanceToggle(
    titleRes: Int,
    descriptionRes: Int?,
    systemName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), onClick = { onCheckedChange(!checked) }) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null)
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(stringResource(titleRes))
                descriptionRes?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GlassToggle(checked, onCheckedChange)
        }
    }
}

@Composable
private fun <T> AppearanceChoice(
    titleRes: Int,
    systemName: String,
    selected: T,
    values: List<T>,
    valueLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(systemName, null)
            Text(stringResource(titleRes), modifier = Modifier.weight(1f).padding(horizontal = 13.dp))
            IosPopupButton(
                selected = selected,
                items = values,
                onSelected = onSelected,
                label = valueLabel,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun AppearanceFloatChoice(
    titleRes: Int,
    systemName: String,
    selected: Float,
    values: List<Float>,
    onSelected: (Float) -> Unit,
) {
    AppearanceChoice(titleRes, systemName, selected, values, { "${(it * 100).toInt()}%" }, onSelected)
}
