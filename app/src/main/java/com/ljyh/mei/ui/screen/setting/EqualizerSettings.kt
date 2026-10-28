package com.ljyh.mei.ui.screen.setting

import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.EqualizerBandGainsKey
import com.ljyh.mei.constants.EqualizerEnabledKey
import com.ljyh.mei.constants.EqualizerPreampKey
import com.ljyh.mei.constants.EqualizerPresetKey
import com.ljyh.mei.playback.EqualizerBand
import com.ljyh.mei.playback.EqualizerPreset
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioSlider
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.utils.rememberEnumPreference
import com.ljyh.mei.utils.rememberPreference
import java.util.Locale

@Composable
fun EqualizerSettings() {
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val (enabled, setEnabled) = rememberPreference(EqualizerEnabledKey, false)
    val (preset, setPreset) = rememberEnumPreference(EqualizerPresetKey, EqualizerPreset.Flat)
    val (preamp, setPreamp) = rememberPreference(EqualizerPreampKey, preset.preamp)
    val (serializedGains, setSerializedGains) = rememberPreference(
        EqualizerBandGainsKey,
        EqualizerPreset.Flat.gains.joinToString(","),
    )
    val gains = serializedGains.split(',')
        .mapNotNull(String::toFloatOrNull)
        .takeIf { it.size == EqualizerBand.entries.size }
        ?: EqualizerPreset.Flat.gains
    // Follow loaded preferences until edited, then let this screen own its live preview.
    var previewPreset by remember { mutableStateOf<EqualizerPreset?>(null) }
    var previewPreamp by remember { mutableStateOf<Float?>(null) }
    var previewGains by remember { mutableStateOf<List<Float>?>(null) }

    fun applyPreset(next: EqualizerPreset) {
        previewPreset = next
        setPreset(next)
        if (next != EqualizerPreset.Custom) {
            previewPreamp = next.preamp
            previewGains = next.gains
            setPreamp(next.preamp)
            setSerializedGains(next.gains.joinToString(","))
        }
    }

    fun updateGain(index: Int, value: Float) {
        val updated = (previewGains ?: gains).toMutableList().apply {
            this[index] = value.coerceIn(-12f, 12f)
        }
        previewPreset = EqualizerPreset.Custom
        previewGains = updated
        setPreset(EqualizerPreset.Custom)
        setSerializedGains(updated.joinToString(","))
    }

    MeiloXPinnedListPage(
        title = stringResource(R.string.equalizer),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            listOf(meiloxHeaderAction(
                key = "equalizer.reset",
                contentDescription = stringResource(R.string.equalizer_reset),
                onClick = { applyPreset(EqualizerPreset.Flat) },
            ) { Text(stringResource(R.string.equalizer_reset)) })
        },
    ) {
        item {
            GlasmioCard(Modifier.fillMaxWidth(), onClick = { setEnabled(!enabled) }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SfIcon("slider.vertical.3", null)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(stringResource(R.string.equalizer_enabled))
                        Text(
                            stringResource(R.string.equalizer_processing_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GlasmioSwitch(enabled, setEnabled)
                }
            }
        }
        item {
            Text(
                stringResource(R.string.equalizer_preset),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EqualizerPreset.entries, key = EqualizerPreset::name) { item ->
                    GlasmioButton(
                        onClick = { applyPreset(item) },
                        enabled = enabled,
                        emphasis = if (item == (previewPreset ?: preset)) GlassEmphasis.Prominent else GlassEmphasis.Regular,
                    ) {
                        Text(stringResource(item.labelResource()))
                    }
                }
            }
        }
        item(key = "equalizer-bands") {
            GlasmioGroupedList {
                EqualizerSliderCard(
                    title = stringResource(R.string.equalizer_preamp), value = previewPreamp ?: preamp, enabled = enabled,
                    onValueChange = {
                        previewPreset = EqualizerPreset.Custom
                        previewPreamp = it
                        setPreset(EqualizerPreset.Custom)
                        setPreamp(it)
                    },
                    valueRange = -12f..6f,
                )
                EqualizerBand.entries.forEach { band ->
                    val index = band.ordinal
                    EqualizerSliderCard(
                        title = stringResource(band.labelResource()), value = (previewGains ?: gains)[index], enabled = enabled,
                        onValueChange = { updateGain(index, it) }, valueRange = -12f..12f,
                    )
                }
            }
        }
    }
}

@Composable
private fun EqualizerSliderCard(
    title: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    GlasmioCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                Text(
                    String.format(Locale.getDefault(), "%+.1f dB", value),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlasmioSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                valueRange = valueRange,
                enabled = enabled,
            )
        }
    }
}

@StringRes
private fun EqualizerPreset.labelResource(): Int = when (this) {
    EqualizerPreset.Flat -> R.string.equalizer_flat
    EqualizerPreset.Acoustic -> R.string.equalizer_acoustic
    EqualizerPreset.BassBoost -> R.string.equalizer_bass_boost
    EqualizerPreset.Classical -> R.string.equalizer_classical
    EqualizerPreset.Dance -> R.string.equalizer_dance
    EqualizerPreset.Electronic -> R.string.equalizer_electronic
    EqualizerPreset.HipHop -> R.string.equalizer_hip_hop
    EqualizerPreset.Jazz -> R.string.equalizer_jazz
    EqualizerPreset.Pop -> R.string.equalizer_pop
    EqualizerPreset.Rock -> R.string.equalizer_rock
    EqualizerPreset.SpokenWord -> R.string.equalizer_spoken_word
    EqualizerPreset.TrebleBoost -> R.string.equalizer_treble_boost
    EqualizerPreset.Vocal -> R.string.equalizer_vocal
    EqualizerPreset.Custom -> R.string.equalizer_custom
}

@StringRes
private fun EqualizerBand.labelResource(): Int = when (this) {
    EqualizerBand.Hz31 -> R.string.equalizer_band_31
    EqualizerBand.Hz62 -> R.string.equalizer_band_62
    EqualizerBand.Hz125 -> R.string.equalizer_band_125
    EqualizerBand.Hz250 -> R.string.equalizer_band_250
    EqualizerBand.Hz500 -> R.string.equalizer_band_500
    EqualizerBand.Khz1 -> R.string.equalizer_band_1k
    EqualizerBand.Khz2 -> R.string.equalizer_band_2k
    EqualizerBand.Khz4 -> R.string.equalizer_band_4k
    EqualizerBand.Khz8 -> R.string.equalizer_band_8k
    EqualizerBand.Khz16 -> R.string.equalizer_band_16k
}
