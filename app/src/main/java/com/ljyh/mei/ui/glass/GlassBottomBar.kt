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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
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
import com.ljyh.mei.constants.NavigationBarSurfaceHeight
import com.ljyh.mei.ui.liquidglass.DampedDragAnimation
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
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

internal val LocalLiquidTabScale = staticCompositionLocalOf { { 1f } }

/** Shared compact icon endpoint for the bottom navigation and its adjacent search control. */
val CompactBottomControlIconSize = 22.dp

private val ExpandedNavigationIconSize = 28.dp
private val ExpandedNavigationItemHeight = NavigationBarSurfaceHeight - 8.dp
private val ExpandedNavigationItemMinWidth = 75.dp
private val NavigationItemIconClearance = 4.dp

private data class NavigationTabGeometry(val widthPx: Float, val stepPx: Float) {
    fun center(index: Float): Float = widthPx / 2f + index * stepPx
}

private fun navigationTabGeometry(
    contentWidthPx: Float,
    itemCount: Int,
    minimumWidthPx: Float,
    protectedIconRadiusPx: Float,
): NavigationTabGeometry {
    val availableWidth = contentWidthPx.coerceAtLeast(1f)
    // width / 2 + protectedIconRadius must not exceed the center-to-center step.
    val maximumWidth = if (itemCount > 1) {
        (2f * (availableWidth - (itemCount - 1) * protectedIconRadiusPx) / (itemCount + 1))
            .coerceIn(1f, availableWidth)
    } else availableWidth
    val width = (availableWidth / itemCount)
        .coerceAtLeast(minimumWidthPx)
        .coerceAtMost(maximumWidth)
    // Keep the outer edges inside the capsule; narrow bars overlap adjacent items.
    val step = if (itemCount > 1) (availableWidth - width) / (itemCount - 1) else 0f
    return NavigationTabGeometry(width, step)
}

