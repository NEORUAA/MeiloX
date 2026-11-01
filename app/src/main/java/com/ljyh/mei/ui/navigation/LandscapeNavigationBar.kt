package com.ljyh.mei.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.swipeBackExcluded
import io.github.neoruaa.glasmio.glass.DampedDragAnimation
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.NativeControlLensStyle
import io.github.neoruaa.glasmio.glass.InteractiveHighlight
import io.github.neoruaa.glasmio.glass.NavigationGlassMaterial
import io.github.neoruaa.glasmio.glass.navigationGlassBackground
import io.github.neoruaa.glasmio.glass.rememberNavigationInnerShadows
import io.github.neoruaa.glasmio.glass.render.rememberGlassMaterialContext
import io.github.neoruaa.glasmio.glass.nativeControlLens
import io.github.neoruaa.glasmio.glass.render.backdrops.emptyBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCombinedBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.render.drawPlainBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * App-local expanded GlasmioNavigationBar adaptation without vertically stacked icons.
 * Keeps native platter puff, highlight, label portal and TabBarIndicator drag/release optics.
 * Content-sized cells and a 44dp surface replace the upstream fixed icon-and-label layout.
 */
@Composable
internal fun <T> LandscapeNavigationBar(
    items: List<GlasmioTabItem<T>>,
    selectedKey: T,
    onSelected: (T) -> Unit,
    onSearchClick: () -> Unit,
    searchKey: T,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val appearance = LocalGlassAppearance.current
    val backdrop = LocalGlassBackdrop.current
    val labelsBackdrop = rememberLayerBackdrop()
    val trackBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, trackBackdrop)
    val materialContext = rememberGlassMaterialContext()
    val innerShadows = rememberNavigationInnerShadows(appearance.usesBackdrop, renderScale = .5f)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val tabs = items + GlasmioTabItem(searchKey, stringResource(R.string.app_tab_search), SfSymbol.Search)
    val selectedIndex = tabs.indexOfFirst { it.key == selectedKey }
    val currentItems by rememberUpdatedState(tabs)
    val currentSelection by rememberUpdatedState(selectedIndex)
    val select by rememberUpdatedState<(T) -> Unit> { key ->
        if (key == searchKey) onSearchClick() else onSelected(key)
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = IosTypography.body.copy(fontWeight = FontWeight.Medium)
    val naturalWidths = items.map { item ->
        val width = maxOf(
            textMeasurer.measure(AnnotatedString(item.label), labelStyle.copy(fontWeight = FontWeight.Normal), maxLines = 1).size.width,
            textMeasurer.measure(AnnotatedString(item.label), labelStyle.copy(fontWeight = FontWeight.Medium), maxLines = 1).size.width,
        )
        with(density) { width.toDp() } + 32.dp
    } + 44.dp
    val naturalWidth = naturalWidths.fold(8.dp) { total, width -> total + width }

    BoxWithConstraints(
        modifier.width(naturalWidth).swipeBackExcluded().height(44.dp).selectableGroup()
            .onGloballyPositioned { coordinates = it },
    ) {
        val surfaceWidthPx = constraints.maxWidth.toFloat()
        val paddingPx = with(density) { 4.dp.toPx() }
        val totalWidthPx = (surfaceWidthPx - paddingPx * 2f).coerceAtLeast(1f)
        val widthRatio = totalWidthPx / with(density) { (naturalWidth - 8.dp).toPx() }
        val widthsPx = naturalWidths.map { with(density) { it.toPx() } * widthRatio }
        val lefts = widthsPx.runningFold(0f, Float::plus).dropLast(1)
        val centers = widthsPx.indices.map { lefts[it] + widthsPx[it] / 2f }
        fun interpolate(values: List<Float>, index: Float): Float {
            val position = index.coerceIn(0f, tabs.lastIndex.toFloat())
            val lower = position.toInt()
            val upper = (lower + 1).coerceAtMost(tabs.lastIndex)
            return values[lower] + (values[upper] - values[lower]) * (position - lower)
        }
        fun centerFor(index: Float) = interpolate(centers, index)
        fun indexFor(center: Float): Float {
            if (center <= centers.first()) return 0f
            for (upper in 1..tabs.lastIndex) {
                if (center <= centers[upper]) return upper - 1f +
                    (center - centers[upper - 1]) / (centers[upper] - centers[upper - 1])
            }
            return tabs.lastIndex.toFloat()
        }
        val currentCenterFor by rememberUpdatedState(::centerFor)
        val currentIndexFor by rememberUpdatedState(::indexFor)
        val dragOffset = remember { Animatable(0f) }
        val animation = remember(scope, tabs.size, isLtr) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.coerceAtLeast(0).toFloat(),
                valueRange = 0f..tabs.lastIndex.coerceAtLeast(1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                dragCoordinateSpace = { coordinates },
                lensStyle = NativeControlLensStyle.TabBarIndicator,
                onDragStarted = {},
                onDragStopped = {
                    val index = targetValue.roundToInt().coerceIn(0, currentItems.lastIndex)
                    if (index != currentSelection) select(currentItems[index].key)
                    animateToValue(index.toFloat())
                    scope.launch { dragOffset.animateTo(0f, spring(1f, 300f, .5f)) }
                },
                onDrag = { _, amount ->
                    updateValue(currentIndexFor(currentCenterFor(targetValue) + amount.x * if (isLtr) 1f else -1f)
                        .coerceIn(0f, currentItems.lastIndex.toFloat()))
                    scope.launch { dragOffset.snapTo(dragOffset.value + amount.x) }
                },
            )
        }
        LaunchedEffect(selectedIndex, animation) {
            if (selectedIndex >= 0 && animation.targetValue != selectedIndex.toFloat()) {
                animation.animateToValue(selectedIndex.toFloat(), animatePress = false)
            }
        }
        val onUserSelected: (T) -> Unit = remember(animation) {
            { key -> animation.press(); select(key); animation.release() }
        }
        fun indicatorLeft(): Float {
            val left = interpolate(lefts, animation.value)
            val width = interpolate(widthsPx, animation.value)
            return if (isLtr) left else totalWidthPx - left - width
        }

        fun panelOffset(): Float {
            val fraction = (dragOffset.value / surfaceWidthPx).coerceIn(-1f, 1f)
            return paddingPx * fraction.sign * EaseOut.transform(abs(fraction))
        }
        val highlight = remember(scope, animation, surfaceWidthPx, isLtr) {
            InteractiveHighlight(scope) { size, _ ->
                val center = currentCenterFor(animation.value)
                Offset((if (isLtr) paddingPx + center else surfaceWidthPx - paddingPx - center) + panelOffset(),
                    size.height / 2f)
            }
        }
        val puff = remember { Animatable(0f, .001f) }
        val longDimension = with(density) { surfaceWidthPx.toDp().value }.coerceAtLeast(44f)
        val springProgress = if (longDimension < 120f) 0f else ((longDimension - 44f) / 116f).coerceIn(0f, 1f)
        LaunchedEffect(highlight.isPressed, springProgress) {
            val pressed = highlight.isPressed
            val response = if (pressed) .262 + (.314 - .262) * springProgress
                else .4 + (.36 - .4) * springProgress
            val damping = if (pressed) .625f else .375f + .225f * springProgress
            puff.animateTo(if (pressed) 1f else 0f,
                spring(damping, (4.0 * PI * PI / (response * response)).toFloat(), .001f))
        }
        fun panelScale() = 1f + 16f * puff.value / longDimension
        val panelTransform = Modifier.graphicsLayer {
            translationX = panelOffset()
            scaleX = panelScale()
            scaleY = panelScale()
        }
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = panelOffset() }
            .then(if (appearance.usesBackdrop) Modifier.layerBackdrop(trackBackdrop) else Modifier)
            .navigationGlassBackground(
                backdrop = backdrop, shape = { Capsule() }, containerColor = colors.container,
                navigationMaterial = appearance.usesBackdrop, innerShadows = innerShadows,
                isDark = colors.isDark, appearance = appearance, materialContext = materialContext,
                material = NavigationGlassMaterial.RegularLarge,
                shadowAlpha = .5f,
                pressProgressState = animation.pressProgressState,
                layerBlock = { scaleX = panelScale(); scaleY = panelScale() },
            ).then(highlight.modifier))

        @Composable
        fun RowScope.Labels(interactive: Boolean) {
            tabs.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Box(
                    Modifier.weight(widthsPx[index]).fillMaxHeight().clip(Capsule())
                        .then(if (interactive) Modifier else Modifier.graphicsLayer {
                            val scale = 1f + .16f * animation.pressProgress
                            scaleX = scale
                            scaleY = scale
                        })
                        .then(if (interactive) Modifier
                            .semantics { contentDescription = item.contentDescription }
                            .selectable(selected, interactionSource = null, indication = null, role = Role.Tab,
                                onClick = { onUserSelected(item.key) }) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.key == searchKey) SfIcon(SfSymbol.Search, null, size = 20.dp,
                        tint = if (!interactive) colors.accent else if (selected)
                            androidx.compose.ui.graphics.lerp(colors.accent, colors.content, animation.pressProgress)
                            else colors.content)
                    else Text(item.label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (!interactive) colors.accent else if (selected)
                            androidx.compose.ui.graphics.lerp(colors.accent, colors.content, animation.pressProgress)
                            else colors.content,
                        fontWeight = if (interactive) FontWeight.Medium else FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxSize().then(highlight.gestureModifier).padding(4.dp).clip(Capsule())
            .then(panelTransform)) { Labels(interactive = true) }

        if (selectedIndex >= 0 && appearance.usesBackdrop) Row(
            Modifier.fillMaxSize().clearAndSetSemantics { }.alpha(0f)
                .layerBackdrop(labelsBackdrop).drawWithContent {
                    if (drawContext.graphicsLayer === labelsBackdrop.graphicsLayer) drawContent()
                }.then(panelTransform).padding(4.dp),
        ) { Labels(interactive = false) }

        if (selectedIndex >= 0) Box(
            Modifier.align(AbsoluteAlignment.TopLeft).graphicsLayer {
                val width = interpolate(widthsPx, animation.value)
                val left = paddingPx + indicatorLeft()
                translationX = left + (left + width / 2f - surfaceWidthPx / 2f) * (panelScale() - 1f) + panelOffset()
                translationY = 4.dp.toPx()
            }.height(36.dp).clearAndSetSemantics { }
                .layout { measurable, constraints ->
                    val width = interpolate(widthsPx, animation.value).roundToInt().coerceAtLeast(1)
                    val child = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(child.width, child.height) { child.place(0, 0) }
                }.testTag("LandscapeNavigationIndicator"),
        ) {
            Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                val progress = if (appearance.usesRefraction) animation.pressProgress else 0f
                val width = (constraints.maxWidth + 16.dp.toPx() * progress).roundToInt().coerceAtLeast(1)
                val height = (constraints.maxHeight + 16.dp.toPx() * progress).roundToInt().coerceAtLeast(1)
                val child = measurable.measure(Constraints.fixed(width, height))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    child.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
                }
            }.testTag("LandscapeNavigationLiftedLens")
                .drawPlainBackdrop(
                    backdrop = if (appearance.usesBackdrop) indicatorBackdrop else emptyBackdrop(),
                    shape = { Capsule() }, effects = {
                        if (appearance.usesBackdrop) nativeControlLens(
                            style = NativeControlLensStyle.TabBarIndicator,
                            liftProgress = if (appearance.usesRefraction) animation.pressProgress else 0f,
                            isDark = colors.isDark, liftedContent = labelsBackdrop,
                        )
                    }, layerBlock = {
                        val velocity = animation.velocity / 10f
                        scaleX = panelScale() / (1f - (velocity * .75f).coerceIn(-.2f, .2f))
                        scaleY = panelScale() * (1f - (velocity * .25f).coerceIn(-.2f, .2f))
                    }, onDrawSurface = {
                        if (!appearance.usesBackdrop) drawRect(colors.content.copy(alpha = .1f))
                    },
                ))
            Box(Modifier.fillMaxSize().then(highlight.gestureModifier).then(animation.modifier))
        }
    }
}
