package com.ljyh.mei.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.liquidglass.DampedDragAnimation
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

@androidx.compose.runtime.Immutable
data class GlassTabItem<T>(
    val key: T,
    val label: String,
    val symbol: SfSymbol,
    val contentDescription: String = label,
)

@androidx.compose.runtime.Immutable
private data class StableGlassTabItems<T>(
    val values: List<GlassTabItem<T>>,
)

private val LocalLiquidTabScale = staticCompositionLocalOf { { 1f } }

/** Shared compact icon endpoint for the bottom navigation and its adjacent search control. */
val CompactBottomControlIconSize = 22.dp

private val ExpandedNavigationIconSize = 28.dp
private val NavigationLabelStyle = IosTypography.caption.copy(
    fontSize = 10.sp,
    lineHeight = 13.sp,
)
private const val CompactIndicatorFadeStart = 0.74f
private const val CompactIndicatorFadeEnd = 0.98f
private const val CompactTabContentScaleFloor = 0.82f

private fun morphSurfaceWidthPx(
    fullWidthPx: Float,
    compactWidthPx: Float,
    compactProgress: Float,
): Float = lerp(fullWidthPx, compactWidthPx, compactProgress.coerceIn(0f, 1f))

private fun compactIndicatorVisibility(compactProgress: Float): Float {
    if (compactProgress <= CompactIndicatorFadeStart) return 1f
    val fadeProgress = ((compactProgress - CompactIndicatorFadeStart) /
        (CompactIndicatorFadeEnd - CompactIndicatorFadeStart)).coerceIn(0f, 1f)
    return 1f - FastOutSlowInEasing.transform(fadeProgress)
}

private fun compactIconLateProgress(compactProgress: Float): Float {
    if (compactProgress <= CompactIndicatorFadeStart) return 0f
    val morphProgress = ((compactProgress - CompactIndicatorFadeStart) /
        (1f - CompactIndicatorFadeStart)).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(morphProgress)
}

/**
 * iOS split-search tab bar backed by AndroidLiquidGlass' complete three-layer interaction.
 *
 * The outer glass keeps the capsule-to-circle navigation morph. The invisible source row and
 * the combined-backdrop indicator restore the original press, drag, lens, highlight and
 * velocity deformation from LiquidBottomTabs.
 */
