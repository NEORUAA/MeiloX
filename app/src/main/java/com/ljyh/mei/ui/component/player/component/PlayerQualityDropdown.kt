package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.SfIcon

@Composable
fun PlayerQualityDropdown(
    quality: MusicQuality?,
    availableQualities: List<MusicQuality>,
    isLoading: Boolean,
    onMenuOpened: () -> Unit,
    onQualitySelected: (MusicQuality) -> Unit,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val statusTitle = when {
        isLoading -> stringResource(R.string.track_quality_loading)
        availableQualities.isEmpty() -> stringResource(R.string.track_quality_unavailable)
        else -> null
    }

    IosPopupMenu(
        expanded = expanded,
        onExpandedChange = {
            if (it && !expanded) onMenuOpened()
            expanded = it
        },
        itemCount = availableQualities.size + if (statusTitle != null) 1 else 0,
        modifier = modifier.background(Color.White.copy(alpha = 0.15f), ContinuousRoundedRectangle(6.dp)),
        anchor = { onClick ->
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SfIcon(
                    systemName = "waveform",
                    contentDescription = null,
                    tint = color,
                    size = 10.dp,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    text = stringResource(quality?.labelRes ?: R.string.music_quality),
                    style = style,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { childBackdrop, close ->
        availableQualities.forEach { option ->
            IosMenuItem(
                title = stringResource(option.labelRes),
                onClick = {
                    onQualitySelected(option)
                    close()
                },
                systemName = if (option == quality) "checkmark" else null,
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
