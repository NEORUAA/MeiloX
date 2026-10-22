package com.ljyh.mei.ui.component.sheet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.capsule.Continuity
import com.kyant.capsule.continuities.G1Continuity
import com.ljyh.mei.ui.glass.GlassPressHighlight
import com.ljyh.mei.ui.glass.clipGlassShape
import com.ljyh.mei.ui.glass.rememberGlassMorphRenderer
import com.ljyh.mei.ui.component.player.LocalPlayerArtwork
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

internal val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetLayers?> { null }

@Stable
internal class PlayerSheetLayers(
    val state: BottomSheetState,
    val miniHost: GraphicsLayer,
    val miniContent: GraphicsLayer,
    val fullContent: GraphicsLayer,
    scope: CoroutineScope,
) {
    private val transitioning = derivedStateOf { state.isTransitioning }
    val isTransitioning get() = transitioning.value
    var hostBounds by mutableStateOf(Rect.Zero)
        private set
    var miniBounds by mutableStateOf(Rect.Zero)
    var miniContentBounds by mutableStateOf(Rect.Zero)
    var miniArtworkBounds by mutableStateOf(Rect.Zero)
    var fullArtworkBounds by mutableStateOf(Rect.Zero)
    var fullArtworkLayoutSize by mutableStateOf(IntSize.Zero)
    var fullArtworkGeometry by mutableStateOf<(() -> PlayerArtworkGeometry)?>(null)
    var artworkPlaybackScale by mutableStateOf<(() -> Float)?>(null)
    var artworkCornerRadius by mutableStateOf<() -> Dp>({ 12.dp })
    var artworkShadowElevation by mutableStateOf<() -> Dp>({ 16.dp })
    var artworkTracksContainerTop by mutableStateOf<() -> Boolean>({ false })
    var artworkBackgroundColor by mutableStateOf(Color.Transparent)
    var artworkSourceAvailable by mutableStateOf(false)
    var sharedArtworkEnabled by mutableStateOf(true)
    var circularArtwork by mutableStateOf(false)
    var miniLayoutCoordinates: LayoutCoordinates? = null
    private var frozenMiniContent: Rect? = null
    private var frozenMiniArtwork: Rect? = null
    private var frozenContainer: Rect? = null
    private var miniPressedBounds = Rect.Zero
    private var openingPressedContainer: Rect? = null
    private val pressRelease = PlayerPressRelease()
    private val pressLight = PlayerPressLight(scope)
    private val pressFraction get() = pressRelease.fraction(state.progress)
    private var pressHighlightSize = Size.Zero

    private var miniPressHighlight = GlassPressHighlight()
    val transitionPressHighlight get() = pressLight.current(state.progress, transitionPressHighlightBounds)
    val transitionPressHighlightBounds get() = playerPressHighlightRect(
        pressHighlightSize,
        playerContainerRect(sourceContainer, hostBounds, state.progress),
    )
    val collapsedPressHighlight get() =
        if (state.isCollapsed && pressLight.hasCapture) transitionPressHighlight else null

    fun updatePressHighlightPointer(rootPosition: Offset) = pressLight.updatePointer(rootPosition)

    fun updateMiniPressHighlight(bounds: Rect, highlight: GlassPressHighlight) {
        if (!state.isTransitioning) {
            if (highlight.progress > 0f && pressLight.hasCapture) pressLight.clear()
            miniPressedBounds = bounds
            miniPressHighlight = highlight
        }
    }

    var miniContentCoordinates: LayoutCoordinates? = null
    var miniArtworkCoordinates: LayoutCoordinates? = null
    var fullArtworkCoordinates: LayoutCoordinates? = null
    val sourceMiniContent get() = openingBounds(
        frozenMiniContent ?: miniLayoutBounds(miniContentCoordinates) ?: miniContentBounds,
    )
    val miniScaleX get() = sourceContainer.width / (frozenContainer ?: miniBounds).width.coerceAtLeast(1f)
    val miniScaleY get() = sourceContainer.height / (frozenContainer ?: miniBounds).height.coerceAtLeast(1f)
    private var frozenTargetArtwork by mutableStateOf<PlayerArtworkGeometry?>(null)
    private val recordedTransitionLayers = PlayerSheetCaptures<GraphicsLayer>()
    private var transitionCaptureActive = false

    val sourceContainer get() = openingBounds(frozenContainer ?: miniBounds)
    val sourceArtwork get() = openingBounds(
        frozenMiniArtwork ?: miniLayoutBounds(miniArtworkCoordinates) ?: miniArtworkBounds,
    )
    private val targetArtworkGeometry get() = frozenTargetArtwork ?: playerArtworkTargetGeometry(
        fullArtworkGeometry?.invoke(),
        hostBounds,
        artworkPlaybackScale?.invoke(),
    ) {
        PlayerArtworkGeometry(fullArtworkCoordinates.visualBounds() ?: fullArtworkBounds, fullArtworkLayoutSize)
    }
    val targetArtwork get() = targetArtworkGeometry.bounds
    val targetArtworkLayoutSize get() = targetArtworkGeometry.layoutSize
    val targetArtworkOpacity get() = targetArtworkGeometry.opacity
    val targetArtworkClipBounds get() = targetArtworkGeometry.clipBounds
    // Ownership only changes when eligibility changes. Its geometry may read the
    // press-release progress, but endpoint display lists need no per-frame invalidation.
    private val artworkOwnership = derivedStateOf(structuralEqualityPolicy()) {
        isTransitioning && playerSharesArtwork(
            true, sharedArtworkEnabled, artworkSourceAvailable,
            sourceArtwork, targetArtwork, hostBounds,
        )
    }
    val sharesArtwork get() = artworkOwnership.value

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
    fun miniLayoutBounds(child: LayoutCoordinates?, container: Rect = miniBounds): Rect? {
        val layout = miniLayoutCoordinates ?: return null
        if (!layout.isAttached || child == null || !child.isAttached) return null
        val offset = layout.localPositionOf(child, Offset.Zero)
        val scaleX = container.width / layout.size.width.coerceAtLeast(1)
        val scaleY = container.height / layout.size.height.coerceAtLeast(1)
        return Rect(
            container.topLeft + Offset(offset.x * scaleX, offset.y * scaleY),
            Size(child.size.width * scaleX, child.size.height * scaleY),
        )
    }

    /** Capture the live pressed geometry before either gesture owner resets its layer. */
    fun prepareTransition() {
        if (frozenContainer != null || !miniBounds.isUsable()) return
        val visualContainer = miniLayoutCoordinates.visualBounds()
        val rawContainer = playerMiniLayoutRect(
            visualContainer,
            miniPressedBounds,
            miniLayoutCoordinates?.size ?: IntSize(miniBounds.width.roundToInt(), miniBounds.height.roundToInt()),
            miniBounds,
        )
        frozenMiniContent = miniLayoutBounds(miniContentCoordinates, rawContainer) ?: miniContentBounds
        frozenMiniArtwork = miniLayoutBounds(miniArtworkCoordinates, rawContainer) ?: miniArtworkBounds
        frozenContainer = rawContainer
        openingPressedContainer = if (miniPressedBounds.isUsable()) {
            visualContainer ?: miniPressedBounds.translate(rawContainer.topLeft)
        } else null
        pressRelease.reset()
        pressHighlightSize = (openingPressedContainer ?: rawContainer).size
        pressLight.capture(miniPressHighlight)
    }

    fun releasePressHighlight() = pressLight.release(state.progress, transitionPressHighlightBounds)

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
            if (state.isExpanded) {
                pressLight.clear()
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
            }
            pressRelease.update(state.progress)
            prepareTransition()
            val target = targetArtworkGeometry
            if (frozenTargetArtwork == null && target.bounds.isUsable()) {
                frozenTargetArtwork = target
            }
        }
    }

    fun shouldCapture(layer: GraphicsLayer): Boolean =
        // Lyrics keep updating in the full foreground; mini snapshots retain ownership.
        isTransitioning && recordedTransitionLayers.shouldCapture(layer, sharesArtwork, live = layer === fullContent)

    fun updateTransitionFrame() {
        pressRelease.update(state.progress)
    }

    fun markCaptured(layer: GraphicsLayer) {
        recordedTransitionLayers.markCaptured(layer, sharesArtwork)
    }
}

