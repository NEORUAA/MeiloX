package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
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
    onActionsSizeChanged: (IntSize) -> Unit = {},
    marqueeEdgeWidth: Dp = 26.dp,
    marqueeEnabled: Boolean = true,
) {
    val shadowStyle = if (needShadow) Shadow(
        color = Color.Black.copy(alpha = 0.3f),
        offset = Offset(2f, 2f),
        blurRadius = 12f
    ) else null

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically, // 垂直居中
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. 文字区域：使用 weight(1f) 占据剩余空间
        Column(
            modifier = Modifier.weight(1f)
                .onSizeChanged(onTextSizeChanged)
                .then(textModifier)
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
                color = Color.White.copy(alpha = 0.35f),
                edgeWidth = marqueeEdgeWidth,
                enabled = marqueeEnabled,
            )
        }

        // 2. 按钮区域
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 5.dp)
                .onSizeChanged(onActionsSizeChanged)
                .then(actionsModifier)
        ) {
            // Like Button
            IconButton(
                onClick = onLikeClick,
                enabled = enabled,
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
                        symbol = if (isLiked) SfSymbol.StarFilled else SfSymbol.Star,
                        contentDescription = stringResource(R.string.app_tab_library_songs),
                        tint = iconColor.copy(alpha = 0.8f),
                        size = 22.dp,
                        weight = FontWeight.Bold
                    )
                }
            }

            // More Button
            IconButton(
                onClick = onMoreClick,
                enabled = enabled,
                modifier = Modifier
                    .size(34.dp),
            ) {
                Box(
                    modifier = Modifier
                        .requiredSize(34.dp)
                        .onGloballyPositioned(onMoreButtonPositioned)
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
