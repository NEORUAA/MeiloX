package com.ljyh.mei.ui.screen.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ljyh.mei.R
import com.ljyh.mei.constants.AllowConcurrentPlaybackKey
import com.ljyh.mei.constants.AudioOutputMode
import com.ljyh.mei.constants.AudioOutputModeKey
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
import com.ljyh.mei.constants.NotificationAction
import com.ljyh.mei.constants.NotificationLeftActionKey
import com.ljyh.mei.constants.NotificationRightActionKey
import com.ljyh.mei.constants.PreviousPlaybackKey
import com.ljyh.mei.constants.RestorePlaybackPositionKey
import com.ljyh.mei.constants.RestorePlaybackStateKey
import com.ljyh.mei.playback.UsbAudioStatus
import com.ljyh.mei.ui.component.player.hasFloatingLyricsBackgroundPermission
import com.ljyh.mei.ui.component.player.isXiaomiFloatingLyricsPermissionRequired
import com.ljyh.mei.ui.component.player.openFloatingLyricsPermissionSettings
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import com.ljyh.mei.ui.component.MeiloXSwitch
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioPopUpButton
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySetting(
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val (audioOutputMode, onAudioOutputModeChange) = rememberEnumPreference(AudioOutputModeKey, AudioOutputMode.AudioTrack)
    val usbBitPerfect = audioOutputMode == AudioOutputMode.UsbBitPerfect
    val usbAudioStatus = playerConnection?.service?.audioOutputStatus?.collectAsState()?.value ?: UsbAudioStatus.Inactive
    val audioOutputModes = remember {
        AudioOutputMode.entries.filter { it != AudioOutputMode.UsbBitPerfect || Build.VERSION.SDK_INT >= 34 }
    }
    val (musicQuality, onMusicQualityChange) = rememberEnumPreference(MusicQualityKey, MusicQuality.EXHIGH)
    val (loopPlayback, onLoopPlaybackChange) = rememberPreference(LoopPlaybackKey, true)
    val (allowConcurrentPlayback, onAllowConcurrentPlaybackChange) = rememberPreference(AllowConcurrentPlaybackKey, false)
    val (cloudShuffle, onCloudShuffleChange) = rememberPreference(CloudShuffleEnabledKey, true)
    val (previousPlayback, onPreviousPlaybackChange) = rememberPreference(PreviousPlaybackKey, true)
    val (restorePlaybackPosition, onRestorePlaybackPositionChange) = rememberPreference(RestorePlaybackPositionKey, true)
    val (restorePlaybackState, onRestorePlaybackStateChange) = rememberPreference(RestorePlaybackStateKey, true)
    val (notificationLeftAction, onNotificationLeftActionChange) = rememberEnumPreference(NotificationLeftActionKey, NotificationAction.PlayMode)
    val (notificationRightAction, onNotificationRightActionChange) = rememberEnumPreference(NotificationRightActionKey, NotificationAction.Like)
    val (noAudioSource, onNoAudioSourceChange) = rememberPreference(NoAudioSourceKey, false)
    val (autoMixEnabled, onAutoMixEnabledChange) = rememberPreference(AutoMixEnabledKey, false)
    val (autoMixMode, onAutoMixModeChange) = rememberEnumPreference(AutoMixModeKey, AutoMixMode.Smart)
    val (storedAutoMixDuration, onAutoMixDurationChange) = rememberPreference(AutoMixDurationKey, 8f)
    val (fadeCurve, onFadeCurveChange) = rememberEnumPreference(AutoMixFadeCurveKey, AutoMixFadeCurve.EqualPower)
    val (tempoMatching, onTempoMatchingChange) = rememberPreference(AutoMixTempoMatchingKey, true)
    val (storedMaximumTempo, onMaximumTempoChange) = rememberPreference(AutoMixMaxTempoAdjustmentKey, 5f)
    val (transitionBars, onTransitionBarsChange) = rememberPreference(AutoMixTransitionBarsKey, 8)
    val (tailCutBars, onTailCutBarsChange) = rememberPreference(AutoMixTailCutBarsKey, 4)
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    var autoMixDurationPreview by remember { mutableStateOf<Float?>(null) }
    var maximumTempoPreview by remember { mutableStateOf<Float?>(null) }
    val autoMixDuration = autoMixDurationPreview ?: storedAutoMixDuration
    val maximumTempo = maximumTempoPreview ?: storedMaximumTempo
    val requiresXiaomiFloatingLyricsPermission = remember { isXiaomiFloatingLyricsPermissionRequired() }
    val floatingLyricsPermissionDescription = stringResource(
        if (requiresXiaomiFloatingLyricsPermission) R.string.notification_floating_lyrics_background_permission_description_xiaomi
        else R.string.notification_floating_lyrics_background_permission_description,
    )
    var floatingLyricsBackgroundAllowed by remember(context) {
        mutableStateOf(hasFloatingLyricsBackgroundPermission(context))
    }
    var showFloatingLyricsPermission by remember { mutableStateOf(false) }
    val checkFloatingLyricsPermission: (NotificationAction) -> Unit = { action ->
        if (action == NotificationAction.FloatingLyrics) {
            floatingLyricsBackgroundAllowed = hasFloatingLyricsBackgroundPermission(context)
            showFloatingLyricsPermission = !floatingLyricsBackgroundAllowed
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        floatingLyricsBackgroundAllowed = hasFloatingLyricsBackgroundPermission(context)
    }

    MeiloXPinnedListPage(
        title = stringResource(R.string.playback_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.playback_behavior)) {
                SettingsCard(
                    modifier = Modifier.fillMaxWidth().alpha(if (usbBitPerfect) 0.38f else 1f),
                    onClick = if (usbBitPerfect) null else { { Screen.EqualizerSettings.navigate(navController) } },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SfIcon("slider.vertical.3", contentDescription = null)
                        Text(stringResource(R.string.equalizer), modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                        SfIcon("chevron.forward", contentDescription = null, tint = LocalGlassColors.current.separator, size = 16.dp)
                    }
                }
                ToggleRow(stringResource(R.string.loop_playback), "arrow.trianglehead.2.clockwise.rotate.90", loopPlayback, onLoopPlaybackChange)
                ToggleRow(
                    stringResource(R.string.allow_concurrent_playback), "waveform",
                    allowConcurrentPlayback, onAllowConcurrentPlaybackChange,
                )
                ToggleRow(
                    stringResource(R.string.cloud_shuffle), "shuffle", cloudShuffle, onCloudShuffleChange,
                    description = stringResource(R.string.cloud_shuffle_description),
                )
                ToggleRow(stringResource(R.string.skip_unavailable), "waveform.slash", noAudioSource, onNoAudioSourceChange)
                ToggleRow(stringResource(R.string.previous_behavior), "backward.fill", previousPlayback, onPreviousPlaybackChange)
                ToggleRow(
                    stringResource(R.string.restore_playback_position), "arrow.counterclockwise",
                    restorePlaybackPosition, onRestorePlaybackPositionChange,
                    description = stringResource(R.string.restore_playback_position_description),
                )
                ToggleRow(
                    stringResource(R.string.restore_playback_state), "play.fill",
                    restorePlaybackState, onRestorePlaybackStateChange,
                    description = stringResource(R.string.restore_playback_state_description),
                )
                ValueRow(stringResource(R.string.music_quality), "waveform") {
                    GlasmioPopUpButton(
                        selected = musicQuality,
                        items = MusicQuality.entries,
                        onSelected = { quality ->
                            if (quality != musicQuality) {
                                if (playerConnection != null) {
                                    playerConnection.changeQuality(quality, preferOnlineSource = false)
                                } else {
                                    onMusicQualityChange(quality)
                                }
                            }
                        },
                        label = { "${it.explanation} · ${it.text}" },
                    )
                }
                ValueRow(
                    title = stringResource(R.string.audio_output),
                    symbol = "speaker.wave.2.fill",
                    description = if (usbBitPerfect) {
                        stringResource(usbAudioStatus.labelResource()) + "\n" + stringResource(R.string.audio_output_usb_description)
                    } else null,
                ) {
                    GlasmioPopUpButton(
                        selected = audioOutputMode,
                        items = audioOutputModes,
                        onSelected = onAudioOutputModeChange,
                        label = { stringResource(it.labelResource()) },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.notification_controls)) {
                ValueRow(stringResource(R.string.notification_left_action), notificationLeftAction.systemName) {
                    GlasmioPopUpButton(
                        selected = notificationLeftAction,
                        items = NotificationAction.entries,
                        onSelected = { action ->
                            onNotificationLeftActionChange(action)
                            checkFloatingLyricsPermission(action)
                        },
                        label = { stringResource(it.labelRes) },
                    )
                }
                ValueRow(stringResource(R.string.notification_right_action), notificationRightAction.systemName) {
                    GlasmioPopUpButton(
                        selected = notificationRightAction,
                        items = NotificationAction.entries,
                        onSelected = { action ->
                            onNotificationRightActionChange(action)
                            checkFloatingLyricsPermission(action)
                        },
                        label = { stringResource(it.labelRes) },
                    )
                }
                if (notificationLeftAction == NotificationAction.FloatingLyrics || notificationRightAction == NotificationAction.FloatingLyrics) {
                    SettingsCard(
                        Modifier.fillMaxWidth(),
                        onClick = { openFloatingLyricsPermissionSettings(context) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            SfIcon("text.rectangle", contentDescription = null)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(stringResource(R.string.notification_floating_lyrics_background_permission))
                                Text(
                                    floatingLyricsPermissionDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(
                                    if (floatingLyricsBackgroundAllowed) R.string.notification_floating_lyrics_permission_allowed
                                    else R.string.notification_floating_lyrics_permission_allow,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.automix)) {
                ToggleRow(
                    stringResource(R.string.automix_enabled), "waveform.path", autoMixEnabled, onAutoMixEnabledChange,
                    enabled = !usbBitPerfect,
                )
            }
        }
        if (autoMixEnabled && !usbBitPerfect) {
            item {
                SettingsGroupedList {
                    GlasmioSegmentedControl(
                        items = listOf(AutoMixMode.Smart to stringResource(R.string.automix_smart), AutoMixMode.Fixed to stringResource(R.string.automix_fixed)),
                        selected = autoMixMode, onSelected = onAutoMixModeChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    SliderRow(
                        title = stringResource(R.string.automix_duration, autoMixDuration.roundToInt()),
                        value = autoMixDuration,
                        onValueChange = { autoMixDurationPreview = it },
                        range = 3f..20f,
                        onValueChangeFinished = {
                            onAutoMixDurationChange(autoMixDurationPreview ?: storedAutoMixDuration)
                        },
                    )
                    GlasmioSegmentedControl(
                        items = listOf(
                            AutoMixFadeCurve.EqualPower to stringResource(R.string.fade_equal_power),
                            AutoMixFadeCurve.Smooth to stringResource(R.string.fade_smooth),
                            AutoMixFadeCurve.Linear to stringResource(R.string.fade_linear),
                        ), selected = fadeCurve, onSelected = onFadeCurveChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    GlasmioSegmentedControl(
                        items = listOf(4, 8, 16).map { it to stringResource(R.string.automix_bars, it) },
                        selected = transitionBars, onSelected = onTransitionBarsChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    GlasmioSegmentedControl(
                        items = listOf(0, 2, 4, 8).map { it to if (it == 0) stringResource(R.string.automix_tail_none) else stringResource(R.string.automix_tail_bars, it) },
                        selected = tailCutBars, onSelected = onTailCutBarsChange,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                    ToggleRow(stringResource(R.string.tempo_matching), "waveform.path", tempoMatching, onTempoMatchingChange)
                    if (tempoMatching) SliderRow(
                        title = stringResource(R.string.maximum_tempo_adjustment, maximumTempo.roundToInt()),
                        value = maximumTempo,
                        onValueChange = { maximumTempoPreview = it },
                        range = 1f..8f,
                        onValueChangeFinished = {
                            onMaximumTempoChange(maximumTempoPreview ?: storedMaximumTempo)
                        },
                    )
                }
            }
        }
    }
    GlasmioAlertDialog(
        visible = showFloatingLyricsPermission,
        onDismissRequest = { showFloatingLyricsPermission = false },
        title = stringResource(R.string.notification_floating_lyrics_background_permission),
        message = floatingLyricsPermissionDescription,
        buttons = listOf(
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.notification_floating_lyrics_permission_later),
                role = GlasmioAlertButtonRole.Cancel,
                onClick = { showFloatingLyricsPermission = false },
            ),
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.pc_qr_login_open_settings),
                onClick = {
                    showFloatingLyricsPermission = false
                    openFloatingLyricsPermissionSettings(context)
                },
            ),
        ),
    )
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
    enabled: Boolean = true,
) {
    SettingsCard(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f),
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
    ) {
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
            MeiloXSwitch(checked, onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun ValueRow(title: String, symbol: String, description: String? = null, value: @Composable () -> Unit) {
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = if (description == null) 0.dp else 14.dp)) {
            Row(
                Modifier.heightIn(min = if (description == null) 52.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SfIcon(symbol, contentDescription = null)
                Text(
                    title,
                    modifier = Modifier.weight(1f)
                        .padding(horizontal = 14.dp, vertical = if (description == null) 14.dp else 0.dp),
                )
                value()
            }
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun AudioOutputMode.labelResource(): Int = when (this) {
    AudioOutputMode.AudioTrack -> R.string.audio_output_audiotrack
    AudioOutputMode.AAudio -> R.string.audio_output_aaudio
    AudioOutputMode.OpenSLES -> R.string.audio_output_opensles
    AudioOutputMode.UsbBitPerfect -> R.string.audio_output_usb
}

private fun UsbAudioStatus.labelResource(): Int = when (this) {
    UsbAudioStatus.Inactive -> R.string.audio_output_usb_inactive
    UsbAudioStatus.WaitingForDevice -> R.string.audio_output_usb_waiting
    UsbAudioStatus.UnsupportedDevice -> R.string.audio_output_usb_unsupported_device
    UsbAudioStatus.UnsupportedFormat -> R.string.audio_output_usb_unsupported_format
    UsbAudioStatus.Active -> R.string.audio_output_usb_active
    UsbAudioStatus.Unavailable -> R.string.audio_output_usb_unavailable
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit,
) {
    SettingsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title)
            GlasmioSlider(
                value,
                onValueChange,
                modifier = Modifier.fillMaxWidth(),
                valueRange = range,
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    }
}