/** Root-space child bounds already contain the Backdrop graphics-layer transform. */
private fun LayoutCoordinates?.visualBounds(): Rect? {
    if (this == null || !isAttached) return null
    return Rect(localToRoot(Offset.Zero), localToRoot(Offset(size.width.toFloat(), size.height.toFloat())))
}

@Composable
internal fun rememberPlayerSheetLayers(state: BottomSheetState): PlayerSheetLayers {
    val scope = rememberCoroutineScope()
    val miniHost = rememberGraphicsLayer()
    val mini = rememberGraphicsLayer()
    val full = rememberGraphicsLayer()
    return remember(state, miniHost, mini, full, scope) {
        PlayerSheetLayers(state, miniHost, mini, full, scope)
    }
}

/** Always draw live at rest. Recording is only a source for the shared transition. */
internal fun Modifier.recordPlayerContent(
    layers: PlayerSheetLayers,
    layer: GraphicsLayer,
    warmWhileVisible: Boolean = false,
    drawInPlace: () -> Boolean,
): Modifier = drawWithContent {
    val visible = drawInPlace()
    if ((warmWhileVisible && visible) || layers.shouldCapture(layer)) {
        layer.record { this@drawWithContent.drawContent() }
        layers.markCaptured(layer)
    }
    if (visible) {
        if (warmWhileVisible) {
            // The mini player and its first moving frame use the same current display list.
            layer.alpha = 1f
            drawLayer(layer)
        } else {
            drawContent()
        }
    }
}

