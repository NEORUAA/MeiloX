package com.ljyh.mei.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
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
    val appearance = LocalGlassAppearance.current
    val light = !colors.isDark
    val accent = colors.accent
    val track = (if (light) {
        Color(0xFF787878).copy(alpha = 0.20f)
    } else {
        Color(0xFF787880).copy(alpha = 0.36f)
    }).let {
        if (appearance.reduceTransparency) it.compositeOver(colors.elevatedBackground) else it
    }
    val density = LocalDensity.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val travelPx = with(density) { 20.dp.toPx() }
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val animation = remember(scope, enabled, travelPx, ltr) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (!currentEnabled) return@DampedDragAnimation
                fraction = if (didDrag) {
                    if (fraction >= 0.5f) 1f else 0f
                } else if (currentChecked) 0f else 1f
                didDrag = false
                updateValue(fraction)
                currentOnCheckedChange(fraction == 1f)
            },
            onDrag = { _, dragAmount ->
                if (!currentEnabled) return@DampedDragAnimation
                didDrag = didDrag || dragAmount.x != 0f
                val delta = dragAmount.x / travelPx
                fraction = if (ltr) (fraction + delta).fastCoerceIn(0f, 1f)
                else (fraction - delta).fastCoerceIn(0f, 1f)
                updateValue(fraction)
            },
            consumeGestures = true,
            onDragCancelled = {
                didDrag = false
                fraction = if (currentChecked) 1f else 0f
                updateValue(fraction)
            },
        )
    }
    LaunchedEffect(checked, enabled, animation) {
        didDrag = false
        val target = if (checked) 1f else 0f
        if (target != fraction || target != animation.targetValue) {
            fraction = target
            // Restored preferences and parent-row changes are not thumb presses.
            animation.animateToValue(target, animatePress = false)
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
    val thumbGlassModifier = remember(thumbBackdrop, animation, enabled, appearance) {
        Modifier.drawBackdrop(
            backdrop = if (appearance.usesBackdrop) thumbBackdrop else emptyBackdrop(),
            shape = { Capsule() },
            effects = {
                if (appearance.usesBackdrop) {
                    val p = animation.pressProgress
                    blur(8.dp.toPx() * if (appearance.usesRefraction) 1f - p else 1f)
                    if (appearance.usesRefraction) {
                        lens(5.dp.toPx() * p, 10.dp.toPx() * p, chromaticAberration = true)
                    }
                }
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
                val alpha = if (appearance.reduceTransparency) 1f else 1f - animation.pressProgress
                drawRect(Color.White.copy(alpha = alpha))
            },
        )
    }
    Box(
        modifier = modifier
            .size(width = 64.dp, height = 28.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (!enabled) disabled()
                onClick {
                    if (currentEnabled) currentOnCheckedChange(!currentChecked)
                    currentEnabled
                }
            }
            .then(if (enabled) animation.modifier else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .then(if (appearance.usesBackdrop) Modifier.layerBackdrop(trackBackdrop) else Modifier)
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

/**
 * iOS 27 liquid slider with immediate local drag feedback.
 * The caller owns [value]; use local preview state when persisting changes asynchronously.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    require(valueRange.start < valueRange.endInclusive)
    val colors = LocalGlassColors.current
    val appearance = LocalGlassAppearance.current
    val light = !colors.isDark
    val accent = colors.accent
    val track = (if (light) {
        Color(0xFF787878).copy(alpha = 0.20f)
    } else {
        Color(0xFF787880).copy(alpha = 0.36f)
    }).let {
        if (appearance.reduceTransparency) it.compositeOver(colors.elevatedBackground) else it
    }
    val trackBackdrop = rememberLayerBackdrop()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentValue by rememberUpdatedState(value.coerceIn(valueRange))
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    var dragValue by remember(valueRange, enabled) {
        mutableFloatStateOf(value.coerceIn(valueRange))
    }
    var dragging by remember(valueRange, enabled) { mutableStateOf(false) }
    val displayedValue = if (dragging) dragValue else currentValue
    val updateValue by rememberUpdatedState<(Float) -> Unit> { next ->
        val target = next.coerceIn(valueRange)
        val previous = if (dragging) dragValue else currentValue
        if (dragging) dragValue = target
        if (target != previous) currentOnValueChange(target)
    }
    val progress = {
        (displayedValue - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .offset(y = 6.dp)
            .progressSemantics(displayedValue, valueRange)
            .semantics {
                if (!enabled) disabled()
                setProgress { requested ->
                    if (!enabled || !requested.isFinite()) {
                        false
                    } else {
                        val target = requested.coerceIn(valueRange)
                        if (target == displayedValue) {
                            false
                        } else {
                            updateValue(target)
                            currentOnValueChangeFinished?.invoke()
                            true
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth
        val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val currentTrackWidth by rememberUpdatedState(trackWidth)
        val currentLtr by rememberUpdatedState(ltr)
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
                onDragStopped = {},
                onDrag = { _, _ -> },
            )
        }
        val updateFromPosition by rememberUpdatedState<(Float) -> Unit> { x ->
            val fraction = (x / currentTrackWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
            updateValue(
                if (currentLtr) lerp(valueRange.start, valueRange.endInclusive, fraction)
                else lerp(valueRange.endInclusive, valueRange.start, fraction),
            )
        }
        val finishGesture by rememberUpdatedState {
            if (currentEnabled) currentOnValueChangeFinished?.invoke()
            dragging = false
            animation.release()
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
        val thumbGlassModifier = remember(thumbBackdrop, animation, appearance) {
            Modifier.drawBackdrop(
                backdrop = if (appearance.usesBackdrop) thumbBackdrop else emptyBackdrop(),
                shape = { Capsule() },
                effects = {
                    if (appearance.usesBackdrop) {
                        val p = animation.pressProgress
                        blur(8.dp.toPx() * if (appearance.usesRefraction) 1f - p else 1f)
                        if (appearance.usesRefraction) {
                            lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                        }
                    }
                },
                highlight = {
                    Highlight.Ambient.copy(
                        width = Highlight.Ambient.width / 1.5f,
                        blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                        alpha = animation.pressProgress,
                    )
                },
                shadow = { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.15f)) },
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
                    val alpha = if (appearance.reduceTransparency) 1f else 1f - animation.pressProgress
                    drawRect(Color.White.copy(alpha = alpha))
                },
            )
        }
        Box(
            Modifier.then(if (appearance.usesBackdrop) Modifier.layerBackdrop(trackBackdrop) else Modifier)
                .fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(track)
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
                        val width = (constraints.maxWidth * progress()).fastRoundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    },
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    // Position follows the pointer directly; springs only deform the glass.
                    translationX = (-size.width / 2f + trackWidth * progress())
                        .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (ltr) 1f else -1f
                    alpha = if (enabled) 1f else 0.45f
                }
                .then(thumbGlassModifier)
                .size(width = 40.dp, height = 24.dp),
        )
        // Use a stationary input surface. Measuring deltas in the translated thumb's
        // coordinates subtracts its own movement and makes dragging feel damped.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(enabled, valueRange, animation) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            animation.press()
                            tryAwaitRelease()
                            if (!dragging) animation.release()
                        },
                        onTap = { position ->
                            if (currentEnabled) {
                                updateFromPosition(position.x)
                                currentOnValueChangeFinished?.invoke()
                            }
                        },
                    )
                }
                .pointerInput(enabled, valueRange, animation) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { position ->
                            if (currentEnabled) {
                                dragValue = currentValue
                                dragging = true
                                animation.press()
                                updateFromPosition(position.x)
                            }
                        },
                        onDragEnd = { finishGesture() },
                        onDragCancel = { finishGesture() },
                        onHorizontalDrag = { change, _ ->
                            if (currentEnabled) updateFromPosition(change.position.x)
                        },
                    )
                },
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
    positionAnimation: Animatable<Float, AnimationVector1D>? = null,
) {
    require(items.isNotEmpty())
    val colors = LocalGlassColors.current
    val appearance = LocalGlassAppearance.current
    val isLight = !colors.isDark
    val selectedIndex = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentItems by rememberUpdatedState(items)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val trackColor = colors.segmentedControlBackground.let {
        if (appearance.reduceTransparency) it.compositeOver(colors.elevatedBackground) else it
    }

    // Same three-layer structure as LiquidBottomTabs:
    // visible tabs -> invisible exported tabs with labels -> movable combined-backdrop lens.
    val dragCoordinateSpace = remember { mutableStateOf<LayoutCoordinates?>(null) }
    BoxWithConstraints(
        modifier.height(32.dp).onGloballyPositioned { dragCoordinateSpace.value = it },
    ) {
        val density = LocalDensity.current
        val contentWidthPx = (constraints.maxWidth - with(density) { 4.dp.roundToPx() })
            .coerceAtLeast(items.size)
            .toFloat()
        val tabWidthPx = contentWidthPx / items.size
        val currentTabWidthPx by rememberUpdatedState(tabWidthPx)
        val trackPaddingPx = with(density) { 2.dp.toPx() }
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = IosTypography.caption
        val labelLayouts = remember(textMeasurer, items, selectedIndex, labelStyle, colors.content, tabWidthPx) {
            items.mapIndexed { index, item ->
                textMeasurer.measure(
                    text = AnnotatedString(item.second),
                    style = labelStyle.copy(
                        color = colors.content,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    constraints = Constraints(maxWidth = tabWidthPx.fastRoundToInt().coerceAtLeast(1)),
                )
            }
        }
        val interactiveHighlight = remember(scope, isLtr) { InteractiveHighlight(scope) }
        val animation = remember(scope, items.size, isLtr, positionAnimation) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..items.lastIndex.coerceAtLeast(1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = SegmentedIndicatorPressedScale,
                dragCoordinateSpace = { dragCoordinateSpace.value },
                sharedValueAnimation = positionAnimation,
                horizontalDragOnly = true,
                onDragStarted = { interactiveHighlight.startPress(it) },
                onDragStopped = {
                    interactiveHighlight.endPress()
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, currentItems.lastIndex)
                    currentOnSelected(currentItems[target].first)
                    animateToValue(target.toFloat())
                },
                onDragCancelled = {
                    interactiveHighlight.endPress()
                    animateToValue(currentSelectedIndex.toFloat(), animatePress = false)
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / currentTabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, currentItems.lastIndex.toFloat()),
                    )
                },
            )
        }
        LaunchedEffect(selectedIndex, animation) { animation.animateToValue(selectedIndex.toFloat()) }
        val segmentedHighlightModifier = remember(interactiveHighlight, animation, isLtr, trackPaddingPx) {
            interactiveHighlight.highlightModifier(
                positionProvider = { size ->
                    Offset(
                        x = if (isLtr) {
                            trackPaddingPx + (animation.value + 0.5f) * currentTabWidthPx
                        } else {
                            size.width - trackPaddingPx - (animation.value + 0.5f) * currentTabWidthPx
                        },
                        y = size.height / 2f,
                    )
                },
                horizontalRadiusProvider = { currentTabWidthPx * 0.65f },
            )
        }
        val hiddenGlassModifier = remember(backdrop, animation, trackColor, appearance) {
            Modifier.drawBackdrop(
                backdrop = if (appearance.usesBackdrop) backdrop else emptyBackdrop(),
                shape = { Capsule() },
                effects = {
                    if (appearance.usesBackdrop) {
                        val press = animation.pressProgress
                        vibrancy()
                        blur(2.dp.toPx())
                        if (appearance.usesRefraction) {
                            lens(
                                8.dp.toPx() * press,
                                16.dp.toPx() * press,
                                depthEffect = press > 0.01f,
                                chromaticAberration = true,
                            )
                        }
                    }
                },
                highlight = { Highlight.Default.copy(alpha = animation.pressProgress) },
                onDrawSurface = { drawRect(trackColor) },
            )
        }
        val indicatorGlassModifier = remember(indicatorBackdrop, animation, appearance, isLight, labelLayouts) {
            if (!appearance.usesBackdrop) Modifier else Modifier.drawBackdrop(
                backdrop = indicatorBackdrop,
                shape = { Capsule() },
                effects = {
                    if (appearance.usesRefraction) {
                        val press = animation.pressProgress
                        lens(
                            SegmentedIndicatorRefractionHeight.toPx() * press,
                            SegmentedIndicatorRefractionAmount.toPx() * press,
                            chromaticAberration = true,
                        )
                    }
                },
                highlight = { Highlight.Default.copy(alpha = animation.pressProgress) },
                shadow = { Shadow(alpha = 0.72f * animation.pressProgress) },
                innerShadow = {
                    InnerShadow(
                        radius = 6.dp * animation.pressProgress,
                        alpha = 0.72f * animation.pressProgress,
                    )
                },
                onDrawSurface = {
                    val press = animation.pressProgress
                    // The resting fill belongs to this surface, never to its sampling source.
                    drawRect((if (isLight) Color.White else Color(0xFF636366)).copy(alpha = 1f - press))
                    drawRect(Color.White.copy(alpha = 0.10f * press))
                    // Restore labels over the resting fill at their fixed track positions.
                    // As the fill fades, the source's refracted labels take over.
                    labelLayouts.forEachIndexed { index, label ->
                        drawText(
                            textLayoutResult = label,
                            topLeft = Offset(
                                size.width / 2f + (index - animation.value) * currentTabWidthPx *
                                    (if (isLtr) 1f else -1f) - label.size.width / 2f,
                                (size.height - label.size.height) / 2f,
                            ),
                            alpha = 1f - press,
                        )
                    }
                },
            )
        }

        // 1. Visible gray track and labels. The exported duplicate mirrors its tap targets.
        Row(
            Modifier
                .fillMaxSize()
                .clip(Capsule())
                .background(trackColor)
                .drawBehind {
                    if (appearance.reduceTransparency) {
                        val visualIndex = if (isLtr) animation.value else items.lastIndex - animation.value
                        val pillHeight = 28.dp.toPx()
                        drawRoundRect(
                            color = if (isLight) Color.White else Color(0xFF636366),
                            topLeft = Offset(trackPaddingPx + visualIndex * tabWidthPx, trackPaddingPx),
                            size = Size(tabWidthPx.fastRoundToInt().toFloat(), pillHeight),
                            cornerRadius = CornerRadius(pillHeight / 2f),
                        )
                    }
                }
                .then(segmentedHighlightModifier)
                .padding(2.dp),
        ) {
            SegmentedTabContent(
                items = items,
                selected = selected,
                onSelected = onSelected,
            )
        }

        // 2. Keep the unscaled source at the inner height so the refractive band can
        // cross its edge. Labels stay aligned with the visible row's 2dp padding.
        if (appearance.usesBackdrop) Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(SegmentedIndicatorHeight)
                .clearAndSetSemantics { }
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .clip(Capsule())
                .background(trackColor)
                .then(hiddenGlassModifier)
                .then(segmentedHighlightModifier)
                .padding(horizontal = 2.dp),
        ) {
            SegmentedTabContent(
                items = items,
                selected = selected,
                onSelected = onSelected,
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
                .then(animation.modifier)
                .height(SegmentedIndicatorHeight)
                .layout { measurable, constraints ->
                    val width = tabWidthPx.fastRoundToInt()
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = width, maxWidth = width),
                    )
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val restingSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                        val visualSize = if (appearance.usesBackdrop) {
                            segmentedIndicatorSize(
                                restingSize, animation.scaleX, animation.scaleY,
                                animation.velocity / 10f, SegmentedIndicatorHorizontalExpansion.toPx(),
                            )
                        } else restingSize
                        val width = visualSize.width.fastRoundToInt().coerceAtLeast(1)
                        val height = visualSize.height.fastRoundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
                        }
                    }
                    .then(indicatorGlassModifier),
            )
        }
    }
}

@Composable
private fun <T> androidx.compose.foundation.layout.RowScope.SegmentedTabContent(
    items: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    val colors = LocalGlassColors.current
    items.forEach { (key, label) ->
        val isSelected = key == selected
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(Capsule())
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
