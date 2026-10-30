package com.ljyh.mei.ui.screen.playlist.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import com.ljyh.mei.constants.PlaylistCoverStyle
import com.ljyh.mei.constants.PlaylistCoverStyleKey
import com.ljyh.mei.ui.component.playlist.FinalPerfectCollage
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.utils.rememberEnumPreference
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGroupedListBackgroundAlpha
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

/**
 * iOS-style playlist hero. The cover and actions intentionally stay self-contained so the playlist
 * page can be hosted by [MeiloXPinnedListPage] without a second app bar or card system.
 */
@Composable
fun PlaylistHeader(
    title: String,
    count: Int,
    playCount: Long,
    subscribeCount: Long,
    cover: String,
    coverList: List<String>,
    creator: String,
    isSubscribed: Boolean,
    onPlayAll: () -> Unit,
    onSubscribed: (Boolean) -> Unit,
    onDownload: (() -> Unit)? = null,
    actionIcon: ImageVector,
    actionLabel: String,
    metadata: String? = null,
    onShufflePlay: () -> Unit = onPlayAll,
    isSubscriptionPending: Boolean = false,
    detailAction: Boolean = false,
    horizontal: Boolean = false,
    isLoading: Boolean = false,
    allowActionScroll: Boolean = false,
) {
    val colors = LocalGlassColors.current
    val actionHeight = if (horizontal) 54.dp else 44.dp
    val playWidth = if (horizontal) 180.dp else 148.dp
    val groupedListBackgroundAlpha = LocalGroupedListBackgroundAlpha.current.coerceIn(0f, 1f)
    val useGroupedListButtonBackground = groupedListBackgroundAlpha < 1f
    val regularButtonBackground =
        colors.elevatedBackground.copy(alpha = groupedListBackgroundAlpha).takeIf {
            useGroupedListButtonBackground && !detailAction
        }
    val playlistCoverStyle by
        rememberEnumPreference(PlaylistCoverStyleKey, defaultValue = PlaylistCoverStyle.Cover)

    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (detailAction) 0.dp else 4.dp)
            .padding(top = 16.dp, bottom = if (horizontal) 40.dp else 16.dp)
    ) {
        val coverSize = minOf(maxWidth * 0.28f, 340.dp)
        val coverContent: @Composable () -> Unit = {
            PlaylistCover(
                style = playlistCoverStyle,
                cover = cover,
                coverList = coverList,
                size = if (horizontal) coverSize else null,
                isLoading = isLoading,
            )
        }
        val information: @Composable () -> Unit = {
            Column(
                modifier =
                    (if (horizontal) Modifier.heightIn(min = coverSize) else Modifier).testTag(
                        "DetailHeaderInformation"
                    ),
                horizontalAlignment =
                    if (horizontal) Alignment.Start else Alignment.CenterHorizontally,
                verticalArrangement = if (horizontal) Arrangement.SpaceBetween else Arrangement.Top,
            ) {
                Column(
                    modifier = Modifier.padding(top = if (horizontal) coverSize * 0.30f else 0.dp),
                    horizontalAlignment =
                        if (horizontal) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (isLoading) title.ifBlank { "MMMMMMMMMMMM" } else title,
                        style = if (horizontal) IosTypography.title2.copy(fontSize = 24.sp, lineHeight = 29.sp) else IosTypography.title2,
                        color = colors.content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (horizontal) TextAlign.Start else TextAlign.Center,
                        modifier =
                            Modifier.padding(top = if (horizontal) 0.dp else 22.dp)
                                .padding(horizontal = if (detailAction) 0.dp else 20.dp)
                                .headerPlaceholder(isLoading),
                    )
                    if (creator.isNotBlank() || isLoading) {
                        Text(
                            text = if (isLoading) creator.ifBlank { "MMMMMMMM" } else creator,
                            style =
                                IosTypography.title2.copy(
                                    fontWeight = FontWeight.Normal,
                                    fontSize = if (horizontal) 24.sp else IosTypography.subheadline.fontSize,
                                    lineHeight = if (horizontal) 29.sp else IosTypography.subheadline.lineHeight,
                                ),
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp).headerPlaceholder(isLoading),
                        )
                    }

                    Text(
                        text =
                            if (isLoading) "MMMMMMMMMMMMMM"
                            else
                                metadata ?: buildPlaylistMetadata(count, playCount, subscribeCount),
                        style = IosTypography.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = colors.secondaryContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp).headerPlaceholder(isLoading),
                    )
                }

                Row(
                    modifier =
                        Modifier.padding(top = 18.dp)
                            .testTag("DetailHeaderActions")
                            .then(
                                if (horizontal || allowActionScroll) Modifier.horizontalScroll(rememberScrollState())
                                else Modifier
                            ),
                    horizontalArrangement = Arrangement.spacedBy(if (horizontal) 16.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLoading) {
                        listOf(actionHeight, playWidth, actionHeight).forEach { width ->
                            Spacer(
                                Modifier.size(width = width, height = actionHeight)
                                    .clip(Capsule())
                                    .background(colors.content.copy(alpha = 0.15f))
                            )
                        }
                    } else if (detailAction) {
                        FlatDetailIconButton(
                            systemName = "shuffle",
                            label = stringResource(R.string.notification_mode_shuffle),
                            onClick = onShufflePlay,
                            enabled = count > 0,
                            size = actionHeight,
                        )
                        Box(
                            modifier =
                                Modifier.size(width = playWidth, height = actionHeight)
                                    .graphicsLayer {
                                        // Isolate the cutout from the cover-derived page
                                        // background.
                                        compositingStrategy = CompositingStrategy.Offscreen
                                        alpha = if (count > 0) 1f else 0.38f
                                    }
                                    .clip(Capsule())
                                    .background(colors.content)
                                    .clickable(
                                        enabled = count > 0,
                                        role = Role.Button,
                                        onClick = onPlayAll,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier =
                                    Modifier.graphicsLayer {
                                        blendMode = BlendMode.DstOut
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    },
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SfIcon("play.fill", null, tint = Color.White, size = if (horizontal) 18.dp else 16.dp)
                                Text(
                                    text = stringResource(R.string.pip_play),
                                    style = if (horizontal) IosTypography.headline.copy(fontSize = 20.sp, lineHeight = 24.sp) else IosTypography.headline,
                                    color = Color.White,
                                )
                            }
                        }
                        FlatDetailIconButton(
                            systemName = if (isSubscribed) "checkmark" else "plus",
                            label = actionLabel,
                            onClick = { onSubscribed(isSubscribed) },
                            enabled = !isSubscriptionPending,
                            pending = isSubscriptionPending,
                            size = actionHeight,
                        )
                    } else {
                        GlasmioIconButton(
                            onClick = onShufflePlay,
                            enabled = count > 0,
                            style = GlassSurfaceStyle.Navigation,
                            detailAction = detailAction,
                            navigationSurfaceColor = regularButtonBackground,
                            navigationSurfaceAlphaMultiplier =
                                if (useGroupedListButtonBackground && !detailAction) 1f else 1.25f,
                            sampleBackdrop = detailAction || !useGroupedListButtonBackground,
                        ) {
                            SfIcon("shuffle", null, size = 24.dp, weight = FontWeight.SemiBold)
                        }
                        GlasmioButton(
                            onClick = onPlayAll,
                            style = GlassSurfaceStyle.Navigation,
                            detailAction = detailAction,
                            enabled = count > 0,
                            emphasis = GlassEmphasis.Prominent,
                        ) {
                            SfIcon("play.fill", null, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Text(
                                stringResource(R.string.pip_play),
                                style = if (horizontal) IosTypography.headline.copy(fontSize = 20.sp, lineHeight = 24.sp) else IosTypography.headline,
                            )
                        }
                        GlasmioIconButton(
                            onClick = { onSubscribed(isSubscribed) },
                            enabled = !isSubscriptionPending,
                            style = GlassSurfaceStyle.Navigation,
                            detailAction = detailAction,
                            navigationSurfaceColor =
                                regularButtonBackground.takeUnless { isSubscribed },
                            navigationSurfaceAlphaMultiplier =
                                if (
                                    useGroupedListButtonBackground && !detailAction && !isSubscribed
                                )
                                    1f
                                else 1.25f,
                            emphasis =
                                if (isSubscribed) GlassEmphasis.Prominent
                                else GlassEmphasis.Regular,
                            sampleBackdrop =
                                detailAction || !useGroupedListButtonBackground || isSubscribed,
                        ) {
                            if (isSubscriptionPending) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(24.dp).semantics {
                                            contentDescription = actionLabel
                                        },
                                    color = LocalContentColor.current,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                SfIcon(
                                    if (isSubscribed) "checkmark" else "plus",
                                    actionLabel,
                                    size = 24.dp,
                                    weight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    onDownload
                        ?.takeUnless { isLoading }
                        ?.let { download ->
                            GlasmioIconButton(onClick = download) {
                                SfIcon(
                                    "arrow.down.circle",
                                    stringResource(R.string.track_action_download),
                                    size = 22.dp,
                                )
                            }
                        }
                }
            }
        }
        if (horizontal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                coverContent()
                Box(Modifier.weight(1f)) { information() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                coverContent()
                information()
            }
        }
    }
}

