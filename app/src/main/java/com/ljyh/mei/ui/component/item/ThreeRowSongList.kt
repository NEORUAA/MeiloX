package com.ljyh.mei.ui.component.item

import com.ljyh.mei.ui.component.playlist.songListDividerColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.icons.SfIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.songListCoverSize
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.constants.HomeSongActionSize
import com.ljyh.mei.constants.songListCoverCornerRadius
import com.ljyh.mei.constants.HomeSongCoverSize
import com.ljyh.mei.constants.HomeSongRowSpacing
import com.ljyh.mei.ui.component.home.homeArtworkBorder
import com.ljyh.mei.ui.component.playlist.PlayingImageView
import io.github.neoruaa.glasmio.core.LocalGlassColors

internal val ThreeRowSongListHeight: Dp
    get() = HomeSongCoverSize * 3 + HomeSongRowSpacing * 2

/** The home feed and artist catalog share row dimensions and separator placement. */
@Composable
internal fun HomeSongColumn(
    itemCount: Int,
    modifier: Modifier = Modifier,
    coverSize: Dp = songListCoverSize(rememberDeviceInfo().isLandscape),
    itemContent: @Composable (Int) -> Unit,
) {
    Column(modifier) {
        repeat(itemCount) { index ->
            itemContent(index)
            if (index < itemCount - 1) {
                Box(Modifier.fillMaxWidth().height(HomeSongRowSpacing)) {
                    HorizontalDivider(
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(start = coverSize + 12.dp),
                        thickness = 0.5.dp,
                        color = songListDividerColor(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ThreeRowSongList(
    itemCount: Int,
    columnWidth: Dp,
    columnSpacing: Dp,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    coverSize: Dp = songListCoverSize(rememberDeviceInfo().isLandscape),
    itemContent: @Composable (Int) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { (itemCount + 2) / 3 })
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxWidth(),
        pageSize = PageSize.Fixed(columnWidth),
        contentPadding = contentPadding,
        pageSpacing = columnSpacing,
        verticalAlignment = Alignment.Top,
    ) { column ->
        HomeSongColumn(minOf(3, itemCount - column * 3), Modifier.width(columnWidth), coverSize) { row ->
            itemContent(column * 3 + row)
        }
    }
}

@Composable
internal fun HomeSongListRow(
    coverUrl: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onMore: (Rect) -> Unit,
    moreModifier: Modifier = Modifier,
    coverSize: Dp = songListCoverSize(rememberDeviceInfo().isLandscape),
) {
    var anchor by remember(coverUrl, title) { mutableStateOf(Rect.Zero) }
    val configuration = LocalViewConfiguration.current
    val actionConfiguration =
        remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize(HomeSongActionSize, HomeSongActionSize)
            }
        }
    Row(
        Modifier.fillMaxWidth()
            .clip(ContinuousRoundedRectangle(songListCoverCornerRadius(rememberDeviceInfo().isLandscape)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayingImageView(
            imageUrl = coverUrl,
            isPlaying = false,
            modifier =
                Modifier.size(coverSize)
                    .clip(ContinuousRoundedRectangle(songListCoverCornerRadius(rememberDeviceInfo().isLandscape)))
                    .homeArtworkBorder(ContinuousRoundedRectangle(songListCoverCornerRadius(rememberDeviceInfo().isLandscape))),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (rememberDeviceInfo().isLandscape) 16.sp else 14.sp,
                lineHeight = if (rememberDeviceInfo().isLandscape) androidx.compose.ui.unit.TextUnit.Unspecified else 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Normal,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (rememberDeviceInfo().isLandscape) 13.sp else 12.sp,
                lineHeight = if (rememberDeviceInfo().isLandscape) androidx.compose.ui.unit.TextUnit.Unspecified else 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides HomeSongActionSize,
            LocalViewConfiguration provides actionConfiguration,
        ) {
            IconButton(
                onClick = { onMore(anchor) },
                modifier = moreModifier.size(HomeSongActionSize).offset(x = (-6).dp)
                    .onGloballyPositioned { anchor = it.boundsInWindow() },
            ) {
                SfIcon("ellipsis", stringResource(R.string.more_actions_title), size = 18.dp,
                    tint = LocalGlassColors.current.secondaryContent)
            }
        }
    }
}