private val NavigationLabelStyle: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = IosTypography.caption.copy(
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

internal fun compactIconLateProgress(compactProgress: Float): Float {
    if (compactProgress <= CompactIndicatorFadeStart) return 0f
    val morphProgress = ((compactProgress - CompactIndicatorFadeStart) /
        (1f - CompactIndicatorFadeStart)).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(morphProgress)
}

/** An opaque parent accent tint already supplies the selected source's RGB. */
internal fun navigationLensSourceColorFilter(
    colors: GlassColors,
    selectedFilter: State<ColorFilter>,
): State<ColorFilter>? =
    if (colors.accent.alpha == 1f && colors.content.alpha == 1f) null else selectedFilter

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
    compactProgress: State<Float>,
    compactSize: Dp,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    require(items.isNotEmpty())
    val compactState = remember(compactProgress) {
        derivedStateOf { compactProgress.value.coerceIn(0f, 1f) }
    }
    val morphing by remember(compactProgress) {
        derivedStateOf { compactProgress.value != 0f && compactProgress.value != 1f }
    }
    val compactMode by remember(compactState) {
        derivedStateOf { compactState.value >= CompactIndicatorFadeStart }
    }
    val onSelectedState = rememberUpdatedState(onSelected)
    val stableOnSelected: (T) -> Unit = remember {
        { key -> onSelectedState.value(key) }
    }
    val stableItems = remember(items) { StableGlassTabItems(items) }
    val tabItems = stableItems.values
    val selectedIndex = tabItems.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0
    val selectedItem = tabItems[selectedIndex]
    val colors = LocalGlassColors.current
    val appearance = LocalGlassAppearance.current
    val isLight = !colors.isDark
    val navigationInnerShadows = rememberNavigationInnerShadows(appearance.usesBackdrop)
    val hiddenInnerShadows = rememberNavigationInnerShadows(appearance.usesRefraction)
    val containerColor = colors.container
    val tabsBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val viewConfiguration = LocalViewConfiguration.current
    val navigationViewConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            // Horizontal auto-expansion would defeat the neighbor icon clearance.
            override val minimumTouchTargetSize = DpSize(
                0.dp,
                viewConfiguration.minimumTouchTargetSize.height,
            )
        }
    }
    val animationScope = rememberCoroutineScope()
    val onExpandState = rememberUpdatedState(onExpand)
    val selectedIndexState = rememberUpdatedState(selectedIndex)
    val tabItemsState = rememberUpdatedState(tabItems)

    val dragCoordinateSpace = remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(
        modifier.height(NavigationBarSurfaceHeight).onGloballyPositioned { dragCoordinateSpace.value = it },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val fullWidthPx = constraints.maxWidth.toFloat()
        val compactWidthPx = with(density) { compactSize.toPx() }
        val indicatorSettled by remember(compactState) {
            derivedStateOf { compactState.value >= CompactIndicatorFadeEnd }
        }
        // Geometry changes belong to measurement. Do not rebuild the tab/source/effect
        // compositions on every spring frame just to update their width and height.
        val surfaceSizeModifier = remember(compactState, fullWidthPx, compactWidthPx, compactSize) {
            Modifier.layout { measurable, constraints ->
                val progress = compactState.value
                val width = constraints.constrainWidth(
                    morphSurfaceWidthPx(fullWidthPx, compactWidthPx, progress).roundToInt(),
                )
                val height = constraints.constrainHeight(
                    androidx.compose.ui.unit.lerp(NavigationBarSurfaceHeight, compactSize, progress).roundToPx(),
                )
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                layout(width, height) { placeable.placeRelative(0, 0) }
            }
        }
        val paddingPx = with(density) { 4.dp.toPx() }
        val expandedTabs = remember(fullWidthPx, paddingPx, density, tabItems.size) {
            navigationTabGeometry(
                contentWidthPx = fullWidthPx - paddingPx * 2f,
                itemCount = tabItems.size,
                minimumWidthPx = with(density) { ExpandedNavigationItemMinWidth.toPx() },
                protectedIconRadiusPx = with(density) {
                    ExpandedNavigationIconSize.toPx() / 2f + NavigationItemIconClearance.toPx()
                },
            )
        }
        val expandedTabWidthPx = expandedTabs.widthPx
        val compactInnerWidthPx = (compactWidthPx - paddingPx * 2f).coerceAtLeast(1f)
        val tabStepPx = rememberUpdatedState(expandedTabs.stepPx.coerceAtLeast(1f))
        val innerGlassVisibilityState = remember(compactState) {
            derivedStateOf { compactIndicatorVisibility(compactState.value) }
        }
        val indicatorSizeModifier = remember(
            compactState, expandedTabWidthPx, compactInnerWidthPx, compactWidthPx, compactSize,
        ) {
            Modifier.layout { measurable, constraints ->
                val progress = compactState.value
                val settled = progress >= CompactIndicatorFadeEnd
                val width = constraints.constrainWidth((if (settled) compactWidthPx else
                    lerp(expandedTabWidthPx, compactInnerWidthPx, progress)).roundToInt())
                val height = constraints.constrainHeight((if (settled) compactSize else
                    androidx.compose.ui.unit.lerp(ExpandedNavigationItemHeight, compactSize - 8.dp, progress)).roundToPx())
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                layout(width, height) { placeable.placeRelative(0, 0) }
            }
        }
        val indicatorHitBoundsModifier = remember(compactState, expandedTabs, tabItems.size) {
            Modifier.layout { measurable, constraints ->
                val overlap = (expandedTabs.widthPx - expandedTabs.stepPx).coerceAtLeast(0f) *
                    (1f - compactState.value) / 2f
                val startInset = if (selectedIndexState.value > 0) overlap.roundToInt() else 0
                val endInset = if (selectedIndexState.value < tabItems.lastIndex) overlap.roundToInt() else 0
                val width = (constraints.maxWidth - startInset - endInset).coerceAtLeast(1)
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(width, constraints.maxHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeRelative(startInset, 0)
                }
            }
        }
        val offsetAnimation = remember { Animatable(0f) }
        val selectionPressOrigin = remember { mutableStateOf(true) }
        val dragAnimation = remember(animationScope, tabItems.size, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndexState.value.toFloat(),
                valueRange = 0f..(tabItems.lastIndex.coerceAtLeast(1)).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                dragCoordinateSpace = { dragCoordinateSpace.value },
                onDragStarted = { selectionPressOrigin.value = compactState.value == 0f },
                onDragStopped = {
                    if (compactState.value >= 0.74f) {
                        onExpandState.value()
                        animateToValue(selectedIndexState.value.toFloat())
                        return@DampedDragAnimation
                    }
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabItems.lastIndex)
                    if (target != selectedIndexState.value) {
                        stableOnSelected(tabItemsState.value[target].key)
                    }
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (compactState.value >= 0.74f) return@DampedDragAnimation
                    updateValue(
                        (targetValue + dragAmount.x / tabStepPx.value * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, tabItems.lastIndex.toFloat()),
                    )
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                },
            )
        }
        LaunchedEffect(selectedIndex, dragAnimation) {
            // Route and preference updates only move the indicator. Navigation callbacks
            // belong to user input, including while the bar is fading out on a detail page.
            if (dragAnimation.targetValue != selectedIndex.toFloat()) {
                selectionPressOrigin.value = compactState.value == 0f
                dragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }
        // Compact input keeps panel feedback without pressing the emerging selection.
        val selectionFeedbackEnabled = remember(compactState, selectionPressOrigin) {
            derivedStateOf { selectionPressOrigin.value && compactState.value == 0f }
        }
        val selectionPressProgressState = remember(dragAnimation, selectionFeedbackEnabled) {
            derivedStateOf { if (selectionFeedbackEnabled.value) dragAnimation.pressProgress else 0f }
        }
        val interactiveHighlight = remember(
            animationScope,
            isLtr,
            fullWidthPx,
            compactWidthPx,
            paddingPx,
            expandedTabs,
            dragAnimation,
        ) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    val progress = compactState.value
                    val expandedTabCenter = expandedTabs.center(dragAnimation.value)
                    val tabCenter = if (isLtr) {
                        paddingPx + expandedTabCenter
                    } else {
                        fullWidthPx - paddingPx - expandedTabCenter
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
        val selectionHighlightModifier = remember(interactiveHighlight, selectionFeedbackEnabled) {
            interactiveHighlight.highlightModifier(
                progressProvider = { if (selectionFeedbackEnabled.value) interactiveHighlight.pressProgress else 0f },
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
            selectionPressProgressState,
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
                        selectionPressProgressState.value,
                    ),
                )
            }
        }
        val hiddenSelectedIconColorFilterState = navigationLensSourceColorFilter(
            colors, selectedIconColorFilterState,
        )
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
        val lensSourceTabContentScale = remember(selectionPressProgressState, compactState) {
            {
                lerp(1f, 1.2f, selectionPressProgressState.value) *
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
        val panelPressScale = remember(pressProgressState, compactState, fullWidthPx, compactWidthPx, density) {
            derivedStateOf {
                navigationPressScale(
                    width = morphSurfaceWidthPx(fullWidthPx, compactWidthPx, compactState.value).roundToInt().toFloat(),
                    expansion = with(density) { 16.dp.toPx() },
                    press = pressProgressState.value,
                )
            }
        }
        val pressLayerBlock: GraphicsLayerScope.() -> Unit = remember(panelPressScale) {
            {
                scaleX = panelPressScale.value
                scaleY = panelPressScale.value
            }
        }
        val lensAnchoring = if (appearance.usesRefraction) 1f else 0f
        val tabPanelScale: (Int) -> Float = remember(dragAnimation, panelPressScale, lensAnchoring) {
            { index ->
                navigationTabPanelScale(index, dragAnimation.value, panelPressScale.value, lensAnchoring)
            }
        }
        val indicatorPanelScale = remember(panelPressScale, lensAnchoring, selectionFeedbackEnabled) {
            derivedStateOf {
                if (selectionFeedbackEnabled.value) {
                    navigationTabPanelScale(0, 0f, panelPressScale.value, lensAnchoring)
                } else 1f
            }
        }
        val selectedTabPanelScale: () -> Float = remember(tabPanelScale, selectedIndex) {
            { tabPanelScale(selectedIndex) }
        }
        val tabPressCorrection: GraphicsLayerScope.(Int) -> Unit = remember(
            compactState, fullWidthPx, compactWidthPx, paddingPx, expandedTabWidthPx,
            compactInnerWidthPx, tabItems.size, isLtr, panelPressScale, tabPanelScale,
        ) {
            { index ->
                val progress = compactState.value
                val contentWidth = morphSurfaceWidthPx(fullWidthPx, compactWidthPx, progress) - 2f * paddingPx
                val itemWidth = lerp(expandedTabWidthPx, compactInnerWidthPx / tabItems.size, progress)
                    .roundToInt().toFloat().coerceAtMost(contentWidth)
                val step = if (tabItems.size > 1) (contentWidth - itemWidth) / (tabItems.size - 1) else 0f
                val center = itemWidth / 2f + index * step
                val physicalCenter = if (isLtr) center else contentWidth - center
                val panelScale = panelPressScale.value
                val tabScale = tabPanelScale(index)
                translationX = navigationTabPressOffset(physicalCenter, contentWidth / 2f, panelScale, tabScale)
                scaleX *= tabScale / panelScale
                scaleY *= tabScale / panelScale
            }
        }
        val morphingNavigationGlass = rememberMorphingNavigationGlass(
            backdrop = backdrop,
            active = morphing,
            shape = Capsule(),
            tint = containerColor,
            navigationMaterial = appearance.usesBackdrop,
            isDark = colors.isDark,
            pressProgress = { pressProgressState.value },
            layerBlock = pressLayerBlock,
        )
        val navigationGlassModifier = if (morphing) morphingNavigationGlass else remember(
            backdrop,
            containerColor,
            isLight,
            navigationInnerShadows,
            pressProgressState,
            pressLayerBlock,
            appearance,
        ) {
            Modifier.navigationGlassBackground(
                backdrop = backdrop,
                shape = { Capsule() },
                containerColor = containerColor,
                navigationMaterial = appearance.usesBackdrop,
                innerShadows = navigationInnerShadows,
                isDark = colors.isDark,
                pressProgressState = pressProgressState,
                layerBlock = pressLayerBlock,
                appearance = appearance,
            )
        }
        val hiddenLayerBackdropModifier = remember(tabsBackdrop) {
            Modifier.layerBackdrop(tabsBackdrop)
        }
        val hiddenSourceSizeModifier = remember(compactState, fullWidthPx, compactWidthPx, compactSize, density) {
            Modifier.layout { measurable, constraints ->
                val progress = compactState.value
                val width = constraints.constrainWidth(
                    morphSurfaceWidthPx(fullWidthPx, compactWidthPx, progress).roundToInt(),
                )
                val height = constraints.constrainHeight(
                    navigationLensSourceHeight(
                        androidx.compose.ui.unit.lerp(NavigationBarSurfaceHeight, compactSize, progress).toPx(),
                        paddingPx,
                    ).roundToInt(),
                )
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                layout(width, height) { placeable.placeRelative(0, 0) }
            }
        }
        val morphingHiddenGlass = rememberMorphingNavigationGlass(
            backdrop = backdrop,
            active = morphing && appearance.usesRefraction,
            shape = Capsule(),
            tint = containerColor,
            navigationMaterial = appearance.usesBackdrop,
            isDark = colors.isDark,
            pressProgress = { selectionPressProgressState.value },
            layerBlock = {},
            tintMultiplier = 1f,
            lensSource = true,
        )
        val hiddenBackdropModifier = if (morphing) morphingHiddenGlass else remember(
            backdrop,
            containerColor,
            isLight,
            hiddenInnerShadows,
            appearance,
            selectionPressProgressState,
        ) {
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val press = selectionPressProgressState.value
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(
                            24.dp.toPx() * press,
                            28.dp.toPx() * press,
                            depthEffect = press > 0.01f,
                            chromaticAberration = true,
                        )
                        if (isLight && appearance.usesBackdrop) liftNavigationShadows()
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.94f * selectionPressProgressState.value)
                    },
                    onDrawSurface = {
                        if (appearance.usesBackdrop) {
                            drawNavigationGlassMaterial(containerColor, colors.isDark, 1f)
                            hiddenInnerShadows?.draw(this, Capsule(), isDark = colors.isDark)
                        } else {
                            drawRect(containerColor)
                        }
                    },
                )
        }
        val expandedIndicatorVisibilityState = remember(compactState) {
            derivedStateOf { (1f - compactState.value * 1.5f).coerceIn(0f, 1f) }
        }
        val indicatorPositionLayerBlock: GraphicsLayerScope.() -> Unit = remember(
            compactState,
            offsetAnimation,
            dragAnimation,
            indicatorPanelScale,
            isLtr,
            fullWidthPx,
            compactWidthPx,
            paddingPx,
            expandedTabWidthPx,
            compactInnerWidthPx,
            expandedTabs,
        ) {
            {
                // Follow the current tab center while the capsule shrinks, then converge to
                // the compact center. This keeps the first, middle and last tabs symmetric.
                val progress = compactState.value
                val currentWidth = morphSurfaceWidthPx(fullWidthPx, compactWidthPx, progress)
                val expandedTabCenter = expandedTabs.center(dragAnimation.value)
                val tabCenter = if (isLtr) {
                    paddingPx + expandedTabCenter
                } else {
                    fullWidthPx - paddingPx - expandedTabCenter
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
                    translationX += navigationPanelCenterOffset(
                        indicatorLeft + indicatorWidth / 2f, currentWidth / 2f, indicatorPanelScale.value,
                    )
                }
            }
        }
        val indicatorBackdropLayerBlock: GraphicsLayerScope.() -> Unit = remember(
            dragAnimation,
            innerGlassVisibilityState,
            selectionFeedbackEnabled,
        ) {
            {
                scaleX = if (selectionFeedbackEnabled.value) dragAnimation.scaleX else 1f
                scaleY = if (selectionFeedbackEnabled.value) dragAnimation.scaleY else 1f
                val velocity = if (selectionFeedbackEnabled.value) dragAnimation.velocity / 10f else 0f
                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                // drawBackdrop installs this block on the backdrop layer itself. Keep the
                // complete refractive surface, highlight and shadows on the same alpha curve.
                alpha = innerGlassVisibilityState.value
            }
        }
        val indicatorBackdropModifier = if (!indicatorSettled) {
            remember(
                indicatorBackdrop,
                dragAnimation,
                compactState,
                expandedIndicatorVisibilityState,
                containerColor,
                isLight,
                indicatorBackdropLayerBlock,
                selectionPressProgressState,
                appearance,
            ) {
                Modifier
                    .drawBackdrop(
                        backdrop = if (appearance.usesRefraction) indicatorBackdrop else emptyBackdrop(),
                        shape = { Capsule() },
                        effects = {
                            val press = selectionPressProgressState.value
                            val opticalIntensity = press * expandedIndicatorVisibilityState.value
                            if (appearance.usesRefraction) {
                                lens(
                                    NavigationIndicatorRefractionHeight.toPx() * opticalIntensity,
                                    NavigationIndicatorRefractionAmount.toPx() * opticalIntensity,
                                    chromaticAberration = true,
                                )
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(
                                alpha = 0.90f * selectionPressProgressState.value *
                                    expandedIndicatorVisibilityState.value,
                            )
                        },
                        shadow = {
                            Shadow(
                                alpha = 0.84f * selectionPressProgressState.value *
                                    expandedIndicatorVisibilityState.value,
                            )
                        },
                        innerShadow = {
                            val strength = selectionPressProgressState.value * expandedIndicatorVisibilityState.value
                            InnerShadow(
                                radius = 10.dp * strength,
                                alpha = 0.86f * strength,
                            )
                        },
                        layerBlock = indicatorBackdropLayerBlock,
                        onDrawSurface = {
                            val press = selectionPressProgressState.value
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
                                alpha = (if (appearance.usesRefraction) 1f - press else 1f) *
                                    (1f - compactState.value),
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
        CompositionLocalProvider(
            LocalLiquidTabScale provides visibleTabContentScale,
            LocalViewConfiguration provides navigationViewConfiguration,
        ) {
            // Glass and unselected tabs follow the panel; the selected item has its own center.
            Box(
                modifier = Modifier
                    .then(surfaceSizeModifier)
                    .then(commonTransform)
                    .then(navigationGlassModifier)
                    .then(interactiveHighlight.modifier),
            )
            Row(
                modifier = Modifier
                    .then(surfaceSizeModifier)
                    .then(commonTransform)
                    .then(if (compactMode) Modifier.clearAndSetSemantics {} else Modifier)
                    .padding(4.dp)
                    .clip(Capsule()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Box(Modifier.fillMaxSize().graphicsLayer(pressLayerBlock)) {
                        FullTabContent(
                            items = stableItems,
                            selectedKey = selectedItem.key,
                            onSelected = stableOnSelected,
                            enabled = !compactMode,
                            alpha = tabContentAlpha,
                            selectedColorFilter = selectedIconColorFilterState,
                            hideSelectedItem = true,
                            expandedTabWidthPx = expandedTabWidthPx,
                            compactTabWidthPx = compactInnerWidthPx / tabItems.size,
                            compactProgress = compactState,
                            pressCorrection = tabPressCorrection,
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
                        panelScale = selectedTabPanelScale,
                    )
                }
            }
        }

        if (!indicatorSettled && appearance.usesRefraction) {
            CompositionLocalProvider(
                LocalLiquidTabScale provides lensSourceTabContentScale,
                LocalViewConfiguration provides navigationViewConfiguration,
            ) {
                Row(
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .then(hiddenLayerBackdropModifier)
                        .then(hiddenSourceSizeModifier)
                        .then(commonTransform)
                        .then(hiddenBackdropModifier)
                        .then(selectionHighlightModifier)
                        .padding(horizontal = 4.dp)
                        .clip(Capsule()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(lensSourceAccentModifier),
                    ) {
                        FullTabContent(
                            items = stableItems,
                            selectedKey = selectedItem.key,
                            onSelected = stableOnSelected,
                            enabled = !compactMode,
                            alpha = tabContentAlpha,
                            selectedColorFilter = selectedIconColorFilterState,
                            hideSelectedItem = true,
                            expandedTabWidthPx = expandedTabWidthPx,
                            compactTabWidthPx = compactInnerWidthPx / tabItems.size,
                            compactProgress = compactState,
                        )
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
                            selectedIconColorFilter = hiddenSelectedIconColorFilterState,
                            contentColor = colors.content,
                        )
                    }
                }
            }
        }

        CompositionLocalProvider(LocalViewConfiguration provides navigationViewConfiguration) {
            Box(
                modifier = Modifier
                    .graphicsLayer(indicatorPositionLayerBlock)
                    .then(indicatorBackdropModifier)
                    .then(indicatorSizeModifier)
                    .then(indicatorHitBoundsModifier)
                    .then(interactiveHighlight.gestureModifier)
                    .then(dragAnimation.modifier)
                    .then(
                        if (compactMode) {
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
}

@Composable
private fun <T> FullTabContent(
    items: StableGlassTabItems<T>,
    selectedKey: T,
    onSelected: (T) -> Unit,
    enabled: Boolean,
    alpha: () -> Float,
    selectedColorFilter: State<ColorFilter>,
    expandedTabWidthPx: Float,
    compactTabWidthPx: Float,
    compactProgress: State<Float>,
    hideSelectedItem: Boolean = false,
    pressCorrection: GraphicsLayerScope.(Int) -> Unit = {},
) {
    val scale = LocalLiquidTabScale.current
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            items.values.forEachIndexed { index, item ->
                val selected = item.key == selectedKey
                Layout(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable(
                            enabled = enabled,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelected(item.key) },
                        ),
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(Capsule())
                                .graphicsLayer {
                                    this.alpha = if (selected && hideSelectedItem) 0f else alpha()
                                    val contentScale = scale()
                                    scaleX = contentScale
                                    scaleY = contentScale
                                    pressCorrection(index)
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
                    },
                ) { measurables, constraints ->
                    val visualWidth = lerp(expandedTabWidthPx, compactTabWidthPx, compactProgress.value)
                        .roundToInt().coerceAtLeast(1)
                    val placeable = measurables.single().measure(
                        androidx.compose.ui.unit.Constraints.fixed(visualWidth, constraints.maxHeight),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val offset = when (index) {
                            0 -> 0
                            items.values.lastIndex -> constraints.maxWidth - visualWidth
                            else -> (constraints.maxWidth - visualWidth) / 2
                        }
                        placeable.placeRelative(offset, 0)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val itemWidth = lerp(expandedTabWidthPx, compactTabWidthPx, compactProgress.value)
            .roundToInt().coerceIn(1, constraints.maxWidth.coerceAtLeast(1))
        val step = if (measurables.size > 1) {
            (constraints.maxWidth - itemWidth).toFloat() / (measurables.size - 1)
        } else 0f
        // Keep targets inside both the visual item and the neighboring center midpoints.
        val starts = IntArray(measurables.size)
        val placeables = measurables.mapIndexed { index, measurable ->
            val visualStart = (index * step).roundToInt()
            val slotStart = if (index == 0) 0 else {
                (itemWidth / 2f + (index - 0.5f) * step).roundToInt()
            }
            val slotEnd = if (index == measurables.lastIndex) constraints.maxWidth else {
                (itemWidth / 2f + (index + 0.5f) * step).roundToInt()
            }
            val hitStart = maxOf(slotStart, visualStart)
            val hitEnd = minOf(slotEnd, visualStart + itemWidth)
            starts[index] = hitStart
            measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(
                    (hitEnd - hitStart).coerceAtLeast(0), constraints.maxHeight,
                ),
            )
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(starts[index], 0)
            }
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
internal fun <T> BoxScope.MorphingSelectedNavigationItem(
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
    selectedIconColorFilter: State<ColorFilter>?,
    contentColor: Color,
    panelScale: () -> Float = { 1f },
) {
    val sharedTabScale = LocalLiquidTabScale.current
    val density = LocalDensity.current
    val expandedTabs = remember(fullWidthPx, horizontalInsetPx, density, itemCount) {
        navigationTabGeometry(
            contentWidthPx = fullWidthPx - 2f * horizontalInsetPx,
            itemCount = itemCount,
            minimumWidthPx = with(density) { ExpandedNavigationItemMinWidth.toPx() },
            protectedIconRadiusPx = with(density) {
                ExpandedNavigationIconSize.toPx() / 2f + NavigationItemIconClearance.toPx()
            },
        )
    }
    val selectedTabWidth = with(density) {
        lerp(
            expandedTabs.widthPx,
            (compactWidthPx - 2f * horizontalInsetPx) / itemCount,
            compactProgress.value.coerceIn(0f, 1f),
        )
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
                val expandedTabCenter = expandedTabs.center(selectedIndex.toFloat())
                val expandedCenter = if (isLtr) {
                    expandedTabCenter
                } else {
                    expandedContentWidth - expandedTabCenter
                }
                val compactCenter = compactContentWidth / 2f
                val targetCenter = lerp(expandedCenter, compactCenter, progress)
                val tabScale = panelScale()
                translationX = targetCenter - currentContentWidth / 2f +
                    navigationPanelCenterOffset(targetCenter, currentContentWidth / 2f, tabScale)
                // Scale the selected icon and label as one tab item. The shared tab scale owns
                // press deformation and the <= 0.74 morph; the late correction only reconciles
                // the expanded icon size with the compact control endpoint.
                val scale = sharedTabScale() * selectedContentLateScale() * tabScale
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(selectedTabWidth)
                .fillMaxHeight()
                .then(
                    if (selectedIconColorFilter == null) {
                        // Keep native icon/text rasterization before the outer morph
                        // scale, using one shared texture instead of two tint layers.
                        Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                    } else Modifier,
                ),
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
