package com.ljyh.mei.ui.component.home

import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ljyh.mei.constants.*
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo
import com.ljyh.mei.ui.screen.main.home.homeRowSnapPosition
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import kotlin.math.abs
import kotlin.math.sign

/** All home providers share the original eager recommendation row and its edge-aware snapping. */
@Composable
internal fun HomeRecommendationRow(keys: List<String>, content: @Composable (Int) -> Unit) {
    val landscape = rememberDeviceInfo().isLandscape
    val width = if (landscape) RecommendCardWidth else PortraitRecommendCardWidth
    val height = if (landscape) RecommendCardHeight else PortraitRecommendCardHeight
    val spacing = homeCardSpacing(landscape)
    val scrollState = rememberScrollState()
    val fling = rememberHomeRowFlingBehavior(scrollState, width, spacing, keys.size)
    Row(Modifier.fillMaxWidth().swipeBackExcluded().height(height)
        .horizontalScroll(scrollState, flingBehavior = fling).padding(horizontal = HomeRowHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
        keys.forEachIndexed { index, itemKey -> key(itemKey) { content(index) } }
    }
}

@Composable
internal fun rememberHomeRowFlingBehavior(
    scrollState: ScrollState,
    cardWidth: Dp,
    cardSpacing: Dp,
    cardCount: Int,
): FlingBehavior {
    val density = LocalDensity.current
    val itemSize = with(density) { cardWidth.roundToPx() }
    val stride = itemSize + with(density) { cardSpacing.roundToPx() }
    val minimumSnapVelocity = with(density) { 400.dp.toPx() }
    val provider = remember(scrollState, itemSize, stride, cardCount, minimumSnapVelocity) {
        object : SnapLayoutInfoProvider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float =
                (abs(decayOffset) - itemSize).coerceAtLeast(0f) * sign(decayOffset)

            override fun calculateSnapOffset(velocity: Float): Float {
                var before = Float.NEGATIVE_INFINITY
                var after = Float.POSITIVE_INFINITY
                repeat(cardCount) { index ->
                    val target = (index * stride).coerceAtMost(scrollState.maxValue)
                    val offset = (target - scrollState.value).toFloat()
                    if (offset <= 0f) before = maxOf(before, offset)
                    if (offset >= 0f) after = minOf(after, offset)
                }
                val offset = when {
                    abs(velocity) < minimumSnapVelocity -> if (abs(before) <= abs(after)) before else after
                    velocity > 0f -> after
                    else -> before
                }
                return if (offset.isFinite()) offset else 0f
            }
        }
    }
    val snapping = rememberSnapFlingBehavior(provider)
    val freeFling = ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    return remember(scrollState, snapping, freeFling, decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val target = decay.calculateTargetValue(0f, initialVelocity)
                val reachesEdge = (initialVelocity < 0f && -target >= scrollState.value) ||
                    (initialVelocity > 0f && target >= scrollState.maxValue - scrollState.value)
                return with(if (reachesEdge) freeFling else snapping) {
                    performFling(initialVelocity)
                }
            }
        }
    }
}

@Composable
internal fun rememberHomeRowFlingBehavior(listState: LazyListState): FlingBehavior {
    val snapPosition = remember(listState) {
        object : SnapPosition {
            override fun position(
                layoutSize: Int,
                itemSize: Int,
                beforeContentPadding: Int,
                afterContentPadding: Int,
                itemIndex: Int,
                itemCount: Int,
            ): Int = homeRowSnapPosition(
                layoutSize, itemSize, beforeContentPadding, afterContentPadding,
                itemIndex, itemCount, listState.layoutInfo.mainAxisItemSpacing,
            )
        }
    }
    val snapping = rememberSnapFlingBehavior(listState, snapPosition)
    val freeFling = ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    return remember(listState, snapping, freeFling, decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val layout = listState.layoutInfo
                val itemSize = layout.visibleItemsInfo.firstOrNull()?.size
                    ?: return with(freeFling) { performFling(initialVelocity) }
                // Each home cover row has uniform widths. Use its measured geometry so
                // rotation, content padding and card spacing share the real scroll bounds.
                val stride = itemSize.toFloat() + layout.mainAxisItemSpacing
                val startDistance = listState.firstVisibleItemIndex * stride +
                    listState.firstVisibleItemScrollOffset
                val contentSize = layout.totalItemsCount * stride - layout.mainAxisItemSpacing +
                    layout.beforeContentPadding + layout.afterContentPadding
                val endDistance = (contentSize - layout.viewportSize.width - startDistance)
                    .coerceAtLeast(0f)
                val target = decay.calculateTargetValue(0f, initialVelocity)
                // Snapping exactly to the first card consumes all velocity. A fling
                // that would cross either edge must instead reach the overscroll effect.
                val reachesEdge = (initialVelocity < 0f && -target >= startDistance) ||
                    (initialVelocity > 0f && target >= endDistance)
                return with(if (reachesEdge) freeFling else snapping) {
                    performFling(initialVelocity)
                }
            }
        }
    }
}
