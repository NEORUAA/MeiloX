package com.ljyh.mei.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.liquidglass.DampedDragAnimation
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import kotlinx.coroutines.flow.collectLatest

internal val LocalMergedGlassCards = staticCompositionLocalOf { false }

/** iOS 27 switch: 64 x 28 dp track and 40 x 24 dp elastic liquid thumb. */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    val colors = LocalGlassColors.current
    val light = !colors.isDark
    val accent = colors.accent
    val track = if (light) Color(0xFF787878).copy(alpha = 0.20f)
    else Color(0xFF787880).copy(alpha = 0.36f)
    val density = LocalDensity.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val travelPx = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val animation = remember(scope, enabled) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (!enabled) return@DampedDragAnimation
                fraction = if (didDrag) {
                    if (targetValue >= 0.5f) 1f else 0f
                } else if (checked) 0f else 1f
                didDrag = false
                onCheckedChange(fraction == 1f)
            },
            onDrag = { _, dragAmount ->
                if (!enabled) return@DampedDragAnimation
                didDrag = didDrag || dragAmount.x != 0f
                val delta = dragAmount.x / travelPx
                fraction = if (ltr) (fraction + delta).fastCoerceIn(0f, 1f)
                else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(animation) {
        snapshotFlow { fraction }.collectLatest(animation::updateValue)
    }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            animation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val transformedTrackBackdrop = rememberBackdrop(
        backdrop = trackBackdrop,
        onDraw = remember(animation) {
            { draw ->
                val p = animation.pressProgress
                scale(lerp(2f / 3f, 0.75f, p), lerp(0f, 0.75f, p)) { draw() }
            }
        },
    )
    val thumbBackdrop = rememberCombinedBackdrop(backdrop, transformedTrackBackdrop)
    val thumbGlassModifier = remember(thumbBackdrop, animation, enabled) {
        Modifier.drawBackdrop(
            backdrop = thumbBackdrop,
            shape = { Capsule() },
            effects = {
                val p = animation.pressProgress
                blur(8.dp.toPx() * (1f - p))
                lens(5.dp.toPx() * p, 10.dp.toPx() * p, chromaticAberration = true)
            },
            highlight = {
                Highlight.Ambient.copy(
                    width = Highlight.Ambient.width / 1.5f,
                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                    alpha = animation.pressProgress,
                )
            },
            shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
            innerShadow = {
                InnerShadow(
                    radius = 4.dp * animation.pressProgress,
                    alpha = animation.pressProgress,
                )
            },
            layerBlock = {
                scaleX = animation.scaleX
                scaleY = animation.scaleY
                val velocity = animation.velocity / 50f
                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                alpha = if (enabled) 1f else 0.45f
            },
            onDrawSurface = {
                drawRect(Color.White.copy(alpha = 1f - animation.pressProgress))
            },
        )
    }
    Box(
        modifier = modifier
            .size(width = 64.dp, height = 28.dp)
            .semantics { role = Role.Switch }
            .then(animation.modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind { drawRect(colorLerp(track, accent, animation.value)) }
                .size(width = 64.dp, height = 28.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2.dp.toPx()
                    translationX = if (ltr) lerp(padding, padding + travelPx, animation.value)
                    else lerp(-padding, -(padding + travelPx), animation.value)
                }
                .then(thumbGlassModifier)
                .size(width = 40.dp, height = 24.dp),
        )
    }
}