private fun Modifier.headerPlaceholder(loading: Boolean): Modifier =
    if (!loading) this
    else
        clearAndSetSemantics {}
            .drawWithContent {
                drawRoundRect(
                    Color.Gray.copy(alpha = 0.25f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }

@Composable
private fun FlatDetailIconButton(
    systemName: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    pending: Boolean = false,
    size: Dp = 44.dp,
) {
    val colors = LocalGlassColors.current
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                blendMode = BlendMode.Luminosity
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = if (enabled || pending) 1f else 0.38f
            }
            .clip(Capsule())
            .background(colors.content.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp).semantics { contentDescription = label },
                color = colors.content,
                strokeWidth = 2.dp,
            )
        } else {
            SfIcon(
                systemName = systemName,
                contentDescription = label,
                tint = colors.content,
                size = 23.dp,
                weight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PlaylistCover(
    style: PlaylistCoverStyle,
    cover: String,
    coverList: List<String>,
    size: Dp? = null,
    isLoading: Boolean = false,
) {
    val shape = ContinuousRoundedRectangle(14.dp)
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier.testTag("DetailHeaderCover")
                .then(
                    if (size != null) Modifier.width(size)
                    else Modifier.fillMaxWidth(0.68f).widthIn(max = 300.dp)
                )
                .aspectRatio(1f)
                .dropShadow(
                    shape = shape,
                    shadow =
                        Shadow(
                            radius = 18.dp,
                            color = Color.Black.copy(alpha = 0.16f),
                            offset = DpOffset.Zero,
                        ),
                )
                .clip(shape)
    ) {
        if (isLoading) {
            Box(
                Modifier.fillMaxSize()
                    .background(LocalGlassColors.current.content.copy(alpha = 0.15f))
            )
        } else
            when (style) {
                PlaylistCoverStyle.Cover ->
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )

                PlaylistCoverStyle.FirstSongImage ->
                    AsyncImage(
                        model = coverList.firstOrNull() ?: cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )

                PlaylistCoverStyle.Combination -> {
                    if (coverList.size < 5) {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        FinalPerfectCollage(
                            imageUrls = coverList,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
    }
}

@Composable
private fun buildPlaylistMetadata(
    count: Int,
    playCount: Long,
    subscribeCount: Long,
): String {
    val songs = stringResource(R.string.playlist_song_count, count)
    val played = if (playCount >= 0) {
        stringResource(R.string.song_wiki_play_count, formatCompactCount(playCount))
    } else {
        null
    }
    return listOfNotNull(songs, played).joinToString(" · ")
}

private fun formatCompactCount(value: Long): String = value.toString()
