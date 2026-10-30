package com.ljyh.mei.ui.component.item

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import com.ljyh.mei.constants.HomeSongActionSize
import com.ljyh.mei.constants.HomeSongCoverCornerRadius
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
    coverSize: Dp = HomeSongCoverSize,
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
                        color = LocalGlassColors.current.separator,
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
    coverSize: Dp = HomeSongCoverSize,
    itemContent: @Composable (Int) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(columnSpacing),
    ) {
        items((itemCount + 2) / 3) { column ->
            HomeSongColumn(minOf(3, itemCount - column * 3), Modifier.width(columnWidth), coverSize) { row ->
                itemContent(column * 3 + row)
            }
        }
    }
}

@Composable
internal fun HomeSongListRow(
    coverUrl: String,
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    onClick: (() -> Unit)? = null,
    coverSize: Dp = HomeSongCoverSize,
    action: @Composable () -> Unit,
) {
    val configuration = LocalViewConfiguration.current
    val actionConfiguration =
        remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize(HomeSongActionSize, HomeSongActionSize)
            }
        }
    Row(
        Modifier.fillMaxWidth()
            .clip(ContinuousRoundedRectangle(HomeSongCoverCornerRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayingImageView(
            imageUrl = coverUrl,
            isPlaying = isPlaying,
            modifier =
                Modifier.size(coverSize)
                    .clip(ContinuousRoundedRectangle(HomeSongCoverCornerRadius))
                    .homeArtworkBorder(ContinuousRoundedRectangle(HomeSongCoverCornerRadius)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Normal,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides HomeSongActionSize,
            LocalViewConfiguration provides actionConfiguration,
        ) {
            action()
        }
    }
}
