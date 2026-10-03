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
import com.ljyh.mei.constants.AutoMixDurationKey
import com.ljyh.mei.constants.AutoMixEnabledKey
import com.ljyh.mei.constants.AutoMixFadeCurve
import com.ljyh.mei.constants.AutoMixFadeCurveKey
import com.ljyh.mei.constants.AutoMixMaxTempoAdjustmentKey
import com.ljyh.mei.constants.AutoMixMode
import com.ljyh.mei.constants.AutoMixModeKey
import com.ljyh.mei.constants.AutoMixTailCutBarsKey
import com.ljyh.mei.constants.AutoMixTempoMatchingKey
import com.ljyh.mei.constants.AutoMixTransitionBarsKey
import com.ljyh.mei.constants.LoopPlaybackKey
import com.ljyh.mei.constants.CloudShuffleEnabledKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.MusicQualityKey
import com.ljyh.mei.constants.NoAudioSourceKey
import com.ljyh.mei.constants.PreviousPlaybackKey
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.GlassSlider
import com.ljyh.mei.ui.glass.GlassToggle
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosPopupButton
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySetting(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val navController = LocalNavController.current
    val (musicQuality, onMusicQualityChange) = rememberEnumPreference(MusicQualityKey, MusicQuality.EXHIGH)
    val (loopPlayback, onLoopPlaybackChange) = rememberPreference(LoopPlaybackKey, true)
    val (cloudShuffle, onCloudShuffleChange) = rememberPreference(CloudShuffleEnabledKey, true)
    val (previousPlayback, onPreviousPlaybackChange) = rememberPreference(PreviousPlaybackKey, true)
    val (noAudioSource, onNoAudioSourceChange) = rememberPreference(NoAudioSourceKey, false)
    val (autoMixEnabled, onAutoMixEnabledChange) = rememberPreference(AutoMixEnabledKey, false)
    val (autoMixMode, onAutoMixModeChange) = rememberEnumPreference(AutoMixModeKey, AutoMixMode.Smart)
    val (autoMixDuration, onAutoMixDurationChange) = rememberPreference(AutoMixDurationKey, 8f)
    val (fadeCurve, onFadeCurveChange) = rememberEnumPreference(AutoMixFadeCurveKey, AutoMixFadeCurve.EqualPower)
    val (tempoMatching, onTempoMatchingChange) = rememberPreference(AutoMixTempoMatchingKey, true)
    val (maximumTempo, onMaximumTempoChange) = rememberPreference(AutoMixMaxTempoAdjustmentKey, 5f)
    val (transitionBars, onTransitionBarsChange) = rememberPreference(AutoMixTransitionBarsKey, 8)
    val (tailCutBars, onTailCutBarsChange) = rememberPreference(AutoMixTailCutBarsKey, 4)
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    IosPinnedListPage(
        title = stringResource(R.string.playback_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.playback_behavior)) {
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { Screen.EqualizerSettings.navigate(navController) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SfIcon("slider.vertical.3", contentDescription = null)
                        Text(stringResource(R.string.equalizer), modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                        SfIcon("chevron.forward", contentDescription = null, tint = LocalGlassColors.current.separator, size = 16.dp)
                    }
                }
                ToggleRow(stringResource(R.string.loop_playback), "arrow.trianglehead.2.clockwise.rotate.90", loopPlayback, onLoopPlaybackChange)
                ToggleRow(
                    stringResource(R.string.cloud_shuffle), "shuffle", cloudShuffle, onCloudShuffleChange,
                    description = stringResource(R.string.cloud_shuffle_description),
                )
                ToggleRow(stringResource(R.string.skip_unavailable), "waveform.slash", noAudioSource, onNoAudioSourceChange)
                ToggleRow(stringResource(R.string.previous_behavior), "backward.fill", previousPlayback, onPreviousPlaybackChange)
                ValueRow(stringResource(R.string.music_quality), "waveform") {
                    IosPopupButton(
                        selected = musicQuality,
                        items = MusicQuality.entries,
                        onSelected = onMusicQualityChange,
                        label = { "${it.explanation} · ${it.text}" },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.automix)) {
                ToggleRow(stringResource(R.string.automix_enabled), "waveform.path", autoMixEnabled, onAutoMixEnabledChange)
            }
        }
        if (autoMixEnabled) {
            item {
                com.ljyh.mei.ui.glass.IosGroupedList {
                    GlassSegmentedControl(
                        items = listOf(AutoMixMode.Smart to stringResource(R.string.automix_smart), AutoMixMode.Fixed to stringResource(R.string.automix_fixed)),
                        selected = autoMixMode, onSelected = onAutoMixModeChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    SliderRow(stringResource(R.string.automix_duration, autoMixDuration.roundToInt()), autoMixDuration, onAutoMixDurationChange, 3f..20f)
                    GlassSegmentedControl(
                        items = listOf(
                            AutoMixFadeCurve.EqualPower to stringResource(R.string.fade_equal_power),
                            AutoMixFadeCurve.Smooth to stringResource(R.string.fade_smooth),
                            AutoMixFadeCurve.Linear to stringResource(R.string.fade_linear),
                        ), selected = fadeCurve, onSelected = onFadeCurveChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    GlassSegmentedControl(
                        items = listOf(4, 8, 16).map { it to stringResource(R.string.automix_bars, it) },
                        selected = transitionBars, onSelected = onTransitionBarsChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    GlassSegmentedControl(
                        items = listOf(0, 2, 4, 8).map { it to if (it == 0) stringResource(R.string.automix_tail_none) else stringResource(R.string.automix_tail_bars, it) },
                        selected = tailCutBars, onSelected = onTailCutBarsChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    ToggleRow(stringResource(R.string.tempo_matching), "waveform.path", tempoMatching, onTempoMatchingChange)
                    if (tempoMatching) SliderRow(stringResource(R.string.maximum_tempo_adjustment, maximumTempo.roundToInt()), maximumTempo, onMaximumTempoChange, 1f..8f)
                }
            }
        }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    symbol: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    GlassCard(Modifier.fillMaxWidth(), onClick = { onCheckedChange(!checked) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(symbol, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GlassToggle(checked, onCheckedChange)
        }
    }
}

@Composable
private fun ValueRow(title: String, symbol: String, value: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SfIcon(symbol, contentDescription = null)
            Text(title, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
            value()
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title)
            GlassSlider(value, onValueChange, modifier = Modifier.fillMaxWidth(), valueRange = range)
        }
    }
}
