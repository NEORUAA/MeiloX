package com.ljyh.mei.ui.component.player.component.classic.component

import com.ljyh.mei.ui.component.player.PlayerMetadataTrackSwipe
import com.ljyh.mei.data.model.supportsNeteaseSongActions

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.ljyh.mei.ui.component.player.component.rememberPlayerFavoriteMotion
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.player.LocalPlayerRoamingModeLabel
import com.ljyh.mei.ui.component.player.component.applemusic.AppleMusicMarqueeText
import com.ljyh.mei.ui.component.player.component.applemusic.PlayerTitleWithMode
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol

@Composable
fun PlayerHeader(
    modifier: Modifier,
    mediaMetadata: MediaMetadata,
    isLiked: Boolean,
    showLikeButton: Boolean = mediaMetadata.supportsNeteaseSongActions,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    onLikeClick: () -> Unit,
    iconColor: Color = Color.White,
    appleMusicStyle: Boolean = false,
    applyStatusBarPadding: Boolean = true,
    onTextPositioned: (LayoutCoordinates) -> Unit = {},
    onRowPositioned: (LayoutCoordinates) -> Unit = {},
    modeLabel: String? = LocalPlayerRoamingModeLabel.current,
) {
    var metadataWidth by remember { mutableIntStateOf(0) }
    val favoriteMotion = rememberPlayerFavoriteMotion(isLiked, mediaMetadata.id)
    val marqueeEnabled = LocalPlayerSheet.current?.state?.isExpanded != false
    val metadataClick = if (appleMusicStyle) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else Modifier.clickable(onClick = onClick)


    Row(
        modifier = modifier
            .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .fillMaxWidth()
            .then(metadataClick)
            .onGloballyPositioned(onRowPositioned),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：标题和副标题
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    end = (if (appleMusicStyle) 0.dp else 8.dp) +
                        (if (showLikeButton) 0.dp else 2.dp),
                )
                .onGloballyPositioned(onTextPositioned)
                .onSizeChanged { metadataWidth = it.width }
        ) {
            // --- 标题部分 ---
            if (appleMusicStyle) {
                PlayerTitleWithMode(
                    modeLabel, MaterialTheme.typography.titleMedium, Color.White.copy(alpha = 0.7f),
                ) { titleModifier ->
                    PlayerMetadataTrackSwipe(modifier = titleModifier, edgeInset = 22.5.dp, pageWidth = metadataWidth) { track, allowMarquee ->
                        val visibleMetadata = track ?: mediaMetadata
                        AppleMusicMarqueeText(
                            text = visibleMetadata.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                            edgeWidth = 22.5.dp,
                            enabled = marqueeEnabled && allowMarquee,
                        )
                    }
                }
                val subTitle = listOf(
                    mediaMetadata.artists.joinToString(", ") { it.name },
                    mediaMetadata.album.title,
                ).filter { it.isNotEmpty() }.joinToString(" - ")
                if (subTitle.isNotEmpty()) {
                    PlayerMetadataTrackSwipe(edgeInset = 22.5.dp, pageWidth = metadataWidth) { track, allowMarquee ->
                        val visibleMetadata = track ?: mediaMetadata
                        AppleMusicMarqueeText(
                            text = listOf(visibleMetadata.artists.joinToString(", ") { it.name },
                                visibleMetadata.album.title).filter { it.isNotEmpty() }.joinToString(" - "),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            edgeWidth = 22.5.dp,
                            enabled = marqueeEnabled && allowMarquee,
                        )
                    }
                }
            } else {
                PlayerTitleWithMode(
                    modeLabel, MaterialTheme.typography.titleMedium, Color.White.copy(alpha = 0.7f),
                ) { titleModifier ->
                    PlayerMetadataTrackSwipe(modifier = titleModifier, pageWidth = metadataWidth) { track, _ ->
                        val visibleMetadata = track ?: mediaMetadata
                        Text(
                            text = visibleMetadata.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                            color = Color.White,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                PlayerMetadataTrackSwipe(pageWidth = metadataWidth) { track, allowMarquee ->
                    val visibleMetadata = track ?: mediaMetadata
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .basicMarquee(iterations = if (allowMarquee) Int.MAX_VALUE else 0)
                    ) {
                        val subTitleStyle = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White.copy(alpha = 0.7f)
                        )

                        if (visibleMetadata.artists.isNotEmpty()) {
                            Text(
                                text = visibleMetadata.artists.joinToString(", ") { it.name },
                                style = subTitleStyle,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clip(ContinuousRoundedRectangle(4.dp))
                            )
                        }

                        if (visibleMetadata.artists.isNotEmpty() && visibleMetadata.album.title.isNotEmpty()) {
                            Text(
                                text = " - ",
                                style = subTitleStyle,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }

                        if (visibleMetadata.album.title.isNotEmpty()) {
                            Text(
                                text = visibleMetadata.album.title,
                                style = subTitleStyle,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clip(ContinuousRoundedRectangle(4.dp))
                            )
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = if (appleMusicStyle) 0.dp else 5.dp, end = 8.dp)
        ) {
            if (showLikeButton) {
                IconButton(
                    onClick = { favoriteMotion.click(isLiked, onLikeClick) },
                    modifier = Modifier
                        .size(34.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(34.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon(
                            modifier = Modifier.graphicsLayer {
                                scaleX = favoriteMotion.scale.value
                                scaleY = favoriteMotion.scale.value
                            },
                            symbol = if (isLiked) SfSymbol.StarFilled else SfSymbol.Star,
                            contentDescription = stringResource(R.string.app_tab_library_songs),
                            tint = iconColor.copy(alpha = 0.8f),
                            size = 22.dp,
                            weight = FontWeight.Bold
                        )
                    }
                }
            }
            // 右侧：更多按钮
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier
                    .size(34.dp),
            ) {
                Box(
                    modifier = Modifier
                        .requiredSize(34.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    SfIcon(
                        symbol = SfSymbol.Ellipsis,
                        contentDescription = stringResource(R.string.more_actions_title),
                        tint = iconColor.copy(alpha = 0.8f),
                        size = 22.dp,
                    )
                }
            }
        }
    }
}
