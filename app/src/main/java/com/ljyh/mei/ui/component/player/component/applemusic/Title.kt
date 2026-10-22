package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol

@Composable
fun Title(
    title: String,
    subTitle: String,
    isLiked: Boolean,
    onLikeClick: () -> Unit,
    onMoreClick: () -> Unit,
    onTitleClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    subTitleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    needShadow: Boolean = true,
    iconColor: Color = Color.White, // 新增：控制图标颜色，方便在不同背景下调整
    onMoreButtonPositioned: (LayoutCoordinates) -> Unit = {},
    enabled: Boolean = true,
    textModifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    onTextSizeChanged: (IntSize) -> Unit = {},
    onTextPositioned: (LayoutCoordinates) -> Unit = {},
    onRowPositioned: (LayoutCoordinates) -> Unit = {},
    onActionsSizeChanged: (IntSize) -> Unit = {},
    marqueeEdgeWidth: Dp = AppleMusicDimensions.albumMarqueeEdgeWidth,
    marqueeEnabled: Boolean = true,
    showLikeButton: Boolean = true,
) {
    val shadowStyle = if (needShadow) Shadow(
        color = Color.Black.copy(alpha = 0.3f),
        offset = Offset(2f, 2f),
        blurRadius = 12f
    ) else null

    Row(
        modifier = modifier.onGloballyPositioned(onRowPositioned),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f)
                .padding(end = if (showLikeButton) 0.dp else 2.dp)
                .onSizeChanged(onTextSizeChanged)
                .then(textModifier)
                .onGloballyPositioned(onTextPositioned)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onTitleClick,
                ),
            horizontalAlignment = Alignment.Start
        ) {
            AppleMusicMarqueeText(
                text = title,
                style = titleStyle.copy(
                    shadow = shadowStyle,
                    fontWeight = titleStyle.fontWeight ?: FontWeight.Medium,
                ),
                color = Color.White,
                edgeWidth = marqueeEdgeWidth,
                enabled = marqueeEnabled,
            )
            AppleMusicMarqueeText(
                text = subTitle,
                style = subTitleStyle.copy(
                    shadow = shadowStyle,
                ),
                color = Color.White.copy(alpha = 0.4f),
                edgeWidth = marqueeEdgeWidth,
                enabled = marqueeEnabled,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 0.dp)
                .onSizeChanged(onActionsSizeChanged)
                .then(actionsModifier)
        ) {
            if (showLikeButton) {
                // Like Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        // Half the icon's size increase keeps its left edge fixed.
                        .absoluteOffset(x = 1.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onLikeClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLiked) {
                        SfIcon(
                            symbol = SfSymbol.StarFilled,
                            contentDescription = null,
                            modifier = Modifier.blur(2.dp, BlurredEdgeTreatment.Unbounded),
                            tint = iconColor.copy(alpha = 0.28f),
                            size = 24.dp,
                            weight = FontWeight.Bold,
                        )
                    }
                    SfIcon(
                        symbol = if (isLiked) SfSymbol.StarFilled else SfSymbol.Star,
                        contentDescription = stringResource(R.string.app_tab_library_songs),
                        tint = iconColor.copy(alpha = 0.8f),
                        size = 24.dp,
                        weight = FontWeight.Bold
                    )
                }
            }

            // More Button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .onGloballyPositioned(onMoreButtonPositioned)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onMoreClick,
                    ),
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

internal fun playerMetadataAnchorBounds(
    textCoordinates: LayoutCoordinates?,
    rowCoordinates: LayoutCoordinates?,
): Rect? {
    val textBounds = textCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
    val rowBounds = rowCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
    return when {
        textBounds == null -> rowBounds
        rowBounds == null -> textBounds
        else -> Rect(rowBounds.left, textBounds.top, rowBounds.right, textBounds.bottom)
    }
}