/** iOS 27 liquid slider, directly adapted from skill-liquid-glass/LiquidSlider.kt. */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    require(valueRange.start < valueRange.endInclusive)
    val colors = LocalGlassColors.current
    val light = !colors.isDark
    val accent = colors.accent
    val track = if (light) Color(0xFF787878).copy(alpha = 0.20f)
    else Color(0xFF787880).copy(alpha = 0.36f)
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .progressSemantics(value.fastCoerceIn(valueRange.start, valueRange.endInclusive), valueRange),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth
        val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val scope = rememberCoroutineScope()
        val animation = remember(scope, valueRange, enabled) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = value.fastCoerceIn(valueRange.start, valueRange.endInclusive),
                valueRange = valueRange,
                visibilityThreshold = (valueRange.endInclusive - valueRange.start) / 1000f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = { if (enabled) onValueChange(targetValue) },
                onDrag = { _, dragAmount ->
                    if (!enabled) return@DampedDragAnimation
                    val delta = (valueRange.endInclusive - valueRange.start) * dragAmount.x / trackWidth
                    onValueChange((if (ltr) targetValue + delta else targetValue - delta).coerceIn(valueRange))
                },
            )
        }
        LaunchedEffect(value) {
            if (animation.targetValue != value) animation.updateValue(value)
        }
        val transformedTrackBackdrop = rememberBackdrop(
            backdrop = trackBackdrop,
            onDraw = remember(animation) {
                { draw ->
                    val p = animation.pressProgress
                    scale(lerp(2f / 3f, 1f, p), lerp(0f, 1f, p)) { draw() }
                }
            },
        )
        val thumbBackdrop = rememberCombinedBackdrop(backdrop, transformedTrackBackdrop)
        val thumbGlassModifier = remember(thumbBackdrop, animation) {
            Modifier.drawBackdrop(
                backdrop = thumbBackdrop,
                shape = { Capsule() },
                effects = {
                    val p = animation.pressProgress
                    blur(8.dp.toPx() * (1f - p))
                    lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                },
                highlight = {
                    Highlight.Ambient.copy(
                        width = Highlight.Ambient.width / 1.5f,
                        blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                        alpha = animation.pressProgress,
                    )
                },
                shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                innerShadow = {
                    InnerShadow(
                        radius = 4.dp * animation.pressProgress,
                        alpha = animation.pressProgress,
                    )
                },
                layerBlock = {
                    scaleX = animation.scaleX
                    scaleY = animation.scaleY
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 1f - animation.pressProgress))
                },
            )
        }
        Box(Modifier.layerBackdrop(trackBackdrop).fillMaxWidth()) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(track)
                    .pointerInput(enabled, valueRange) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { position ->
                            val fraction = position.x / trackWidth
                            val target = (if (ltr) valueRange.start + (valueRange.endInclusive - valueRange.start) * fraction
                            else valueRange.endInclusive - (valueRange.endInclusive - valueRange.start) * fraction).coerceIn(valueRange)
                            animation.animateToValue(target)
                            onValueChange(target)
                        }
                    }
                    .height(6.dp)
                    .fillMaxWidth(),
            )
            Box(
                Modifier
                    .clip(Capsule())
                    .background(accent)
                    .height(6.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * animation.progress).fastRoundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    },
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = (-size.width / 2f + trackWidth * animation.progress)
                        .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (ltr) 1f else -1f
                    alpha = if (enabled) 1f else 0.45f
                }
                .then(animation.modifier)
                .then(thumbGlassModifier)
                .size(width = 40.dp, height = 24.dp),
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop = LocalGlassBackdrop.current,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val merged = LocalMergedGlassCards.current
    Box(
        modifier = modifier
            .then(
                if (merged) {
                    Modifier.drawBehind {
                        drawLine(
                            colors.separator,
                            Offset(16.dp.toPx(), 0f),
                            Offset(size.width - 16.dp.toPx(), 0f),
                            1.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                        .clip(ContinuousRoundedRectangle(LocalGlassDimensions.current.regularCornerRadius))
                        .background(colors.elevatedBackground)
                },
            )
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ) else Modifier,
            ),
        content = {
            CompositionLocalProvider(
                LocalContentColor provides colors.content,
                LocalGlassContentColor provides colors.content,
            ) {
                content()
            }
        },
    )
}

@Composable
fun GlassSheetSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalBlurBackdrop.current,
    content: @Composable BoxScope.() -> Unit,
) = IosSheetSurface(modifier = modifier, backdrop = backdrop, content = content)