@Composable
internal fun Modifier.playerArtwork(
    cornerRadius: () -> Dp = { 12.dp },
    circle: Boolean = false,
    shared: Boolean = true,
    shadowElevation: () -> Dp = { 16.dp },
    trackContainerTop: () -> Boolean = { false },
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    geometry: (() -> PlayerArtworkGeometry)? = null,
    playbackScale: (() -> Float)? = null,
): Modifier {
    val layers = LocalPlayerSheet.current ?: return this
    SideEffect {
        layers.sharedArtworkEnabled = shared
        layers.artworkCornerRadius = cornerRadius
        layers.circularArtwork = circle
        layers.artworkShadowElevation = shadowElevation
        layers.artworkTracksContainerTop = trackContainerTop
        layers.artworkBackgroundColor = backgroundColor
        layers.fullArtworkGeometry = geometry
        layers.artworkPlaybackScale = playbackScale
    }
    DisposableEffect(layers) {
        onDispose {
            layers.fullArtworkGeometry = null
            layers.artworkPlaybackScale = null
            layers.fullArtworkCoordinates = null
            layers.fullArtworkBounds = Rect.Zero
            layers.fullArtworkLayoutSize = IntSize.Zero
        }
    }
    return onGloballyPositioned {
        layers.fullArtworkCoordinates = it
        // Preserve the full size and offscreen position of a paged-out cover.
        layers.fullArtworkBounds = it.visualBounds() ?: Rect.Zero
        layers.fullArtworkLayoutSize = it.size
    }
        .drawWithContent {
            // The resident Image owns the painter independently of this draw callback.
            // Endpoint drawing never records a second copy for the shared artwork.
            if (!shared || !layers.sharesArtwork) drawContent()
        }
}

@Composable
private fun Modifier.observePressHighlight(layers: PlayerSheetLayers): Modifier {
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates.value = it }
        .pointerInput(layers) {
            // Observe consumed moves on the existing hit path without claiming the page.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var pointer = down.id
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointer } ?: break
                        coordinates.value?.takeIf { it.isAttached }?.let {
                            layers.updatePressHighlightPointer(it.localToRoot(change.position))
                        }
                        val next = event.changes.firstOrNull { it.pressed } ?: break
                        if (!change.pressed) pointer = next.id
                    } while (true)
                } finally {
                    layers.releasePressHighlight()
                }
            }
        }
}