@Composable
fun <T> GlassBottomBar(
    items: List<GlassTabItem<T>>,
    selectedKey: T,
    onSelected: (T) -> Unit,
    onExpand: () -> Unit,
    compactProgress: Float,
    compactSize: Dp,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    require(items.isNotEmpty())
    val compact = compactProgress.coerceIn(0f, 1f)
    val onSelectedState = rememberUpdatedState(onSelected)
    val stableOnSelected: (T) -> Unit = remember {
        { key -> onSelectedState.value(key) }
    }
    val stableItems = remember(items) { StableGlassTabItems(items) }
    val tabItems = stableItems.values
    val selectedIndex = tabItems.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
    val selectedItem = tabItems[selectedIndex]
    val colors = LocalGlassColors.current
    val isLight = !colors.isDark
    val containerColor = colors.container
    val tabsBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val compactState = rememberUpdatedState(compact)
    val onExpandState = rememberUpdatedState(onExpand)
    val selectedIndexState = rememberUpdatedState(selectedIndex)

    BoxWithConstraints(modifier.height(64.dp), contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val fullWidthPx = constraints.maxWidth.toFloat()
        val compactWidthPx = with(density) { compactSize.toPx() }
        val indicatorSettled = compact >= CompactIndicatorFadeEnd
        val surfaceWidthPx = morphSurfaceWidthPx(fullWidthPx, compactWidthPx, compact)
        val surfaceWidth = with(density) { surfaceWidthPx.toDp() }
        val surfaceHeight = androidx.compose.ui.unit.lerp(64.dp, compactSize, compact)
        val paddingPx = with(density) { 4.dp.toPx() }
        val expandedTabWidthPx = ((fullWidthPx - paddingPx * 2f) / tabItems.size)
            .coerceAtLeast(1f)
        val compactInnerWidthPx = (compactWidthPx - paddingPx * 2f).coerceAtLeast(1f)
        val tabWidthPx = expandedTabWidthPx
        val indicatorWidthPx = if (indicatorSettled) {
            compactWidthPx
        } else {
            lerp(expandedTabWidthPx, compactInnerWidthPx, compact)
        }
        val indicatorWidth = with(density) {
            indicatorWidthPx.toDp()
        }
        val expandedIndicatorVisibility = (1f - compact * 1.5f).coerceIn(0f, 1f)
        val innerGlassVisibility = compactIndicatorVisibility(compact)
        val innerGlassVisibilityState = rememberUpdatedState(innerGlassVisibility)
        val offsetAnimation = remember { Animatable(0f) }
        var currentIndex by remember { mutableIntStateOf(selectedIndex) }
        val dragAnimation = remember(animationScope, tabItems.size) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndexState.value.toFloat(),
                valueRange = 0f..(tabItems.lastIndex.coerceAtLeast(1)).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    if (compactState.value >= 0.74f) {
                        onExpandState.value()
                        animateToValue(selectedIndexState.value.toFloat())
                        return@DampedDragAnimation
                    }
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabItems.lastIndex)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (compactState.value >= 0.74f) return@DampedDragAnimation
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, tabItems.lastIndex.toFloat()),
                    )
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                },
            )
        }
        LaunchedEffect(selectedIndex, compact >= 0.74f) {
            currentIndex = selectedIndex
            dragAnimation.animateToValue(selectedIndex.toFloat())
        }
        LaunchedEffect(dragAnimation, stableItems) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index -> stableOnSelected(tabItems[index].key) }
        }
        val interactiveHighlight = remember(
            animationScope,
            isLtr,
            fullWidthPx,
            compactWidthPx,
            paddingPx,
            tabItems.size,
        ) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    val progress = compactState.value
                    val currentWidth = morphSurfaceWidthPx(fullWidthPx, compactWidthPx, progress)
                    val currentTabWidth =
                        ((currentWidth - paddingPx * 2f) / tabItems.size).coerceAtLeast(1f)
                    val tabCenter = if (isLtr) {
                        paddingPx + (dragAnimation.value + 0.5f) * currentTabWidth
                    } else {
                        currentWidth - paddingPx - (dragAnimation.value + 0.5f) * currentTabWidth
                    }
                    val morphCenter = lerp(tabCenter, compactWidthPx / 2f, progress)
                    val fraction = (offsetAnimation.value / fullWidthPx).fastCoerceIn(-1f, 1f)
                    val currentPanelOffset =
                        paddingPx * fraction.sign * EaseOut.transform(abs(fraction))
                    Offset(
                        x = morphCenter + currentPanelOffset,
                        y = size.height / 2f,
                    )
                },
            )
        }
        val commonTransform = remember(density, fullWidthPx, compactWidthPx, isLtr) {
            Modifier.graphicsLayer {
                val progress = compactState.value
                val fraction = (offsetAnimation.value / fullWidthPx).fastCoerceIn(-1f, 1f)
                translationX = paddingPx * fraction.sign * EaseOut.transform(abs(fraction))
                scaleY = 1f + 0.045f * sin(PI.toFloat() * progress)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                    if (isLtr) 0f else 1f,
                    0.5f,
                )
            }
        }
        val tabContentAlpha = remember {
            { (1f - compactState.value * 1.35f).coerceIn(0f, 1f) }
        }
        val pressProgressState = dragAnimation.pressProgressState
        val selectedIconColorFilterState: State<ColorFilter> = remember(
            compactState,
            pressProgressState,
            colors.accent,
            colors.content,
        ) {
            derivedStateOf {
                val compactTint = androidx.compose.ui.graphics.lerp(
                    colors.accent,
                    colors.content,
                    compactState.value,
                )
                ColorFilter.tint(
                    androidx.compose.ui.graphics.lerp(
                        compactTint,
                        colors.content,
                        pressProgressState.value,
                    ),
                )
            }
        }
        val lensSourceAccentFilter = remember(colors.accent) {
            ColorFilter.tint(colors.accent)
        }
        val lensSourceAccentModifier = remember(lensSourceAccentFilter) {
            Modifier.graphicsLayer(colorFilter = lensSourceAccentFilter)
        }
        val visibleTabContentScale = remember(compactState) {
            {
                (1f - compactState.value).coerceAtLeast(CompactTabContentScaleFloor)
            }
        }
        val lensSourceTabContentScale = remember(pressProgressState, compactState) {
            {
                lerp(1f, 1.2f, pressProgressState.value) *
                    (1f - compactState.value).coerceAtLeast(CompactTabContentScaleFloor)
            }
        }
        val selectedContentLateScaleProvider: () -> Float = remember(compactState) {
            {
                // Keep the icon geometry continuous through p = 0.98; only the settled
                // transparent indicator/source composition uses CompactIndicatorFadeEnd.
                val lateProgress = compactIconLateProgress(compactState.value)
                lerp(
                    1f,
                    (CompactBottomControlIconSize.value / ExpandedNavigationIconSize.value) /
                        CompactTabContentScaleFloor,
                    lateProgress,
                )
            }
        }
        val pressLayerBlock: GraphicsLayerScope.() -> Unit = remember(
            pressProgressState,
            density,
        ) {
            {
                val press = pressProgressState.value
                val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, press)
                scaleX = scale
                scaleY = scale
            }
        }
        val navigationGlassModifier = remember(
            backdrop,
            containerColor,
            pressProgressState,
            pressLayerBlock,
        ) {
            Modifier.navigationGlassBackground(
                backdrop = backdrop,
                shape = { Capsule() },
                containerColor = containerColor,
                pressProgressState = pressProgressState,
                layerBlock = pressLayerBlock,
            )
        }
        val hiddenLayerBackdropModifier = remember(tabsBackdrop) {
            Modifier.layerBackdrop(tabsBackdrop)
        }
        val hiddenBackdropModifier = remember(
            backdrop,
            containerColor,
            pressProgressState,
            pressLayerBlock,
        ) {
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val press = pressProgressState.value
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(
                            24.dp.toPx() * press,
                            28.dp.toPx() * press,
                            depthEffect = press > 0.01f,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.94f * pressProgressState.value)
                    },
                    layerBlock = pressLayerBlock,
                    onDrawSurface = { drawRect(containerColor) },
                )
        }
        val expandedIndicatorVisibilityState = rememberUpdatedState(expandedIndicatorVisibility)
        val indicatorPositionLayerBlock: GraphicsLayerScope.() -> Unit = remember(
            compactState,
            offsetAnimation,
            dragAnimation,
            isLtr,
            fullWidthPx,
            compactWidthPx,
            paddingPx,
            expandedTabWidthPx,
            compactInnerWidthPx,
            tabItems.size,
        ) {
            {
                // Follow the current tab center while the capsule shrinks, then converge to
                // the compact center. This keeps the first, middle and last tabs symmetric.
                val progress = compactState.value
                val currentWidth = morphSurfaceWidthPx(fullWidthPx, compactWidthPx, progress)
                val currentTabWidth =
                    ((currentWidth - paddingPx * 2f) / tabItems.size).coerceAtLeast(1f)
                val tabCenter = if (isLtr) {
                    paddingPx + (dragAnimation.value + 0.5f) * currentTabWidth
                } else {
                    currentWidth - paddingPx - (dragAnimation.value + 0.5f) * currentTabWidth
                }
                val indicatorWidth = if (progress >= CompactIndicatorFadeEnd) {
                    compactWidthPx
                } else {
                    lerp(expandedTabWidthPx, compactInnerWidthPx, progress)
                }
                if (progress >= CompactIndicatorFadeEnd) {
                    // The settled transparent interaction box is 48dp wide. Keep it centered
                    // inside the still-continuously-morphing outer surface until p reaches 1.
                    val compactOffset = (currentWidth - compactWidthPx).coerceAtLeast(0f) / 2f
                    translationX = compactOffset * if (isLtr) 1f else -1f
                } else {
                    val targetIndicatorLeft = lerp(
                        tabCenter - indicatorWidth / 2f,
                        compactWidthPx / 2f - indicatorWidth / 2f,
                        progress,
                    )
                    val indicatorLeft = targetIndicatorLeft.coerceIn(
                        paddingPx,
                        currentWidth - paddingPx - indicatorWidth,
                    )
                    val surfaceBaseLeft = if (isLtr) 0f else fullWidthPx - currentWidth
                    val directionalBase = if (isLtr) 0f else fullWidthPx - indicatorWidth
                    val fraction = (offsetAnimation.value / fullWidthPx).fastCoerceIn(-1f, 1f)
                    translationX = surfaceBaseLeft + indicatorLeft - directionalBase +
                        paddingPx * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        val indicatorBackdropLayerBlock: GraphicsLayerScope.() -> Unit = remember(
            dragAnimation,
            innerGlassVisibilityState,
        ) {
            {
                scaleX = dragAnimation.scaleX
                scaleY = dragAnimation.scaleY
                val velocity = dragAnimation.velocity / 10f
                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                // drawBackdrop installs this block on the backdrop layer itself. Keep the
                // complete refractive surface, highlight and shadows on the same alpha curve.
                alpha = innerGlassVisibilityState.value
            }
        }
        val indicatorBackdropModifier = if (compact < CompactIndicatorFadeEnd) {
            remember(
                indicatorBackdrop,
                dragAnimation,
                compactState,
                expandedIndicatorVisibilityState,
                containerColor,
                isLight,
                indicatorBackdropLayerBlock,
            ) {
                Modifier
                    .drawBackdrop(
                        backdrop = indicatorBackdrop,
                        shape = { Capsule() },
                        effects = {
                            val press = pressProgressState.value
                            val opticalIntensity = press * expandedIndicatorVisibilityState.value
                            lens(
                                14.dp.toPx() * opticalIntensity,
                                22.dp.toPx() * opticalIntensity,
                                depthEffect = opticalIntensity > 0.01f,
                                chromaticAberration = true,
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(
                                alpha = 0.90f * pressProgressState.value *
                                    expandedIndicatorVisibilityState.value,
                            )
                        },
                        shadow = {
                            Shadow(
                                alpha = 0.84f * pressProgressState.value *
                                    expandedIndicatorVisibilityState.value,
                            )
                        },
                        innerShadow = {
                            val strength = pressProgressState.value * expandedIndicatorVisibilityState.value
                            InnerShadow(
                                radius = 10.dp * strength,
                                alpha = 0.86f * strength,
                            )
                        },
                        layerBlock = indicatorBackdropLayerBlock,
                        onDrawSurface = {
                            val press = pressProgressState.value
                            val indicatorBaseColor = if (isLight) {
                                Color.Black.copy(alpha = 0.10f)
                            } else {
                                Color.White.copy(alpha = 0.10f)
                            }
                            val indicatorColor = androidx.compose.ui.graphics.lerp(
                                indicatorBaseColor,
                                containerColor,
                                compactState.value,
                            )
                            drawRect(
                                indicatorColor,
                                alpha = (1f - press) * (1f - compactState.value),
                            )
                            drawRect(
                                Color.Black.copy(
                                    alpha = 0.03f * press * expandedIndicatorVisibilityState.value,
                                ),
                            )
                        },
                    )
            }
        } else {
            Modifier
        }

        // Keep both layers on the same navigation morph while limiting the press magnification
        // to the hidden Lens source.
        CompositionLocalProvider(LocalLiquidTabScale provides visibleTabContentScale) {
            Row(
                modifier = Modifier
                    .width(surfaceWidth)
                    .height(surfaceHeight)
                    .then(commonTransform)
                    .then(navigationGlassModifier)
                    .then(interactiveHighlight.modifier)
                    .then(if (compact >= 0.74f) Modifier.clearAndSetSemantics {} else Modifier)
                    .padding(4.dp)
                    .clip(Capsule()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Row(Modifier.fillMaxSize()) {
                        FullTabContent(
                            items = stableItems,
                            selectedKey = selectedItem.key,
                            onSelected = stableOnSelected,
                            enabled = compact < 0.74f,
                            alpha = tabContentAlpha,
                            selectedColorFilter = selectedIconColorFilterState,
                            hideSelectedItem = true,
                        )
                    }
                    MorphingSelectedNavigationItem(
                        item = selectedItem,
                        compactProgress = compactState,
                        fullWidthPx = fullWidthPx,
                        compactWidthPx = compactWidthPx,
                        itemCount = tabItems.size,
                        selectedIndex = selectedIndex,
                        isLtr = isLtr,
                        horizontalInsetPx = paddingPx,
                        selectedContentLateScale = selectedContentLateScaleProvider,
                        labelAlpha = tabContentAlpha,
                        selectedIconColorFilter = selectedIconColorFilterState,
                        contentColor = colors.content,
                    )
                }
            }
        }

        if (compact < CompactIndicatorFadeEnd) {
            CompositionLocalProvider(LocalLiquidTabScale provides lensSourceTabContentScale) {
                Row(
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .then(hiddenLayerBackdropModifier)
                        .width(surfaceWidth)
                        .height(surfaceHeight)
                        .then(commonTransform)
                        .then(hiddenBackdropModifier)
                        .then(interactiveHighlight.modifier)
                        .padding(4.dp)
                        .clip(Capsule()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(lensSourceAccentModifier),
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            FullTabContent(
                                items = stableItems,
                                selectedKey = selectedItem.key,
                                onSelected = stableOnSelected,
                                // Keep the exported source interactive just like LiquidBottomTabs. It
                                // is visually hidden, but it sits above the visible row in the hit-test tree.
                                // The source content is accent-filtered as one unit so every item
                                // sampled through the Lens uses the same emphasis color.
                                enabled = compact < 0.74f,
                                alpha = tabContentAlpha,
                                selectedColorFilter = selectedIconColorFilterState,
                                hideSelectedItem = true,
                            )
                        }
                        MorphingSelectedNavigationItem(
                            item = selectedItem,
                            compactProgress = compactState,
                            fullWidthPx = fullWidthPx,
                            compactWidthPx = compactWidthPx,
                            itemCount = tabItems.size,
                            selectedIndex = selectedIndex,
                            isLtr = isLtr,
                            horizontalInsetPx = paddingPx,
                            selectedContentLateScale = selectedContentLateScaleProvider,
                            labelAlpha = tabContentAlpha,
                            selectedIconColorFilter = selectedIconColorFilterState,
                            contentColor = colors.content,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .graphicsLayer(indicatorPositionLayerBlock)
                .then(interactiveHighlight.gestureModifier)
                .then(dragAnimation.modifier)
                .then(indicatorBackdropModifier)
                .height(
                    if (indicatorSettled) {
                        compactSize
                    } else {
                        androidx.compose.ui.unit.lerp(56.dp, compactSize - 8.dp, compact)
                    },
                )
                .width(indicatorWidth)
                .then(
                    if (compact >= 0.74f) {
                        Modifier
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onExpandState.value() },
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = selectedItem.contentDescription
                            }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
private fun <T> androidx.compose.foundation.layout.RowScope.FullTabContent(
    items: StableGlassTabItems<T>,
    selectedKey: T,
    onSelected: (T) -> Unit,
    enabled: Boolean,
    alpha: () -> Float,
    selectedColorFilter: State<ColorFilter>,
    hideSelectedItem: Boolean = false,
) {
    val colors = LocalGlassColors.current
    val scale = LocalLiquidTabScale.current
    items.values.forEach { item ->
        val selected = item.key == selectedKey
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(Capsule())
                .clickable(
                    enabled = enabled,
                    interactionSource = null,
                    indication = null,
                    role = Role.Tab,
                    onClick = { onSelected(item.key) },
                )
                .graphicsLayer {
                    this.alpha = if (selected && hideSelectedItem) 0f else alpha()
                    val contentScale = scale()
                    scaleX = contentScale
                    scaleY = contentScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NavigationTabContent(
                item = item,
                selected = selected,
                selectedColorFilter = selectedColorFilter,
            )
        }
    }
}

@Composable
private fun <T> NavigationTabContent(
    item: GlassTabItem<T>,
    selected: Boolean,
    selectedColorFilter: State<ColorFilter>? = null,
    selectedIconTint: Color? = null,
    labelAlpha: (() -> Float)? = null,
) {
    val colors = LocalGlassColors.current
    val labelVisibility = labelAlpha?.invoke()?.coerceIn(0f, 1f) ?: 1f
    SfIcon(
        symbol = item.symbol,
        contentDescription = item.contentDescription,
        tint = if (selected) selectedIconTint ?: colors.accent else colors.content,
        size = ExpandedNavigationIconSize,
        weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier
            .padding(top = 3.dp * labelVisibility)
            .then(
                if (selected && selectedColorFilter != null) {
                    Modifier.graphicsLayer {
                        colorFilter = selectedColorFilter.value
                    }
                } else {
                    Modifier
                },
            ),
    )
    Text(
        text = item.label,
        color = colors.content,
        style = NavigationLabelStyle,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .then(
                if (labelAlpha != null) {
                    Modifier.collapseVertically(labelVisibility)
                } else {
                    Modifier
                },
            )
            .padding(top = 1.dp, bottom = 4.dp)
            .then(
                if (labelAlpha != null || (selected && selectedColorFilter != null)) {
                    Modifier.graphicsLayer {
                        if (labelAlpha != null) {
                            alpha = labelVisibility
                        }
                        if (selected) {
                            selectedColorFilter?.let { colorFilter = it.value }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    )
}

private fun Modifier.collapseVertically(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height * fraction.coerceIn(0f, 1f))
        .roundToInt()
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) {
        placeable.placeRelative(0, 0)
    }
}

@Composable
private fun <T> BoxScope.MorphingSelectedNavigationItem(
    item: GlassTabItem<T>,
    compactProgress: State<Float>,
    fullWidthPx: Float,
    compactWidthPx: Float,
    itemCount: Int,
    selectedIndex: Int,
    isLtr: Boolean,
    horizontalInsetPx: Float,
    selectedContentLateScale: () -> Float,
    labelAlpha: () -> Float,
    selectedIconColorFilter: State<ColorFilter>,
    contentColor: Color,
) {
    val sharedTabScale = LocalLiquidTabScale.current
    val density = LocalDensity.current
    val currentContentWidthPx = morphSurfaceWidthPx(
        fullWidthPx,
        compactWidthPx,
        compactProgress.value.coerceIn(0f, 1f),
    ) - 2f * horizontalInsetPx
    val selectedTabWidth = with(density) {
        (currentContentWidthPx / itemCount)
            .coerceAtLeast(ExpandedNavigationIconSize.toPx())
            .toDp()
    }
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxSize()
            .graphicsLayer {
                val progress = compactProgress.value.coerceIn(0f, 1f)
                val expandedContentWidth = fullWidthPx - 2f * horizontalInsetPx
                val compactContentWidth = compactWidthPx - 2f * horizontalInsetPx
                val currentSurfaceWidth = morphSurfaceWidthPx(
                    fullWidthPx,
                    compactWidthPx,
                    progress,
                )
                val currentContentWidth = currentSurfaceWidth - 2f * horizontalInsetPx
                val expandedTabWidth = (expandedContentWidth / itemCount).coerceAtLeast(1f)
                val expandedCenter = if (isLtr) {
                    (selectedIndex + 0.5f) * expandedTabWidth
                } else {
                    expandedContentWidth - (selectedIndex + 0.5f) * expandedTabWidth
                }
                val compactCenter = compactContentWidth / 2f
                val targetCenter = lerp(expandedCenter, compactCenter, progress)
                translationX = targetCenter - currentContentWidth / 2f
                // Scale the selected icon and label as one tab item. The shared tab scale owns
                // press deformation and the <= 0.74 morph; the late correction only reconciles
                // the expanded icon size with the compact control endpoint.
                val scale = sharedTabScale() * selectedContentLateScale()
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(selectedTabWidth)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NavigationTabContent(
                item = item,
                selected = true,
                selectedColorFilter = selectedIconColorFilter,
                selectedIconTint = contentColor,
                labelAlpha = labelAlpha,
            )
        }
    }
}