@Composable
fun <T> GlassSegmentedControl(
    items: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    require(items.isNotEmpty())
    val colors = LocalGlassColors.current
    val isLight = !colors.isDark
    val selectedIndex = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val currentItems by rememberUpdatedState(items)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val trackColor = colors.segmentedControlBackground

    // Same three-layer structure as LiquidBottomTabs:
    // visible tabs -> invisible exported tabs with labels -> movable combined-backdrop lens.
    BoxWithConstraints(modifier.height(32.dp)) {
        val density = LocalDensity.current
        val contentWidthPx = (constraints.maxWidth - with(density) { 4.dp.roundToPx() })
            .coerceAtLeast(items.size)
            .toFloat()
        val tabWidthPx = contentWidthPx / items.size
        val trackPaddingPx = with(density) { 2.dp.toPx() }
        val animation = remember(scope, items.size) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..items.lastIndex.coerceAtLeast(1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 36f / 28f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, currentItems.lastIndex)
                    currentOnSelected(currentItems[target].first)
                    animateToValue(target.toFloat())
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, currentItems.lastIndex.toFloat()),
                    )
                },
            )
        }
        LaunchedEffect(selectedIndex) { animation.animateToValue(selectedIndex.toFloat()) }
        val interactiveHighlight = remember(scope, isLtr) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    Offset(
                        x = if (isLtr) {
                            trackPaddingPx + (animation.value + 0.5f) * tabWidthPx
                        } else {
                            size.width - trackPaddingPx - (animation.value + 0.5f) * tabWidthPx
                        },
                        y = size.height / 2f,
                    )
                },
            )
        }
        val hiddenGlassModifier = remember(backdrop, animation, trackColor) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    val press = animation.pressProgress
                    vibrancy()
                    blur(2.dp.toPx())
                    lens(
                        8.dp.toPx() * press,
                        16.dp.toPx() * press,
                        depthEffect = press > 0.01f,
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Default.copy(alpha = animation.pressProgress) },
                onDrawSurface = { drawRect(trackColor) },
            )
        }
        val indicatorGlassModifier = remember(indicatorBackdrop, animation) {
            Modifier.drawBackdrop(
                backdrop = indicatorBackdrop,
                shape = { Capsule() },
                effects = {
                    val press = animation.pressProgress
                    lens(
                        7.dp.toPx() * press,
                        18.dp.toPx() * press,
                        depthEffect = press > 0.01f,
                        chromaticAberration = true,
                    )
                },
                highlight = { Highlight.Default.copy(alpha = animation.pressProgress) },
                shadow = { Shadow(alpha = 0.72f * animation.pressProgress) },
                innerShadow = {
                    InnerShadow(
                        radius = 6.dp * animation.pressProgress,
                        alpha = 0.72f * animation.pressProgress,
                    )
                },
                layerBlock = {
                    scaleX = animation.scaleX
                    scaleY = animation.scaleY
                    val velocity = animation.velocity / 10f
                    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.18f, 0.18f)
                    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.14f, 0.14f)
                },
                onDrawSurface = {
                    val press = animation.pressProgress
                    // The selected white pill and its label are already exported by
                    // tabsBackdrop. A resting white overlay here would cover that sampled
                    // label instead of refracting it, so only add a subtle pressed sheen.
                    drawRect(Color.White.copy(alpha = 0.10f * press))
                },
            )
        }

        // 1. Visible gray track and labels. The exported duplicate mirrors its tap targets.
        Row(
            Modifier
                .fillMaxSize()
                .clip(Capsule())
                .background(trackColor)
                .then(interactiveHighlight.modifier)
                .padding(2.dp),
        ) {
            SegmentedTabContent(
                items = items,
                selected = selected,
                onSelected = onSelected,
                selectedBackground = Color.Transparent,
            )
        }

        // 2. Exact duplicate exported as a hidden sampling source. It includes the labels,
        // so the lens carries and refracts the selected tab content just like the nav bar.
        Row(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .clip(Capsule())
                .background(trackColor)
                .then(hiddenGlassModifier)
                .then(interactiveHighlight.modifier)
                .padding(2.dp),
        ) {
            SegmentedTabContent(
                items = items,
                selected = selected,
                onSelected = onSelected,
                selectedBackground = if (isLight) Color.White else Color(0xFF636366),
            )
        }

        // 3. The only drag surface. It sits over the selected cell, samples layer 2 and
        // becomes larger only while pressed; unselected cells remain tappable through layer 2.
        Box(
            Modifier
                .graphicsLayer {
                    val visualIndex = if (isLtr) animation.value else items.lastIndex - animation.value
                    translationX = 2.dp.toPx() + visualIndex * tabWidthPx
                    translationY = 2.dp.toPx()
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                }
                .then(interactiveHighlight.gestureModifier)
                .then(animation.modifier)
                .then(indicatorGlassModifier)
                .height(28.dp)
                .layout { measurable, constraints ->
                    val width = tabWidthPx.fastRoundToInt()
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = width, maxWidth = width),
                    )
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        )
    }
}

@Composable
private fun <T> androidx.compose.foundation.layout.RowScope.SegmentedTabContent(
    items: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    selectedBackground: Color,
) {
    val colors = LocalGlassColors.current
    items.forEach { (key, label) ->
        val isSelected = key == selected
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(Capsule())
                .background(if (isSelected) selectedBackground else Color.Transparent)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Tab,
                    onClick = { onSelected(key) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = IosTypography.caption,
                color = colors.content,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