@Composable
private fun Modifier.sheetGestures(
    state: BottomSheetState,
    layers: PlayerSheetLayers,
    onHorizontalSwipe: ((HorizontalSwipeDirection) -> Unit)?,
): Modifier = observePressHighlight(layers).pointerInput(state, onHorizontalSwipe) {
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
            layers.prepareTransition()
            state.beginDrag()
        },
        onVerticalDrag = { change, amount ->
            tracker.addPointerInputChange(change)
            state.dispatchRawDelta(amount)
        },
        onDragCancel = {
            layers.releasePressHighlight()
            state.cancelDrag()
        },
        onDragEnd = {
            layers.releasePressHighlight()
            state.performFling(-tracker.calculateVelocity().y, null)
        },
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
                // The visible shell keeps its continuous corners in the tiled Canvas.
                // A native rounded outline avoids a full-screen path mask while keeping
                // hidden controls from intercepting touches in the exposed page corners.
                shape = object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
                }
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

/** Clip the retained mesh background to the moving player shell. */
private fun Modifier.clipPlayerBackground(
    layers: PlayerSheetLayers,
    expandedCornerRadius: Float,
): Modifier = graphicsLayer {
    // Bound the full-screen background layer to the visible shell during motion.
    // The tiled draw clip below keeps the exact corners without a full-screen path mask.
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
    // The hidden mini host stays at the collapsed anchor through normal player
    // motion. Preserve live relocation/dismiss offsets without placing it every frame.
    val miniAnchor by remember(state) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (state.progress == 0f && !state.isDragging) state.value else state.collapsedBound
        }
    }
    val transitionGeometryReady by remember(layers) {
        derivedStateOf { layers.hostBounds.isUsable() && layers.sourceContainer.isUsable() }
    }
    val transitionGlassRenderer = rememberGlassMorphRenderer(
        backdrop = transitionBackdrop,
        active = active && transitionGeometryReady,
        // Only the blurred page sample is reduced; all moving edges and content stay native.
        renderScale = 0.25f,
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
                        IntOffset(0, (state.expandedBound - miniAnchor + collapsedDragOffset()).roundToPx())
                    }
                    .clipPlayerRecordingHost(layers, expandedCornerRadius)
                    .sheetGestures(state, layers, onHorizontalSwipe)
                    .then(if (active || expanded) Modifier.clearAndSetSemantics { } else Modifier)
                    .recordPlayerContent(layers, layers.miniHost) { !active && !expanded },
                content = collapsedContent,
            )
        }
        // Draw transition glass below the mesh background. The background's alpha reveals
        // this glass during motion without tinting the fully expanded player background.
        if (active && transitionGeometryReady) {
            PlayerTransitionGlass(
                layers = layers,
                renderer = transitionGlassRenderer,
                expandedCornerRadius = expandedCornerRadius,
            )
        }
        // Prepare the Compose mesh before the first opening. Its unplaced host
        // neither draws nor receives input, and FluidBackground stops hidden frames.
        Box(
            Modifier.fillMaxSize()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (active || expanded) placeable.place(0, 0)
                    }
                }
                .clipPlayerBackground(layers, expandedCornerRadius)
                .clearAndSetSemantics { },
        ) {
            backgroundContent()
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
                        .sheetGestures(state, layers, onHorizontalSwipe)
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
                // This snapshot contains inset controls, not the visible shell. The shell
                // and mesh background retain their continuous corner clip separately.
                // Replaying the text/icon display lists in seven corner tiles multiplies
                // command preparation even when a tile contains no visible controls.
                clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                    val scale = bounds.width / size.width.coerceAtLeast(1f)
                    val miniAlpha = playerMiniContentAlpha(p)
                    if (miniAlpha > 0f && layers.miniContent.size.width > 0) {
                        layers.miniContent.alpha = miniAlpha
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

                    val fullAlpha = playerContentAlpha(p)
                    if (fullAlpha > 0f && layers.fullContent.size.width > 0) {
                        // Preserve descendant blend modes while the recorded controls fade.
                        layers.fullContent.compositingStrategy = CompositingStrategy.ModulateAlpha
                        layers.fullContent.alpha = fullAlpha
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
            // follow the drawn shell; the full-sized snapshot/background hosts keep their dimensions.
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
                .sheetGestures(state, layers, null)
                .clearAndSetSemantics { })
        }
    }
}

