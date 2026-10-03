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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.LyricAutoFollowEnabledKey
import com.ljyh.mei.constants.SystemLyricsEnabledKey
import com.ljyh.mei.constants.LyricGlowEnabledKey
import com.ljyh.mei.constants.LyricLiftEnabledKey
import com.ljyh.mei.constants.LyricLongToneEnabledKey
import com.ljyh.mei.constants.LyricRomanizationEnabledKey
import com.ljyh.mei.constants.LyricTranslationEnabledKey
import com.ljyh.mei.constants.LyricVisualStyle
import com.ljyh.mei.constants.LyricVisualStyleKey
import com.ljyh.mei.constants.FloatingLyricsTranslationKey
import com.ljyh.mei.constants.FloatingLyricsNextLineKey
import com.ljyh.mei.constants.FloatingLyricsFontScaleKey
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassToggle
import com.ljyh.mei.ui.glass.GlassSlider
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosPopupButton
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference

@Composable
fun LyricsSettings() {
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (style, onStyle) = rememberEnumPreference(LyricVisualStyleKey, LyricVisualStyle.AppleMusic)
    val (translation, onTranslation) = rememberPreference(LyricTranslationEnabledKey, true)
    val (romanization, onRomanization) = rememberPreference(LyricRomanizationEnabledKey, true)
    val (glow, onGlow) = rememberPreference(LyricGlowEnabledKey, true)
    val (lift, onLift) = rememberPreference(LyricLiftEnabledKey, true)
    val (longTone, onLongTone) = rememberPreference(LyricLongToneEnabledKey, true)
    val (autoFollow, onAutoFollow) = rememberPreference(LyricAutoFollowEnabledKey, true)
    val (systemLyrics, onSystemLyrics) = rememberPreference(SystemLyricsEnabledKey, true)
    val (floatingTranslation, onFloatingTranslation) = rememberPreference(FloatingLyricsTranslationKey, true)
    val (floatingNext, onFloatingNext) = rememberPreference(FloatingLyricsNextLineKey, true)
    val (floatingScale, onFloatingScale) = rememberPreference(FloatingLyricsFontScaleKey, 1f)

    IosPinnedListPage(
        title = stringResource(R.string.lyrics_settings),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
    ) {
        item {
            SettingsGroup(stringResource(R.string.lyrics_visual_style)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.lyrics_style_presentation),
                            modifier = Modifier.weight(1f),
                        )
                        IosPopupButton(
                            selected = style,
                            items = LyricVisualStyle.entries,
                            onSelected = onStyle,
                            label = { lyricStyleLabel(it) },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.lyrics_content)) {
                LyricsToggleRow(stringResource(R.string.lyrics_translation), translation, onTranslation)
                LyricsToggleRow(stringResource(R.string.lyrics_romanization), romanization, onRomanization)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.lyrics_animation)) {
                LyricsToggleRow(stringResource(R.string.lyrics_glow), glow, onGlow)
                LyricsToggleRow(stringResource(R.string.lyrics_lift), lift, onLift)
                LyricsToggleRow(stringResource(R.string.lyrics_long_tone), longTone, onLongTone)
                LyricsToggleRow(stringResource(R.string.lyrics_auto_follow), autoFollow, onAutoFollow)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.system_lyrics_settings)) {
                LyricsToggleRow(stringResource(R.string.system_lyrics_enabled), systemLyrics, onSystemLyrics)
                Text(
                    stringResource(R.string.system_lyrics_usage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.floating_lyrics_settings)) {
                LyricsToggleRow(stringResource(R.string.floating_lyrics_translation), floatingTranslation, onFloatingTranslation)
                LyricsToggleRow(stringResource(R.string.floating_lyrics_next_line), floatingNext, onFloatingNext)
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.floating_lyrics_size), modifier = Modifier.weight(1f))
                            Text("${(floatingScale * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassSlider(value = floatingScale, onValueChange = onFloatingScale, valueRange = .75f..1.35f)
                    }
                }
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        SfIcon("pip", null, size = 24.dp)
                        Text(stringResource(R.string.floating_lyrics_usage), modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun lyricStyleLabel(style: LyricVisualStyle): String = stringResource(
    when (style) {
        LyricVisualStyle.AppleMusic -> R.string.lyrics_style_apple
        LyricVisualStyle.EVA -> R.string.lyrics_style_eva
        LyricVisualStyle.TextPV -> R.string.lyrics_style_textpv
        LyricVisualStyle.Skyline -> R.string.lyrics_style_skyline
    },
)

@Composable
private fun LyricsToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f))
            GlassToggle(checked, onCheckedChange)
        }
    }
}
