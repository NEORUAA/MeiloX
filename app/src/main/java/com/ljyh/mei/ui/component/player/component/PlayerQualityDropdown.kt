package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.playback.LocalPlaybackInfo
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.SfIcon
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun localPlaybackQualityTitle(
    info: LocalPlaybackInfo?,
    includeSource: Boolean = false,
): String {
    val localTitle = stringResource(R.string.track_quality_local_file)
    val format = when (info?.mimeType?.substringBefore(';')?.lowercase(Locale.ROOT)) {
        "audio/mpeg" -> "MP3"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/mp4" -> "M4A"
        "audio/opus" -> "Opus"
        "audio/vorbis", "audio/ogg" -> "Ogg"
        "audio/alac" -> "ALAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
        "audio/raw" -> "PCM"
        "audio/wav", "audio/x-wav" -> "WAV"
        else -> null
    }
    val bitrate = info?.bitrate?.takeIf { it > 0 }?.let { "${(it / 1_000f).roundToInt()} kbps" }
    val sampleRate = info?.sampleRate?.takeIf { it > 0 }?.let { rate ->
        val khz = rate / 1_000f
        "${if (rate % 1_000 == 0) khz.toInt().toString() else String.format(Locale.ROOT, "%.1f", khz)} kHz"
    }
    val details = listOfNotNull(format, bitrate ?: sampleRate).joinToString(" · ")
    return when {
        details.isEmpty() -> localTitle
        includeSource -> "$localTitle · ${format ?: bitrate ?: sampleRate}"
        else -> details
    }
}

@Composable
fun PlayerQualityDropdown(
    quality: MusicQuality?,
    availableQualities: List<MusicQuality>,
    isLoading: Boolean,
    localPlaybackInfo: LocalPlaybackInfo?,
    isPlayingLocalSource: Boolean,
    onMenuOpened: () -> Unit,
    onQualitySelected: (MusicQuality) -> Unit,
    onLocalSourceSelected: () -> Unit,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White.copy(alpha = 0.15f),
    cornerRadius: Dp = 6.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val pendingTitle = stringResource(R.string.player_quality_pending)
    val hasResolvedQuality = isPlayingLocalSource || quality != null
    val qualityTitle = when {
        isPlayingLocalSource -> localPlaybackQualityTitle(localPlaybackInfo)
        quality != null -> stringResource(quality.labelRes)
        else -> pendingTitle
    }
    val titleAlpha = remember(qualityTitle, hasResolvedQuality) {
        Animatable(if (hasResolvedQuality) 0f else 1f)
    }
    LaunchedEffect(titleAlpha) {
        // Animatable follows the system animator duration scale, including disabled motion.
        if (hasResolvedQuality) titleAlpha.animateTo(1f, tween(160))
    }
    val statusTitle = when {
        isLoading -> stringResource(R.string.track_quality_loading)
        availableQualities.isEmpty() && localPlaybackInfo == null -> stringResource(R.string.track_quality_unavailable)
        else -> null
    }

    IosPopupMenu(
        expanded = expanded,
        onExpandedChange = {
            if (it && !expanded) onMenuOpened()
            expanded = it
        },
        itemCount = availableQualities.size + (if (localPlaybackInfo != null) 1 else 0) +
            (if (statusTitle != null) 1 else 0),
        modifier = modifier.background(backgroundColor, ContinuousRoundedRectangle(cornerRadius)),
        fullScreen = true,
        centerHorizontally = true,
        expandFromAnchor = Alignment.CenterHorizontally,
        anchor = { onClick ->
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                SfIcon(
                    systemName = "waveform",
                    contentDescription = null,
                    tint = color,
                    size = 10.dp,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    text = qualityTitle,
                    style = style,
                    color = color,
                    modifier = Modifier.widthIn(max = 136.dp)
                        .graphicsLayer { alpha = titleAlpha.value },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { childBackdrop, close ->
        localPlaybackInfo?.let { info ->
            IosMenuItem(
                title = localPlaybackQualityTitle(info, includeSource = true),
                onClick = {
                    onLocalSourceSelected()
                    close()
                },
                systemName = if (isPlayingLocalSource) "checkmark" else null,
                backdrop = childBackdrop,
            )
        }
        availableQualities.forEach { option ->
            IosMenuItem(
                title = stringResource(option.labelRes),
                onClick = {
                    onQualitySelected(option)
                    close()
                },
                systemName = if (!isPlayingLocalSource && option == quality) "checkmark" else null,
                backdrop = childBackdrop,
            )
        }
        statusTitle?.let {
            IosMenuItem(
                title = it,
                onClick = {},
                enabled = false,
                backdrop = childBackdrop,
            )
        }
    }
}