@Composable
internal fun PlayerSheetArtworkOverlay(layers: PlayerSheetLayers) {
    val artwork = LocalPlayerArtwork.current
    SideEffect { layers.artworkSourceAvailable = artwork != null }
    DisposableEffect(layers) {
        onDispose { layers.artworkSourceAvailable = false }
    }
    if (artwork == null) return

    // A stable, non-zero image host exists before the first transition frame.
    // The same Coil painter supplies mini/full/overlay rendering without another
    // request, bitmap copy or endpoint draw-time recording.
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .drawWithContent {
                if (!layers.sharesArtwork) {
                    drawContent()
                    return@drawWithContent
                }
                val targetClip = layers.targetArtworkClipBounds
                if (targetClip != null) {
                    val clip = playerArtworkClipRect(targetClip, layers.hostBounds, layers.state.progress)
                    clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                        this@drawWithContent.drawContent()
                    }
                } else drawContent()
            }
            .clearAndSetSemantics { },
    ) {
        // Native shadows must use the same logical outline size as the live endpoint.
        val density = LocalDensity.current
        val layoutSize by remember(layers) {
            derivedStateOf(structuralEqualityPolicy()) { layers.targetArtworkLayoutSize }
        }
        val imageWidth = if (layoutSize.width > 0) with(density) { layoutSize.width.toDp() } else minOf(maxWidth, maxHeight)
        val imageHeight = if (layoutSize.height > 0) with(density) { layoutSize.height.toDp() } else imageWidth
        Image(
            painter = artwork.second,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(imageWidth, imageHeight)
                .graphicsLayer {
                    if (!layers.sharesArtwork) {
                        alpha = 0f
                        shadowElevation = 0f
                        return@graphicsLayer
                    }
                    val progress = layers.state.progress
                    alpha = playerArtworkOpacity(layers.targetArtworkOpacity, progress)
                    val target = layers.targetArtwork
                    val artworkBounds = if (layers.artworkTracksContainerTop()) {
                        playerTopAnchoredArtworkRect(
                            layers.sourceArtwork, target,
                            layers.sourceContainer, layers.hostBounds, progress,
                        )
                    } else playerArtworkRect(layers.sourceArtwork, target, progress)
                    val bounds = artworkBounds.translate(-layers.hostBounds.topLeft)
                    val imageWidth = size.width.coerceAtLeast(1f)
                    val logicalTargetWidth = layers.targetArtworkLayoutSize.width.coerceAtLeast(1)
                    val visualScaleX = bounds.width / imageWidth
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = visualScaleX
                    scaleY = bounds.height / size.height.coerceAtLeast(1f)
                    translationX = bounds.left
                    translationY = bounds.top
                    val sourceRadius = com.ljyh.mei.constants.MiniPlayerArtworkCornerRadius.toPx()
                    val targetRadius = if (layers.circularArtwork) target.width / 2f else
                        layers.artworkCornerRadius().toPx() * target.width / logicalTargetWidth
                    val radius = mix(sourceRadius, targetRadius, progress) /
                        visualScaleX.coerceAtLeast(0.001f)
                    val continuity = if (layers.circularArtwork) {
                        if (progress == 1f) G1Continuity else
                            Continuity.Default.lerp(G1Continuity, progress.toDouble())
                    } else Continuity.Default
                    shape = ContinuousRoundedRectangle(radius.toDp(), continuity = continuity)
                    clip = true
                    shadowElevation = layers.artworkShadowElevation().toPx() * progress *
                        imageWidth / logicalTargetWidth
                }
                .drawBehind {
                    // Loading/error painters can be empty. Preserve the endpoint's
                    // surface frame instead of depending on a capture to fill it.
                    drawRect(layers.artworkBackgroundColor)
                },
        )
    }
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
