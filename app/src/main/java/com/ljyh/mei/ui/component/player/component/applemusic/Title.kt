package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import kotlin.math.roundToInt

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
    var displayedIsLiked by remember { mutableStateOf(isLiked) }
    if (showLikeButton) displayedIsLiked = isLiked
    val shadowStyle = if (needShadow) Shadow(
        color = Color.Black.copy(alpha = 0.3f),
        offset = Offset(2f, 2f),
        blurRadius = 12f
    ) else null

    PlayerMetadataRow(
        favoriteVisible = showLikeButton,
        modifier = modifier.onGloballyPositioned(onRowPositioned),
        text = {
            // Text uses the space released by the fading favorite.
            Column(
                modifier = Modifier
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
        },
        actions = { favoriteModifier ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 5.dp)
                    .onSizeChanged(onActionsSizeChanged)
                    .then(actionsModifier)
            ) {
                Box(
                    Modifier.padding(end = 12.dp)
                        // Keep the icon's original leading-edge adjustment outside its scale.
                        .absoluteOffset(x = 1.dp)
                        .then(favoriteModifier),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled && showLikeButton,
                                role = Role.Button,
                                onClick = onLikeClick,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (displayedIsLiked) {
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
                            symbol = if (displayedIsLiked) SfSymbol.StarFilled else SfSymbol.Star,
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
        },
    )
}

/** Animate text space independently of the full action layer and its hit bounds. */
@Composable
internal fun PlayerMetadataRow(
    favoriteVisible: Boolean,
    modifier: Modifier = Modifier,
    text: @Composable () -> Unit,
    actions: @Composable (favoriteModifier: Modifier) -> Unit,
) {
    val progress = animateFloatAsState(
        targetValue = if (favoriteVisible) 1f else 0f,
        animationSpec = tween(500, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)),
        label = "PlayerFavoriteVisibility",
    )
    val favoriteModifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (progress.value > 0f) placeable.placeRelative(0, 0)
        }
    }.graphicsLayer {
        alpha = progress.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
        clip = false
    }.drawWithContent {
        // Transform only drawing, preserving the full layout and touch target.
        val shrink = 2.dp.toPx() * (1f - progress.value)
        val visualScale = (1f - shrink / size.minDimension.coerceAtLeast(1f)).coerceIn(0f, 1f)
        withTransform({ scale(visualScale, visualScale, center) }) { this@drawWithContent.drawContent() }
    }.then(if (favoriteVisible) Modifier else Modifier.clearAndSetSemantics { })
    Layout(
        modifier = modifier,
        content = {
            text()
            actions(favoriteModifier)
        },
    ) { measurables, constraints ->
        // Never shrink the actions' offscreen buffer: it must contain the whole star.
        val actionPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val releasedWidth = ((34.dp + 12.dp).toPx() * (1f - progress.value)).roundToInt()
        val reservedWidth = (actionPlaceable.width - releasedWidth).coerceAtLeast(0)
        val textConstraints = constraints.offset(horizontal = -reservedWidth).copy(minHeight = 0)
        val textPlaceable = measurables[0].measure(
            if (textConstraints.hasBoundedWidth) textConstraints.copy(minWidth = textConstraints.maxWidth)
            else textConstraints,
        )
        val width = constraints.constrainWidth(textPlaceable.width + reservedWidth)
        val height = constraints.constrainHeight(maxOf(textPlaceable.height, actionPlaceable.height))
        layout(width, height) {
            textPlaceable.placeRelative(0, (height - textPlaceable.height) / 2)
            actionPlaceable.placeRelative(width - actionPlaceable.width, (height - actionPlaceable.height) / 2)
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
