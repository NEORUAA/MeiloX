package com.ljyh.mei.ui.component.item

import com.ljyh.mei.ui.component.playlist.songListDividerColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.ljyh.mei.constants.songListCoverSize
import com.ljyh.mei.constants.songListCoverCornerRadius
import com.ljyh.mei.ui.screen.playlist.component.DetailTrackDivider
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.ui.component.playlist.FavoriteTrackStar
import com.ljyh.mei.ui.component.playlist.PlayingImageView
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon

/** Shared landscape catalog columns; the album column follows the available viewport. */
@Composable
internal fun LandscapeMediaRow(
    image: String,
    title: String,
    artist: String,
    detail: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    album: String? = null,
    suffix: String? = null,
    suffixColor: Color? = null,
    favorite: Boolean = false,
    showDivider: Boolean = true,
    onMore: ((Rect) -> Unit)? = null,
    navigationArrow: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    landscape: Boolean = rememberDeviceInfo().isLandscape,
) {
    val colors = LocalGlassColors.current
    var anchor by remember(title, image) { mutableStateOf(Rect.Zero) }
    if (!landscape) {
        PortraitMediaRow(
            image = image,
            title = title,
            artist = artist,
            detail = detail,
            onClick = onClick,
            modifier = modifier,
            album = album,
            suffix = suffix,
            suffixColor = suffixColor,
            favorite = favorite,
            showDivider = showDivider,
            onMore = onMore,
            navigationArrow = navigationArrow,
            footer = footer,
        )
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val showAlbum = album != null && maxWidth >= 1000.dp
        Column {
            Box {
                if (favorite) FavoriteTrackStar(
                    Modifier.align(Alignment.CenterStart).offset(x = (-18).dp),
                    tint = colors.secondaryContent,
                )
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayingImageView(imageUrl = image, isPlaying = false,
                        modifier = Modifier.size(songListCoverSize(true)).clip(ContinuousRoundedRectangle(songListCoverCornerRadius(true))))
                    Text(title, Modifier.weight(if (showAlbum) 1f else 1.25f),
                        fontSize = 16.sp, fontWeight = FontWeight.Normal, color = colors.content,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, Modifier.weight(if (showAlbum) 0.85f else 1f),
                        fontSize = 16.sp, color = colors.secondaryContent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (showAlbum) Text(album.orEmpty(), Modifier.weight(1.35f),
                        fontSize = 16.sp, color = colors.secondaryContent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detail, Modifier.widthIn(min = 60.dp), textAlign = TextAlign.End,
                        fontSize = 16.sp, color = colors.secondaryContent, maxLines = 1, softWrap = false)
                    if (!suffix.isNullOrBlank()) Text(suffix, Modifier.widthIn(max = 100.dp),
                        fontSize = 16.sp, color = suffixColor ?: colors.secondaryContent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onMore != null) Box(
                        Modifier.size(44.dp).offset(x = (-8).dp)
                            .onGloballyPositioned { anchor = it.boundsInWindow() }
                            .clip(ContinuousRoundedRectangle(22.dp))
                            .clickable(interactionSource = null, indication = null, onClick = { onMore(anchor) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon("ellipsis", stringResource(R.string.more_actions_title), size = 18.dp, tint = colors.secondaryContent)
                    } else if (navigationArrow) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        SfIcon("chevron.forward", null, size = 12.dp, tint = colors.separator)
                    }
                }
            }
            footer?.invoke()
            if (showDivider) HorizontalDivider(Modifier.padding(start = 60.dp), thickness = 0.5.dp, color = songListDividerColor())
        }
    }
}

@Composable
internal fun PortraitMediaRow(
    image: String,
    title: String,
    artist: String,
    detail: String,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    album: String?,
    suffix: String?,
    suffixColor: Color?,
    favorite: Boolean,
    showDivider: Boolean,
    onMore: ((Rect) -> Unit)?,
    navigationArrow: Boolean,
    footer: (@Composable () -> Unit)?,
) {
    val colors = LocalGlassColors.current
    var anchor by remember(title, image) { mutableStateOf(Rect.Zero) }
    Box(modifier.fillMaxWidth()) {
        Column {
            Box {
                if (favorite) FavoriteTrackStar(
                    Modifier.align(Alignment.CenterStart).offset(x = (-16).dp),
                    tint = colors.secondaryContent,
                )
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayingImageView(
                        imageUrl = image,
                        isPlaying = false,
                        modifier = Modifier.size(songListCoverSize(false)).clip(ContinuousRoundedRectangle(songListCoverCornerRadius(false))),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                            fontWeight = FontWeight.Normal,
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = listOf(artist, detail, suffix.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = colors.secondaryContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onMore != null) Box(
                        Modifier.size(44.dp).offset(x = 8.dp)
                            .onGloballyPositioned { anchor = it.boundsInWindow() }
                            .clip(ContinuousRoundedRectangle(22.dp))
                            .clickable(interactionSource = null, indication = null, onClick = { onMore(anchor) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon("ellipsis", stringResource(R.string.more_actions_title), size = 16.dp, tint = colors.secondaryContent)
                    } else if (navigationArrow) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        SfIcon("chevron.forward", null, size = 12.dp, tint = colors.separator)
                    }
                }
            }
            if (showDivider) DetailTrackDivider()
        }
        footer?.let { progress ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { progress() }
        }
    }
}
