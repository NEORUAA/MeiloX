package com.ljyh.mei.ui.component.sheet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.glass.GlassPressHighlight
import com.ljyh.mei.ui.glass.clipGlassShape
import com.ljyh.mei.ui.glass.rememberGlassMorphRenderer
import kotlin.math.roundToInt

internal val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetLayers?> { null }

@Stable
internal class PlayerSheetLayers(
    val state: BottomSheetState,
    val miniHost: GraphicsLayer,
    val miniContent: GraphicsLayer,
    val fullContent: GraphicsLayer,
    val artwork: GraphicsLayer,
) {
    var hostBounds by mutableStateOf(Rect.Zero)
        private set
    var miniBounds by mutableStateOf(Rect.Zero)
    var miniContentBounds by mutableStateOf(Rect.Zero)
    var miniArtworkBounds by mutableStateOf(Rect.Zero)
    var fullArtworkBounds by mutableStateOf(Rect.Zero)
    var artworkCornerRadius by mutableStateOf(12.dp)
    var sharedArtworkEnabled by mutableStateOf(true)
    var circularArtwork by mutableStateOf(false)
    var artworkRecorded by mutableStateOf(false)
    var artworkOverlayMounted by mutableStateOf(false)
    var miniLayoutCoordinates: LayoutCoordinates? = null
    private var frozenMiniContent: Rect? = null
    private var frozenMiniArtwork: Rect? = null
    private var frozenContainer: Rect? = null
    private var miniPressedBounds = Rect.Zero
    private var openingPressedContainer: Rect? = null
    private val pressRelease = PlayerPressRelease()
    private val pressFraction get() = pressRelease.fraction(state.progress)

    private var miniPressHighlight = GlassPressHighlight()
    val transitionPressHighlight get() = miniPressHighlight.copy(
        progress = miniPressHighlight.progress * pressFraction * playerPressHighlightAlpha(state.progress),
    )

    fun updateMiniPressHighlight(bounds: Rect, highlight: GlassPressHighlight) {
        if (!state.isTransitioning) {
            miniPressedBounds = bounds
            miniPressHighlight = highlight
        }
    }

    var miniContentCoordinates: LayoutCoordinates? = null
    var miniArtworkCoordinates: LayoutCoordinates? = null
    val sourceMiniContent get() = openingBounds(
        frozenMiniContent ?: miniLayoutBounds(miniContentCoordinates) ?: miniContentBounds,
    )
    val miniScaleX get() = sourceContainer.width / (frozenContainer ?: miniBounds).width.coerceAtLeast(1f)
    val miniScaleY get() = sourceContainer.height / (frozenContainer ?: miniBounds).height.coerceAtLeast(1f)
    private var frozenTargetArtwork: Rect? = null
    private val recordedTransitionLayers = mutableSetOf<GraphicsLayer>()
    private var transitionCaptureActive = false

    val sourceContainer get() = openingBounds(frozenContainer ?: miniBounds)
    val sourceArtwork get() = openingBounds(
        frozenMiniArtwork ?: miniLayoutBounds(miniArtworkCoordinates) ?: miniArtworkBounds,
    )
    val targetArtwork get() = frozenTargetArtwork ?: fullArtworkBounds
    val drawsArtworkOverlay get() = artworkOverlayMounted && canMountArtworkOverlay
    val canMountArtworkOverlay get() = sharedArtworkEnabled && state.isTransitioning &&
        sourceArtwork.isUsable() && targetArtwork.isUsable() && hostBounds.isUsable()
    val canDrawArtworkOverlay get() = canMountArtworkOverlay && artworkRecorded

    private fun openingBounds(layoutBounds: Rect): Rect {
        val layout = frozenContainer ?: return layoutBounds
        val pressed = openingPressedContainer ?: return layoutBounds
        // Keep the raw return endpoint intact. Only the opening leg inherits the last
        // visible press transform. A reversal releases it continuously back to raw bounds.
        val visual = playerUntransformRect(layoutBounds, layout, pressed)
        return playerContainerRect(layoutBounds, visual, pressFraction)
    }

    // Both coordinates are inside the same press-transformed glass layer. Relative
    // coordinates cancel that transform; layout sizes never include its scale.
    fun miniLayoutBounds(child: LayoutCoordinates?): Rect? {
        val layout = miniLayoutCoordinates ?: return null
        if (!layout.isAttached || child == null || !child.isAttached) return null
        val offset = layout.localPositionOf(child, Offset.Zero)
        return Rect(miniBounds.topLeft + offset, Size(child.size.width.toFloat(), child.size.height.toFloat()))
    }

    fun updateHost(bounds: Rect) {
        if (bounds == hostBounds) return
        hostBounds = bounds
        frozenContainer = null
        openingPressedContainer = null
        frozenTargetArtwork = null
        frozenMiniContent = null
        frozenMiniArtwork = null
    }

    fun updateFrozenBounds() {
        if (!state.isTransitioning) {
            transitionCaptureActive = false
            recordedTransitionLayers.clear()
            artworkRecorded = false
            if (state.isExpanded) {
                miniPressHighlight = GlassPressHighlight()
                miniPressedBounds = Rect.Zero
                openingPressedContainer = null
            }
            frozenTargetArtwork = null
            // Keep the original unpressed endpoints through the expanded state. Hidden
            // mini-player layout/press updates must not redefine the collapse destination.
            if (state.isCollapsed || state.isDismissed) {
                frozenContainer = null
                openingPressedContainer = null
                frozenMiniContent = null
                frozenMiniArtwork = null
            }
            pressRelease.reset()
        } else {
            if (!transitionCaptureActive) {
                transitionCaptureActive = true
                recordedTransitionLayers.clear()
                artworkRecorded = false
            }
            pressRelease.update(state.progress)
            if (frozenContainer == null && miniBounds.isUsable()) {
                frozenMiniContent = miniLayoutBounds(miniContentCoordinates) ?: miniContentBounds
                frozenMiniArtwork = miniLayoutBounds(miniArtworkCoordinates) ?: miniArtworkBounds
                frozenContainer = miniBounds
                // A collapse after reaching the expanded anchor already has raw endpoints,
                // so this capture runs only when starting a new opening from the mini player.
                openingPressedContainer = miniPressedBounds.takeIf { it.isUsable() }
                    ?.translate(miniBounds.topLeft)
            }
            if (frozenTargetArtwork == null && fullArtworkBounds.isUsable()) {
                frozenTargetArtwork = fullArtworkBounds
            }
        }
    }

    fun shouldCapture(layer: GraphicsLayer): Boolean =
        state.isTransitioning && layer !in recordedTransitionLayers

    fun updateTransitionFrame() {
        pressRelease.update(state.progress)
    }

    fun markCaptured(layer: GraphicsLayer) {
        recordedTransitionLayers += layer
        if (layer === artwork && !artworkRecorded) {
            artworkRecorded = true
            if (!artworkOverlayMounted) {
                // The first expansion may discover the target artwork bounds during layout.
                // Refresh only the mini snapshot once so it no longer contains a ghost cover.
                recordedTransitionLayers -= miniHost
                recordedTransitionLayers -= miniContent
            }
        }
    }
}

