package com.ljyh.mei.ui.screen.playlist.component

import com.ljyh.mei.ui.component.playlist.songListDividerColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.height
import com.ljyh.mei.ui.component.item.PortraitMediaRow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.DetailPageHorizontalPadding
import com.ljyh.mei.constants.songListCoverCornerRadius
import com.ljyh.mei.constants.songListCoverSize
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.supportsNeteaseSongActions
import com.ljyh.mei.ui.component.playlist.PlayingImageView
import com.ljyh.mei.ui.component.playlist.AnimatedSoundWave
import com.ljyh.mei.ui.component.playlist.FavoriteTrackStar
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.utils.TimeUtils.formatDuration
import com.ljyh.mei.utils.smallImage

private val DetailTrackMoreButtonSize = 44.dp
private val DetailTrackMoreIconSize = 16.dp
private val DetailTrackIndicatorWidth = 18.dp
private val DetailTrackFavoriteSize = 12.dp

@Composable
internal fun DetailTrackRow(
    track: MediaMetadata,
    trackNumber: Int,
    showArtwork: Boolean,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    selected: Boolean?,
    onMoreClick: ((Rect) -> Unit)?,
    showDuration: Boolean = false,
) {
    val colors = LocalGlassColors.current
    val compactRows = rememberDeviceInfo().isLandscape
    val artworkSize = songListCoverSize(compactRows)
    val highlightTitle = showArtwork && isCurrentTrack && track.supportsNeteaseSongActions
    if (!compactRows && showArtwork && selected == null) {
        PortraitMediaRow(
            image = track.coverUrl.smallImage(), title = track.title,
            artist = track.artists.joinToString(" / ") { it.name },
            detail = if (showDuration) formatDuration(track.duration) else "",
            onClick = onClick, modifier = Modifier, album = null, suffix = null,
            suffixColor = null, favorite = isFavorite, showDivider = false,
            onMore = onMoreClick, navigationArrow = false, footer = null,
            isCurrentTrack = highlightTitle,
        )
        return
    }

    val numberWidth = if (showArtwork) 0.dp else albumTrackNumberWidth()
    val numberColumnWidth = maxOf(numberWidth, DetailTrackIndicatorWidth)
    val favoriteOffset = -(DetailPageHorizontalPadding + DetailTrackFavoriteSize) / 2 +
        if (compactRows) 0.dp else 2.dp
    val moreButtonOffset = if (compactRows) -8.dp else 8.dp
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxWidth()) {
        if (isFavorite) {
            FavoriteTrackStar(
                modifier = Modifier.align(Alignment.CenterStart).offset(x = favoriteOffset),
                tint = colors.secondaryContent,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (showArtwork) { 56.dp } else 52.dp)
                .then(
                    if (selected == null) Modifier.clickable(onClick = onClick)
                    else Modifier.selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
                )
                .padding(vertical = 4.dp),
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
                        .size(artworkSize)
                        .clip(ContinuousRoundedRectangle(songListCoverCornerRadius(compactRows))),
                )
            } else {
                Box(
                    modifier = Modifier.width(numberColumnWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrentTrack) {
                        Box(
                            modifier = Modifier.size(width = numberColumnWidth, height = 22.dp).detailInkBlend(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isPlaying) {
                                AnimatedSoundWave(
                                    modifier = Modifier.size(width = DetailTrackIndicatorWidth, height = 12.dp),
                                    barCount = 5,
                                    barColor = colors.secondaryContent,
                                    barWidth = 2.dp,
                                    barSpacing = 2.dp,
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(5) {
                                        Box(Modifier.size(2.dp).background(colors.secondaryContent, CircleShape))
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = trackNumber.toString(),
                            modifier = Modifier.fillMaxWidth().detailInkBlend(),
                            color = colors.secondaryContent,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).detailInkBlend(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = track.title,
                    color = if (highlightTitle) colors.accent else colors.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (compactRows) 16.sp else 15.sp,
                        lineHeight = if (compactRows) 22.sp else 20.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = if (showArtwork) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showArtwork) {
                    Text(
                        text = track.artists.joinToString(" / ") { it.name },
                        color = colors.secondaryContent,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (compactRows) 13.sp else 12.sp, lineHeight = if (compactRows) 18.sp else 17.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showDuration) {
                Text(
                    text = formatDuration(track.duration),
                    modifier = Modifier.padding(start = 16.dp).detailInkBlend(),
                    color = colors.secondaryContent,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (compactRows) 16.sp else 17.sp),
                    maxLines = 1, softWrap = false,
                )
            }
            onMoreClick?.let { onMore ->
                Spacer(Modifier.width(if (compactRows && !showArtwork && showDuration) 12.dp else 8.dp))
                Box(
                    modifier = Modifier.width(DetailTrackMoreButtonSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(DetailTrackMoreButtonSize)
                            .offset(x = moreButtonOffset)
                            .onGloballyPositioned { menuAnchor = it.boundsInWindow() }
                            .clip(CircleShape)
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
                            size = if (compactRows && !showArtwork) 18.dp else DetailTrackMoreIconSize,
                        )
                    }
                }
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
    val dividerColor = songListDividerColor()
    val compactRows = rememberDeviceInfo().isLandscape
    val artworkSize = songListCoverSize(compactRows)
    val leadingInset = if (fullWidth) 0.dp else {
        (if (showArtwork) artworkSize else maxOf(albumTrackNumberWidth(), DetailTrackIndicatorWidth)) +
            12.dp +
            (if (selectionMode) 36.dp else 0.dp)
    }
    if (!compactRows && !fullWidth) {
        Box(Modifier.fillMaxWidth().padding(start = leadingInset).height(0.dp).drawBehind {
            drawLine(dividerColor, Offset.Zero, Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
        })
        return
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = leadingInset),
        thickness = 0.5.dp,
        color = dividerColor,
    )
}

@Composable
private fun albumTrackNumberWidth(): Dp {
    val textMeasurer = rememberTextMeasurer(cacheSize = 1)
    val width = textMeasurer.measure(
        text = "999",
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
        maxLines = 1,
        softWrap = false,
    ).size.width
    return with(LocalDensity.current) { width.toDp() }
}

// Preserve the cover background's hue in the ink without tinting row artwork.
private fun Modifier.detailInkBlend(): Modifier = graphicsLayer {
    blendMode = BlendMode.Luminosity
    compositingStrategy = CompositingStrategy.Offscreen
}
