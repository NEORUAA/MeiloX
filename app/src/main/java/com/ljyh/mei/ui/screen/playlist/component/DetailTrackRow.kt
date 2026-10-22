package com.ljyh.mei.ui.screen.playlist.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.CommonImageRadius
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.playlist.PlayingImageView
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.utils.smallImage

@Composable
internal fun DetailTrackRow(
    track: MediaMetadata,
    trackNumber: Int,
    showArtwork: Boolean,
    isCurrentTrack: Boolean,
    onClick: () -> Unit,
    selected: Boolean?,
    onMoreClick: ((Rect) -> Unit)?,
) {
    val colors = LocalGlassColors.current
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (showArtwork) 64.dp else 52.dp)
            .then(
                if (selected == null) Modifier.clickable(onClick = onClick)
                else Modifier.selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            )
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            SfIcon(
                if (selected) "checkmark.circle.fill" else "circle",
                contentDescription = null,
                modifier = Modifier.detailInkBlend(),
                size = 24.dp,
                tint = colors.content,
            )
            Spacer(Modifier.width(12.dp))
        }
        if (showArtwork) {
            PlayingImageView(
                imageUrl = track.coverUrl.smallImage(),
                isPlaying = false,
                modifier = Modifier
                    .size(48.dp)
                    .clip(ContinuousRoundedRectangle(CommonImageRadius)),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Box(
                modifier = Modifier.align(Alignment.Top).width(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrentTrack) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-10).dp)
                            .size(4.dp)
                            .detailInkBlend()
                            .background(colors.secondaryContent, CircleShape)
                    )
                }
                Text(
                    text = trackNumber.toString(),
                    modifier = Modifier.fillMaxWidth().detailInkBlend(),
                    color = colors.secondaryContent,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    textAlign = TextAlign.Start,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f)
                .then(if (showArtwork) Modifier else Modifier.align(Alignment.Top))
                .detailInkBlend(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track.title,
                color = colors.content,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                ),
                maxLines = if (showArtwork) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArtwork) {
                Text(
                    text = track.artists.joinToString(" / ") { it.name },
                    color = colors.secondaryContent,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        onMoreClick?.let { onMore ->
            Box(
                modifier = Modifier
                    .then(if (showArtwork) Modifier else Modifier.align(Alignment.Top))
                    .padding(start = 8.dp)
                    .size(width = 44.dp, height = if (showArtwork) 44.dp else 22.dp)
                    .onGloballyPositioned { menuAnchor = it.boundsInWindow() }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.more_actions_title),
                        onClick = { onMore(menuAnchor) },
                    )
                    .detailInkBlend(),
                contentAlignment = Alignment.Center,
            ) {
                SfIcon(
                    symbol = SfSymbol.Ellipsis,
                    contentDescription = stringResource(R.string.more_actions_title),
                    tint = colors.secondaryContent,
                    size = 16.dp,
                )
            }
        }
    }
}

@Composable
internal fun DetailTrackDivider(
    showArtwork: Boolean = true,
    selectionMode: Boolean = false,
    fullWidth: Boolean = false,
) {
    val colors = LocalGlassColors.current
    val leadingInset = if (fullWidth) 12.dp else {
        12.dp + (if (showArtwork) 60.dp else 28.dp) + (if (selectionMode) 36.dp else 0.dp)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = leadingInset, end = 12.dp).detailInkBlend(),
        thickness = 0.5.dp,
        color = colors.content.copy(alpha = 0.16f),
    )
}

// Preserve the cover background's hue in the ink without tinting row artwork.
private fun Modifier.detailInkBlend(): Modifier = graphicsLayer {
    blendMode = BlendMode.Luminosity
    compositingStrategy = CompositingStrategy.Offscreen
}