/** Root-space child bounds already contain the Backdrop graphics-layer transform. */
private fun LayoutCoordinates?.visualBounds(): Rect? {
    if (this == null || !isAttached) return null
    return Rect(localToRoot(Offset.Zero), localToRoot(Offset(size.width.toFloat(), size.height.toFloat())))
}

@Composable
internal fun rememberPlayerSheetLayers(state: BottomSheetState): PlayerSheetLayers {
    val miniHost = rememberGraphicsLayer()
    val mini = rememberGraphicsLayer()
    val full = rememberGraphicsLayer()
    val artwork = rememberGraphicsLayer()
    return remember(state, miniHost, mini, full, artwork) {
        PlayerSheetLayers(state, miniHost, mini, full, artwork)
    }
}

/** Always draw live at rest. Recording is only a source for the shared transition. */
internal fun Modifier.recordPlayerContent(
    layers: PlayerSheetLayers,
    layer: GraphicsLayer,
    drawInPlace: () -> Boolean,
): Modifier = drawWithContent {
    if (layers.shouldCapture(layer)) {
        layer.record { this@drawWithContent.drawContent() }
        layers.markCaptured(layer)
    }
    if (drawInPlace()) drawContent()
}

@Composable
internal fun Modifier.playerArtwork(
    cornerRadius: Dp = 12.dp,
    circle: Boolean = false,
    shared: Boolean = true,
): Modifier {
    val layers = LocalPlayerSheet.current ?: return this
    SideEffect {
        layers.sharedArtworkEnabled = shared
        layers.artworkCornerRadius = cornerRadius
        layers.circularArtwork = circle
    }
    DisposableEffect(layers) {
        onDispose {
            layers.fullArtworkBounds = Rect.Zero
            layers.artworkRecorded = false
        }
    }
    return onGloballyPositioned {
        // Preserve the full size and offscreen position of a paged-out cover.
        layers.fullArtworkBounds = it.visualBounds() ?: Rect.Zero
    }
        .drawWithContent {
            if (layers.shouldCapture(layers.artwork)) {
                layers.artwork.record { this@drawWithContent.drawContent() }
                layers.markCaptured(layers.artwork)
            }
            // Shared artwork only appears at its measured overlay position during motion.
            if (!layers.state.isTransitioning || !shared) drawContent()
        }
}

private fun Modifier.sheetGestures(
    state: BottomSheetState,
    onHorizontalSwipe: ((HorizontalSwipeDirection) -> Unit)?,
): Modifier = pointerInput(state, onHorizontalSwipe) {
    if (onHorizontalSwipe != null) {
        val tracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onHorizontalDrag = { change, _ -> tracker.addPointerInputChange(change) },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().x
                if (velocity > 500f) onHorizontalSwipe(HorizontalSwipeDirection.Right)
                if (velocity < -500f) onHorizontalSwipe(HorizontalSwipeDirection.Left)
            },
        )
    }
}.pointerInput(state) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            state.beginDrag()
        },
        onVerticalDrag = { change, amount ->
            tracker.addPointerInputChange(change)
            state.dispatchRawDelta(amount)
        },
        onDragCancel = state::cancelDrag,
        onDragEnd = { state.performFling(-tracker.calculateVelocity().y, null) },
    )
}

/** Clip hidden controls' hit testing as well as their drawing; consuming a down is too late
 * to pass it to a sibling page underneath the player. Existing drag streams stay attached.
 */
@Composable
private fun Modifier.clipPlayerRecordingHost(
    layers: PlayerSheetLayers,
    expandedCornerRadius: Float,
): Modifier {
    val origin = remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin.value = it.positionInRoot() }
        .graphicsLayer {
            clip = layers.state.isTransitioning
            if (clip) {
                val progress = layers.state.progress
                val bounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, progress)
                    .translate(-origin.value)
                val radius = playerContainerCornerRadius(
                    layers.sourceContainer.height / 2f, expandedCornerRadius, progress,
                )
                shape = PlayerHitShape(bounds, radius)
            }
        }
}

private class PlayerHitShape(private val bounds: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (bounds.isEmpty) return Outline.Rectangle(Rect.Zero)
        val outline = ContinuousRoundedRectangle(with(density) { radius.toDp() })
            .createOutline(bounds.size, layoutDirection, density)
        return Outline.Generic(Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(bounds.topLeft)
        })
    }
}

/** The native underlay keeps its window hole; only its canvas clip is split into small tiles. */
private fun Modifier.clipPlayerSurface(
    layers: PlayerSheetLayers,
    expandedCornerRadius: Float,
): Modifier = graphicsLayer {
    // Canvas clipping alone leaves the full-screen AndroidView in the hit-test tree.
    // A rectangular outline rejects touches in the exposed page without reintroducing
    // the full-screen path mask. The tiled draw clip below keeps the exact corners.
    clip = layers.state.isTransitioning
    if (clip) {
        val bounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, layers.state.progress)
            .translate(-layers.hostBounds.topLeft)
        shape = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                Outline.Rectangle(bounds)
        }
    }
}.drawWithContent {
    if (!layers.state.isTransitioning) {
        drawContent()
    } else {
        val progress = layers.state.progress
        val bounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, progress)
            .translate(-layers.hostBounds.topLeft)
        val radius = playerContainerCornerRadius(
            layers.sourceContainer.height / 2f, expandedCornerRadius, progress,
        )
        val shape = ContinuousRoundedRectangle(radius.toDp())
        val outline = PlayerHitShape(bounds, radius).createOutline(size, layoutDirection, this)
        val path = (outline as? Outline.Generic)?.path
        if (path != null) {
            clipGlassShape(bounds, radius, shape, path) { this@drawWithContent.drawContent() }
        }
    }
}

@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    collapsedDragOffset: () -> Dp = { 0.dp },
    collapsedDragHeight: Dp = 0.dp,
    transitionBackdrop: Backdrop,
    onDismiss: (() -> Unit)? = null,
    onHorizontalSwipe: ((HorizontalSwipeDirection) -> Unit)? = null,
    backgroundContent: @Composable BoxScope.() -> Unit = {},
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val layers = checkNotNull(LocalPlayerSheet.current)
    val active by remember(state) { derivedStateOf { state.isTransitioning } }
    val expanded by remember(state) { derivedStateOf { state.isExpanded } }
    val collapsed by remember(state) { derivedStateOf { state.isCollapsed } }
    val dismissed by remember(state) { derivedStateOf { state.isDismissed } }
    val transitionGeometryReady by remember(layers) {
        derivedStateOf { layers.hostBounds.isUsable() && layers.sourceContainer.isUsable() }
    }
    val transitionGlassRenderer = rememberGlassMorphRenderer(
        backdrop = transitionBackdrop,
        active = active && transitionGeometryReady,
        // Only the blurred page sample is reduced; all moving edges and content stay native.
        renderScale = 1f / 3f,
    )
    val expandedCornerRadius = rememberPlayerScreenCornerRadius(layers.hostBounds)
    SideEffect { layers.updateFrozenBounds() }
    BackHandler(enabled = !collapsed && !dismissed, onBack = state::collapseSoft)

    Box(modifier.fillMaxSize().onGloballyPositioned { layers.updateHost(it.boundsInRoot()) }) {
        if (!dismissed || onDismiss == null) {
            Box(
                Modifier.fillMaxWidth()
                    .height(if (collapsedDragHeight > 0.dp) collapsedDragHeight else state.collapsedBound)
                    .offset {
                        val anchor = if (state.progress == 0f && !state.isDragging) state.value else state.collapsedBound
                        IntOffset(0, (state.expandedBound - anchor + collapsedDragOffset()).roundToPx())
                    }
                    .clipPlayerRecordingHost(layers, expandedCornerRadius)
                    .sheetGestures(state, onHorizontalSwipe)
                    .then(if (active || expanded) Modifier.clearAndSetSemantics { } else Modifier)
                    .recordPlayerContent(layers, layers.miniHost) { !active && !expanded },
                content = collapsedContent,
            )
        }
        // Draw glass below the real Surface. The Surface's alpha reveals this glass during
        // motion; drawing glass above it would tint an already opaque player background.
        if (active && transitionGeometryReady) {
            PlayerTransitionGlass(
                layers = layers,
                renderer = transitionGlassRenderer,
                expandedCornerRadius = expandedCornerRadius,
            )
        }
        // Retain the native Surface across the collapsed anchor. Recreating it on every
        // opening also recreates EGL, uploads the album and allocates the mesh framebuffer.
        // FluidBackground stops rendering/capture and makes the Surface transparent at rest.
        if (state.hasBeenShown) {
            Box(
                Modifier.fillMaxSize()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            // An invisible AndroidView still participates in hit testing.
                            if (active || expanded) placeable.place(0, 0)
                        }
                    }
                    .clipPlayerSurface(layers, expandedCornerRadius)
                    .clearAndSetSemantics { },
            ) {
                backgroundContent()
            }
        }
        run {
            val visible = active || expanded
            // Keep pager/lyrics composition, but unplace the hidden host so it has no hit targets.
            Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (visible) placeable.place(0, 0)
                }
            }) {
                BoxWithConstraints(
                    Modifier.fillMaxSize()
                        .clipPlayerRecordingHost(layers, expandedCornerRadius)
                        .sheetGestures(state, onHorizontalSwipe)
                        .then(if (!expanded) Modifier.clearAndSetSemantics { } else Modifier)
                        .recordPlayerContent(layers, layers.fullContent) { expanded },
                    content = content,
                )
            }
        }
        if (active && transitionGeometryReady) {
            Canvas(
                Modifier.fillMaxSize(),
            ) {
                val p = state.progress
                val globalBounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, p)
                val bounds = globalBounds.translate(-layers.hostBounds.topLeft)
                val radius = playerContainerCornerRadius(
                    layers.sourceContainer.height / 2f, expandedCornerRadius, p,
                )
                val shellShape = ContinuousRoundedRectangle(radius.toDp())
                val outline = shellShape.createOutline(bounds.size, layoutDirection, this)
                val path = Path().apply {
                    when (outline) {
                        is Outline.Rectangle -> addRect(outline.rect)
                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                        is Outline.Generic -> addPath(outline.path)
                    }
                    translate(bounds.topLeft)
                }
                clipGlassShape(bounds, radius, shellShape, path) {
                    val scale = bounds.width / size.width.coerceAtLeast(1f)
                    if (layers.miniContent.size.width > 0) {
                        layers.miniContent.alpha = playerMiniContentAlpha(p)
                        val source = layers.sourceContainer
                        val offset = Offset(
                            layers.sourceMiniContent.left - layers.hostBounds.left,
                            layers.sourceMiniContent.top - source.top + bounds.top,
                        )
                        withTransform({
                            translate(offset.x + globalBounds.left - source.left, offset.y)
                            scale(layers.miniScaleX, layers.miniScaleY, Offset.Zero)
                        }) { drawLayer(layers.miniContent) }
                    }

                    if (layers.fullContent.size.width > 0) {
                        layers.fullContent.alpha = playerContentAlpha(p)
                        withTransform({
                            translate(bounds.left, bounds.top)
                            scale(scale, scale, Offset.Zero)
                        }) { drawLayer(layers.fullContent) }
                    }
                }
            }
        }
        if (active) {
            // Only the empty input surface is remeasured. Its bounds and continuous corners
            // follow the drawn shell; the full-sized snapshot/GL hosts keep their dimensions.
            Box(Modifier
                .layout { measurable, constraints ->
                    val bounds = playerContainerRect(layers.sourceContainer, layers.hostBounds, state.progress)
                        .translate(-layers.hostBounds.topLeft)
                    val placeable = measurable.measure(Constraints.fixed(
                        bounds.width.roundToInt().coerceAtLeast(0),
                        bounds.height.roundToInt().coerceAtLeast(0),
                    ))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                    }
                }
                .graphicsLayer {
                    clip = true
                    shape = ContinuousRoundedRectangle(playerContainerCornerRadius(
                        layers.sourceContainer.height / 2f, expandedCornerRadius, state.progress,
                    ).toDp())
                }
                .sheetGestures(state, null)
                .clearAndSetSemantics { })
        }
    }
}

@Composable
internal fun PlayerSheetArtworkOverlay(layers: PlayerSheetLayers) {
    val active by remember(layers) { derivedStateOf { layers.canMountArtworkOverlay } }
    if (!active) return
    DisposableEffect(layers) {
        layers.artworkOverlayMounted = true
        onDispose { layers.artworkOverlayMounted = false }
    }
    val density = LocalDensity.current
    val sourceRadius = with(density) { com.ljyh.mei.constants.ThumbnailCornerRadius.toPx() }
    val target = layers.targetArtwork.translate(-layers.hostBounds.topLeft)
    Box(
        Modifier.offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
            .size(with(density) { target.width.toDp() }, with(density) { target.height.toDp() })
            .clearAndSetSemantics { }
            .graphicsLayer {
                val progress = layers.state.progress
                val bounds = playerArtworkRect(layers.sourceArtwork, layers.targetArtwork, progress)
                    .translate(-layers.hostBounds.topLeft)
                val scale = bounds.width / target.width.coerceAtLeast(1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = bounds.height / target.height.coerceAtLeast(1f)
                translationX = bounds.left - target.left
                translationY = bounds.top - target.top
                val targetRadius = if (layers.circularArtwork) layers.targetArtwork.width / 2 else
                    with(density) { layers.artworkCornerRadius.toPx() } *
                        (layers.targetArtwork.width / layers.artwork.size.width.coerceAtLeast(1))
                val radius = mix(sourceRadius, targetRadius, progress)
                shape = ContinuousRoundedRectangle(with(density) { (radius / scale.coerceAtLeast(0.001f)).toDp() })
                clip = true
                shadowElevation = 16.dp.toPx() * progress
            }
            .drawWithContent {
                if (!layers.canDrawArtworkOverlay) return@drawWithContent
                layers.artwork.alpha = 1f
                withTransform({
                    val recordingScale = target.width / layers.artwork.size.width.coerceAtLeast(1)
                    scale(recordingScale, recordingScale, Offset.Zero)
                }) { drawLayer(layers.artwork) }
            },
    )
}

@Composable
private fun rememberPlayerScreenCornerRadius(hostBounds: Rect): Float {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    return remember(context, view, hostBounds, configuration.screenWidthDp, configuration.screenHeightDp) {
        val activity = context.playerActivity()
        if (activity == null || activity.isInMultiWindowMode || activity.isInPictureInPictureMode) {
            0f
        } else {
            val current = activity.windowManager.currentWindowMetrics.bounds
            val maximum = activity.windowManager.maximumWindowMetrics.bounds
            if (current.width() < maximum.width() || current.height() < maximum.height()) {
                0f
            } else {
                val insets = view.rootWindowInsets
                listOf(
                    RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
                ).mapNotNull { insets?.getRoundedCorner(it)?.radius }.maxOrNull()?.toFloat() ?: 0f
            }
        }
    }
}

private tailrec fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}
