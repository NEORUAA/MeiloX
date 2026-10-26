package com.ljyh.mei.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.PathSegment as ComposePathSegment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.capsule.core.Point
import com.kyant.capsule.path.PathSegment
import com.kyant.capsule.path.toPath
import com.kyant.shapes.Capsule
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import com.ljyh.mei.ui.liquidglass.PressHighlightRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Both styles retain the same action slots and icon-to-icon spacing. */
enum class IosToolbarActionStyle { Merged, Separated }

internal const val ToolbarDetachProgress = 0.8f

internal val LocalIosToolbarActionGroup = staticCompositionLocalOf<ToolbarActionGroup?> { null }
internal val LocalToolbarActionSeparation = staticCompositionLocalOf<(() -> Float)?> { null }

/** Transfers a complete connected toolbar surface to its menu without changing its layout. */
@Stable
class IosMenuAnchorState internal constructor() {
    private var group: ToolbarActionGroup? = null
    private var triggerBounds by mutableStateOf<Rect?>(null)
    private var acquiredBounds by mutableStateOf<Rect?>(null)
    private var acquiredGroup: ToolbarActionGroup? = null
    @Volatile
    internal var shaderPrograms: LiquidMenuShaderPrograms? = null
        private set

    @Synchronized
    internal fun prewarmShaders() {
        if (shaderPrograms == null) shaderPrograms = LiquidMenuShaderPrograms()
    }
    internal var portal by mutableStateOf<ToolbarMenuPortal?>(null)
        private set

    /** Group content is hidden in the final Row draw, after its portal is recorded. */
    internal val isGroupedSource: Boolean get() = acquiredGroup != null
    internal var isAcquired by mutableStateOf(false)
        private set

    /** Window coordinates, shared by the trigger and the popup position provider. */
    val bounds: Rect?
        get() = acquiredBounds ?: liveBounds

    /** Follow the source's live press transform while the popup keeps its placement frozen. */
    internal val liveBounds: Rect?
        get() = triggerBounds?.let { source ->
            group?.menuSourceBounds(source) ?: source
        }

    /** The native through item is the trigger, while the source may be its entire fused run. */
    internal val previewBounds: Rect?
        get() = triggerBounds?.let { source ->
            group?.menuPreviewBounds(source) ?: source
        }

    internal fun bind(group: ToolbarActionGroup?, bounds: Rect) {
        this.group = group
        triggerBounds = bounds
    }

    internal fun acquire(): Rect? {
        if (isAcquired) return acquiredBounds
        val source = triggerBounds?.takeUnless { it.isEmpty } ?: return null
        val sourceGroup = group
        val run = sourceGroup?.menuSourceRun(source).orEmpty()
        acquiredBounds = if (run.isEmpty()) source else sourceGroup!!.visualBoundsInWindow(run)
        acquiredGroup = sourceGroup.takeIf { run.isNotEmpty() }
        portal = acquiredGroup?.foregroundPortal(run)
        isAcquired = true
        acquiredGroup?.hideMenuSource(this, run)
        return acquiredBounds
    }

    internal fun release() {
        acquiredGroup?.restoreMenuSource(this)
        acquiredGroup = null
        portal = null
        acquiredBounds = null
        isAcquired = false
    }
}

@Composable
fun rememberIosMenuAnchorState(): IosMenuAnchorState {
    val state = remember { IosMenuAnchorState() }
    DisposableEffect(state) {
        onDispose { state.release() }
    }
    return state
}

/** Attach to the source button even when its menu is composed outside the toolbar. */
@Composable
fun Modifier.iosMenuAnchor(state: IosMenuAnchorState): Modifier {
    val group = LocalIosToolbarActionGroup.current
    LaunchedEffect(state) { withContext(Dispatchers.Default) { state.prewarmShaders() } }
    return onGloballyPositioned { state.bind(group, it.boundsInWindow()) }
        .graphicsLayer { alpha = if (state.isAcquired && !state.isGroupedSource) 0f else 1f }
}

/** Shares only the material; each action retains its own hit target and interaction state. */
@Composable
internal fun IosToolbarActionGroup(
    modifier: Modifier = Modifier,
    style: IosToolbarActionStyle = IosToolbarActionStyle.Merged,
    separationProgress: Float? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val styleProgress by animateFloatAsState(
        targetValue = if (style == IosToolbarActionStyle.Separated) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f),
        label = "ToolbarActionSeparation",
    )
    val progress = rememberUpdatedState((separationProgress ?: styleProgress).coerceIn(0f, 1f))
    val density = LocalDensity.current
    val foregroundLayer = rememberGraphicsLayer()
    val group = remember(density, foregroundLayer) {
        ToolbarActionGroup(with(density) { 8.dp.toPx() }, density, progress, foregroundLayer)
    }
    val colors = LocalGlassColors.current
    val appearance = LocalGlassAppearance.current
    val backdrop = LocalGlassBackdrop.current
    val runs = group.sharedRuns()
    Box(modifier.onGloballyPositioned(group::updateCoordinates)) {
        runs.forEach { run -> key(run.first(), run.last()) {
            val material = run.first().material
            val navigationMaterial = material.color == null && material.sampleBackdrop && appearance.usesBackdrop
            val innerShadows = rememberNavigationInnerShadows(navigationMaterial)
            val shapeState = remember(group, progress, run) {
                derivedStateOf { group.shape(run, progress.value) }
            }
            val shape = remember(shapeState) { { shapeState.value } }
            val press = remember(group, run) {
                derivedStateOf { run.maxOfOrNull { it.highlight.pressProgress } ?: 0f }
            }
            val background = remember(backdrop, shape, colors, appearance, innerShadows, material, run) {
                Modifier.navigationGlassBackground(
                    backdrop = backdrop,
                    shape = shape,
                    containerColor = material.color ?: colors.container,
                    navigationMaterial = navigationMaterial,
                    innerShadows = innerShadows,
                    isDark = colors.isDark,
                    containerAlphaMultiplier = material.alphaMultiplier,
                    sampleBackdrop = material.sampleBackdrop,
                    pressProgressState = press,
                    pressOverlay = { group.drawPressHighlight(this, run) },
                    layerBlock = {
                        group.applyTransform(this, group.bounds(run), group.pressTransform(run.first()))
                        alpha = if (group.isMenuSourceHidden(run)) 0f else 1f
                    },
                    appearance = appearance,
                )
            }
            // The layer stays at the run's untransformed size; its graphics transform can
            // expand outside that layer without clipping the shell to the original slot.
            Box(Modifier.matchParentSize().layout { measurable, constraints ->
                val bounds = group.bounds(run)
                val placeable = measurable.measure(Constraints.fixed(
                    bounds.width.roundToInt().coerceAtLeast(0),
                    bounds.height.roundToInt().coerceAtLeast(0),
                ))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                }
            }.then(background))
        } }
        CompositionLocalProvider(LocalIosToolbarActionGroup provides group) {
            Row(
                modifier = Modifier.drawWithContent {
                    if (size.width <= 0f || size.height <= 0f) {
                        drawContent()
                        return@drawWithContent
                    }
                    // Child layers retain their opacity for the outgoing portal. Suppress
                    // the original glyphs only in this final draw, after recording them.
                    foregroundLayer.record { this@drawWithContent.drawContent() }
                    group.markForegroundRecorded()
                    val hidden = group.foregroundOcclusionPath()
                    if (hidden == null) drawLayer(foregroundLayer)
                    else clipPath(hidden, clipOp = ClipOp.Difference) { drawLayer(foregroundLayer) }
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
internal fun rememberToolbarActionMember(
    eligible: Boolean,
    material: ToolbarActionMaterial,
    separation: (() -> Float)?,
    highlight: InteractiveHighlight,
): ToolbarActionMember? {
    val group = LocalIosToolbarActionGroup.current.takeIf { eligible }
    val currentSeparation = rememberUpdatedState(separation)
    val foregroundLayer = if (group != null) rememberGraphicsLayer() else null
    val member = remember(group, highlight, material, foregroundLayer) {
        group?.let { ToolbarActionMember(it, material, currentSeparation, highlight, requireNotNull(foregroundLayer)) }
    }
    DisposableEffect(member) {
        member?.group?.members?.add(member)
        onDispose { member?.group?.members?.remove(member) }
    }
    return member
}

internal data class ToolbarActionMaterial(
    val color: Color?,
    val alphaMultiplier: Float,
    val sampleBackdrop: Boolean,
)

internal class ToolbarActionMember(
    val group: ToolbarActionGroup,
    val material: ToolbarActionMaterial,
    val separation: State<(() -> Float)?>,
    val highlight: InteractiveHighlight,
    val foregroundLayer: GraphicsLayer,
) {
    var coordinates: LayoutCoordinates? = null
    var bounds: Rect? by mutableStateOf(null)
    var foregroundSize = Size.Zero
        private set

    fun drawForeground(scope: ContentDrawScope) = with(scope) {
        foregroundLayer.record { this@with.drawContent() }
        foregroundSize = size
        drawLayer(foregroundLayer)
    }

    val sharesSurface: Boolean
        get() = group.connectedRun(this).size > 1

    fun updateCoordinates(value: LayoutCoordinates) {
        coordinates = value
        updateBounds()
    }

    fun updateBounds() {
        val parent = group.coordinates
        val child = coordinates
        // Popup content inherits composition locals, but belongs to another layout root.
        bounds = if (parent?.isAttached == true && child?.isAttached == true &&
            parent.findRootCoordinates() === child.findRootCoordinates()
        ) parent.localBoundingBoxOf(child, clipBounds = false) else null
    }

    fun applyPressTransform(scope: GraphicsLayerScope) {
        val base = bounds ?: return
        group.applyTransform(scope, base, group.pressTransform(this))
        scope.alpha = if (group.isMenuSourceHidden(listOf(this)) && !group.isMenuSourcePortaled(this)) 0f else 1f
    }
}

/** The original foreground stays GPU-resident while the menu deforms its source envelope. */
internal data class ToolbarMenuPortal(
    val layer: GraphicsLayer,
    val sourceBounds: Rect,
    private val sourceBoundsProvider: (() -> Rect)? = null,
) {
    // The retained Row follows the live press transform. Normalize its contents
    // against that same transform, while keeping the acquisition bounds unchanged.
    val currentSourceBounds: Rect
        get() = sourceBoundsProvider?.invoke()?.takeUnless { it.isEmpty } ?: sourceBounds
}

/** A single affine transform is shared by the shell and every icon in a fused run. */
internal data class ToolbarActionTransform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
) {
    fun map(point: Offset) = Offset(point.x * scaleX + translationX, point.y * scaleY + translationY)

    fun followedBy(next: ToolbarActionTransform) = ToolbarActionTransform(
        scaleX * next.scaleX, scaleY * next.scaleY,
        translationX * next.scaleX + next.translationX,
        translationY * next.scaleY + next.translationY,
    )
}

internal class ToolbarActionGroup(
    val gap: Float,
    val density: Density,
    val separationProgress: State<Float>,
    private val foregroundLayer: GraphicsLayer,
) {
    private val pressScope = GraphicsLayerBlockScope()
    private val pressRenderer = PressHighlightRenderer()

    val members = mutableStateListOf<ToolbarActionMember>()
    var coordinates: LayoutCoordinates? = null
    private var menuSources by mutableStateOf<Map<IosMenuAnchorState, List<ToolbarActionMember>>>(emptyMap())
    private var foregroundRecorded = false
    private val foregroundOcclusion = Path()

    private val layoutRuns = derivedStateOf(structuralEqualityPolicy()) { calculateRuns() }
    private val connectedRuns = derivedStateOf(structuralEqualityPolicy()) {
        runs().flatMap { run ->
            val result = mutableListOf<MutableList<ToolbarActionMember>>()
            var previous: ToolbarActionMember? = null
            run.forEach { member ->
                if (previous?.let { connection(it, member) > 0f } == true) result.last().add(member)
                else result.add(mutableListOf(member))
                previous = member
            }
            result
        }
    }
    private val sharedRunsState = derivedStateOf(structuralEqualityPolicy()) {
        connectedRuns.value.filter { it.size > 1 }
    }

    fun updateCoordinates(value: LayoutCoordinates) {
        coordinates = value
        members.forEach { it.updateBounds() }
    }

    fun runs(): List<List<ToolbarActionMember>> = layoutRuns.value

    private fun calculateRuns(): List<List<ToolbarActionMember>> {
        val ordered = members.filter { it.bounds?.isEmpty == false }.sortedBy { it.bounds!!.left }
        if (ordered.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<ToolbarActionMember>>()
        ordered.forEach { member ->
            val previousMember = result.lastOrNull()?.lastOrNull()
            val previous = previousMember?.bounds
            val bounds = member.bounds!!
            if (previous != null && previousMember.material == member.material && abs(bounds.left - previous.right - gap) <= 2f &&
                abs(bounds.center.y - previous.center.y) <= 2f && abs(bounds.height - previous.height) <= 2f
            ) {
                result.last().add(member)
            } else {
                result.add(mutableListOf(member))
            }
        }
        return result
    }

    private fun connection(left: ToolbarActionMember, right: ToolbarActionMember): Float =
        1f - neckProgress(maxOf(separationProgress.value, left.separation.value?.invoke() ?: 0f,
            right.separation.value?.invoke() ?: 0f))

    private fun neckProgress(progress: Float) = (progress / ToolbarDetachProgress).coerceIn(0f, 1f)

    fun connectedRun(member: ToolbarActionMember): List<ToolbarActionMember> {
        return connectedRuns.value.firstOrNull { member in it } ?: listOf(member)
    }

    private fun menuSourceMember(source: Rect): ToolbarActionMember? {
        val origin = coordinates?.takeIf { it.isAttached }?.localToWindow(Offset.Zero) ?: return null
        val localSource = source.translate(-origin)
        return members.filter { it.bounds?.overlaps(localSource) == true }
            .maxByOrNull { member ->
                val intersection = member.bounds!!.intersect(localSource)
                intersection.width * intersection.height
            }
    }

    internal fun menuSourceRun(source: Rect): List<ToolbarActionMember> =
        menuSourceMember(source)?.let(::connectedRun).orEmpty()

    internal fun menuPreviewBounds(source: Rect): Rect? =
        menuSourceMember(source)?.let { visualBoundsInWindow(listOf(it)) }

    internal fun menuSourceBounds(source: Rect): Rect? {
        val run = menuSourceRun(source)
        if (run.isEmpty()) return null
        return visualBoundsInWindow(run)
    }

    internal fun visualBoundsInWindow(run: List<ToolbarActionMember>): Rect {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return Rect.Zero
        val bounds = bounds(run)
        val transform = pressTransform(run.first())
        val topLeft = coordinates.localToWindow(transform.map(bounds.topLeft))
        val bottomRight = coordinates.localToWindow(transform.map(bounds.bottomRight))
        return Rect(topLeft, bottomRight)
    }

    internal fun markForegroundRecorded() {
        foregroundRecorded = true
    }

    internal fun foregroundPortal(run: List<ToolbarActionMember>): ToolbarMenuPortal? {
        if (run.size == 1) {
            // A standalone control draws its own glass inside the Row. Transfer only
            // its live content layer, before the press transform, without replaying glass.
            val member = run.single()
            if (member.foregroundSize.isEmpty()) return null
            return ToolbarMenuPortal(member.foregroundLayer, Rect(Offset.Zero, member.foregroundSize))
        }
        // Shared runs draw their material outside the Row, leaving a clean glyph portal.
        if (!foregroundRecorded || run.isEmpty()) return null
        val sourceBounds = visualBoundsInGroup(run)
        if (sourceBounds.isEmpty) return null
        return ToolbarMenuPortal(foregroundLayer, sourceBounds) { visualBoundsInGroup(run) }
    }

    internal fun isMenuSourcePortaled(member: ToolbarActionMember): Boolean =
        menuSources.any { (owner, run) -> owner.portal != null && member in run }

    internal fun foregroundOcclusionPath(): Path? {
        foregroundOcclusion.rewind()
        menuSources.forEach { (owner, run) ->
            if (owner.portal != null) {
                // Include antialias coverage around the glyph layer's physical run bounds.
                foregroundOcclusion.addRect(visualBoundsInGroup(run).inflate(1f))
            }
        }
        return foregroundOcclusion.takeUnless { it.isEmpty }
    }

    private fun visualBoundsInGroup(run: List<ToolbarActionMember>): Rect {
        val bounds = bounds(run)
        if (bounds.isEmpty) return Rect.Zero
        val transform = pressTransform(run.first())
        return Rect(transform.map(bounds.topLeft), transform.map(bounds.bottomRight))
    }

    internal fun hideMenuSource(owner: IosMenuAnchorState, run: List<ToolbarActionMember>) {
        menuSources = menuSources + (owner to run)
    }

    internal fun restoreMenuSource(owner: IosMenuAnchorState) {
        menuSources = menuSources - owner
    }

    internal fun isMenuSourceHidden(run: List<ToolbarActionMember>): Boolean =
        menuSources.values.any { hidden -> run.any { it in hidden } }

    private fun transform(bounds: Rect, highlight: InteractiveHighlight): ToolbarActionTransform {
        pressScope.reset(density, bounds.size)
        pressScope.applyGlassDragScale(highlight.pressProgress, highlight.offset)
        return ToolbarActionTransform(
            pressScope.scaleX, pressScope.scaleY,
            bounds.center.x * (1f - pressScope.scaleX) + pressScope.translationX,
            bounds.center.y * (1f - pressScope.scaleY) + pressScope.translationY,
        )
    }

    fun pressTransform(member: ToolbarActionMember): ToolbarActionTransform {
        val base = member.bounds ?: return ToolbarActionTransform()
        val run = connectedRun(member)
        val active = run.maxBy { it.highlight.pressProgress }
        val pressed = transform(if (run.size > 1) bounds(run) else base, active.highlight)
        val family = runs().firstOrNull { member in it } ?: return pressed
        if (family.size < 2) return pressed
        val progress = maxOf(separationProgress.value,
            family.maxOf { it.separation.value?.invoke() ?: 0f }).coerceIn(0f, 1f)
        // A small outward stretch gives the neck a visible pull. It continues to recoil
        // after the connection breaks, returning every icon to its original final slot.
        val wave = sin(PI * progress).toFloat()
        val tension = wave * wave
        val bounds = bounds(family)
        val stretchX = 1f + with(density) { 12.dp.toPx() } / bounds.width.coerceAtLeast(1f) * tension
        val stretchY = 1f - 0.035f * tension
        return pressed.followedBy(ToolbarActionTransform(
            stretchX, stretchY,
            bounds.center.x * (1f - stretchX),
            bounds.center.y * (1f - stretchY),
        ))
    }

    fun applyTransform(scope: GraphicsLayerScope, bounds: Rect, transform: ToolbarActionTransform) {
        with(scope) {
            scaleX = transform.scaleX
            scaleY = transform.scaleY
            val movement = transform.map(bounds.center) - bounds.center
            translationX = movement.x
            translationY = movement.y
        }
    }

    fun bounds(run: List<ToolbarActionMember>): Rect {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        run.forEach { member -> member.bounds?.let { bounds ->
            left = minOf(left, bounds.left)
            top = minOf(top, bounds.top)
            right = maxOf(right, bounds.right)
            bottom = maxOf(bottom, bounds.bottom)
        } }
        return if (left.isFinite()) Rect(left, top, right, bottom) else Rect.Zero
    }

    fun sharedRuns(): List<List<ToolbarActionMember>> = sharedRunsState.value

    fun drawPressHighlight(scope: DrawScope, run: List<ToolbarActionMember>) = with(scope) {
        val active = run.maxBy { it.highlight.pressProgress }
        val base = active.bounds ?: return@with
        val position = base.topLeft - bounds(run).topLeft + active.highlight.highlightPosition(base.size)
        // navigationGlassBackground already clips this pass to the full run outline.
        pressRenderer.draw(this, active.highlight.pressProgress, position)
    }

    fun shape(run: List<ToolbarActionMember>, separation: Float): Shape {
        val origin = bounds(run).topLeft
        return ToolbarActionShape(listOf(run.map { member ->
            member.bounds?.let { bounds ->
                ToolbarActionOutline(bounds.translate(-origin),
                    neckProgress(member.separation.value?.invoke() ?: 0f))
            }
        }), neckProgress(separation))
    }
}

private data class ToolbarActionOutline(val bounds: Rect, val separation: Float)

private val ToolbarContinuousShape = ContinuousRoundedRectangle(50)

/** Original layout size and its axis-aligned visual bounds in the shared drawing space. */
internal data class LiquidContactCapsule(val size: Size, val bounds: Rect)

/** Adds adhesion only while the independently transformed surfaces are close enough. */
internal fun liquidContactShape(
    first: LiquidContactCapsule,
    second: LiquidContactCapsule,
    contactDistance: Float,
    includeDisconnected: Boolean = false,
): Shape? {
    fun LiquidContactCapsule.isValid() = size.width > 0f && size.height > 0f &&
        size.width.isFinite() && size.height.isFinite() && bounds.width > 0f && bounds.height > 0f &&
        bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() && bounds.bottom.isFinite()
    if (!first.isValid() || !second.isValid() || !contactDistance.isFinite() || contactDistance <= 0.01f) return null
    val effectiveDistance = contactDistance - 0.01f
    val left = if (first.bounds.center.x <= second.bounds.center.x) first else second
    val right = if (left === first) second else first
    return LiquidContactShape(left, right, effectiveDistance).takeIf { includeDisconnected || it.isConnected }
}

internal data class LiquidContactShape(
    val first: LiquidContactCapsule,
    val second: LiquidContactCapsule,
    val contactDistance: Float,
    private val lensTopStart: CornerSize = CornerSize(50),
    private val lensTopEnd: CornerSize = CornerSize(50),
    private val lensBottomEnd: CornerSize = CornerSize(50),
    private val lensBottomStart: CornerSize = CornerSize(50),
    val outlineScale: Offset = Offset(1f, 1f),
    val outlineTranslation: Offset = Offset.Zero,
) : CornerBasedShape(lensTopStart, lensTopEnd, lensBottomEnd, lensBottomStart) {
    private var baseGeometry = lazy(LazyThreadSafetyMode.NONE) {
        contactCapsulePath(first, second, contactDistance)
    }
    private var cachedOutline: Outline? = null
    internal val isConnected: Boolean get() = !(second.bounds.left - first.bounds.right >= contactDistance ||
        min(first.bounds.bottom, second.bounds.bottom) <= maxOf(first.bounds.top, second.bounds.top))
    internal var hasConsistentWinding: Boolean = false
        private set

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize): CornerBasedShape =
        LiquidContactShape(first, second, contactDistance, topStart, topEnd, bottomEnd, bottomStart,
            outlineScale, outlineTranslation).also { it.baseGeometry = baseGeometry }

    fun withOutlineTransform(scale: Offset, translation: Offset): LiquidContactShape =
        copy(outlineScale = scale, outlineTranslation = translation).also { it.baseGeometry = baseGeometry }

    override fun createOutline(
        size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline = cachedOutline ?: baseGeometry.value.let { geometry ->
        hasConsistentWinding = geometry.hasConsistentWinding
        val path = if (outlineScale != Offset(1f, 1f) || outlineTranslation != Offset.Zero) {
            // Keep the shared native contour immutable across visible and sampled outlines.
            Path().apply {
                fillType = geometry.path.fillType
                addPath(geometry.path)
            }.also { transformed ->
                transformed.asAndroidPath().transform(android.graphics.Matrix().apply {
                    setValues(floatArrayOf(
                        outlineScale.x, 0f, outlineTranslation.x,
                        0f, outlineScale.y, outlineTranslation.y,
                        0f, 0f, 1f,
                    ))
                })
            }
        } else geometry.path
        Outline.Generic(path).also { cachedOutline = it }
    }
}

private data class NativeContactProfile(
    val size: Size,
    val segments: List<PathSegment>,
    val upper: ContinuousActionProfile,
)

private val ContactCapsule = Capsule()
private val ContactProfileCache = ArrayDeque<NativeContactProfile>(4)

private fun nativeContactProfile(size: Size): NativeContactProfile = synchronized(ContactProfileCache) {
    ContactProfileCache.firstOrNull { it.size == size }?.let { return@synchronized it }
    val bounds = Rect(Offset.Zero, size)
    val segments: List<PathSegment> = if (size.width == size.height) {
        listOf(PathSegment.Circle(Point(size.width / 2.0, size.height / 2.0), size.width / 2.0))
    } else {
        // Read the actual legacy Capsule cubics. Its corner profile differs from the
        // newer toolbar capsule, so regenerating these from a radius would cause a jump.
        val outline = ContactCapsule.createOutline(size, LayoutDirection.Ltr, Density(1f)) as Outline.Generic
        val iterator = outline.path.iterator(PathIterator.ConicEvaluation.AsConic)
        val points = FloatArray(8)
        buildList<PathSegment> {
            fun point(index: Int) = Point(points[index].toDouble(), points[index + 1].toDouble())
            var contourStart = Point.Zero
            var current = Point.Zero
            while (iterator.hasNext()) {
                when (iterator.next(points)) {
                    ComposePathSegment.Type.Move -> { contourStart = point(0); current = contourStart }
                    ComposePathSegment.Type.Line -> { current = point(2); add(PathSegment.Line(point(0), current)) }
                    ComposePathSegment.Type.Cubic -> {
                        current = point(6)
                        add(PathSegment.Cubic(point(0), point(2), point(4), current))
                    }
                    ComposePathSegment.Type.Close -> {
                        // Capsule closes across its flat top; retain that edge in the
                        // extracted profile as well as in the original closed path.
                        if (current != contourStart) add(PathSegment.Line(current, contourStart))
                        current = contourStart
                    }
                    ComposePathSegment.Type.Done -> Unit
                    else -> error("The native continuous capsule must contain only lines and cubics")
                }
            }
        }
    }
    NativeContactProfile(size, segments, ContinuousActionProfile(bounds, segments)).also {
        if (ContactProfileCache.size == 4) ContactProfileCache.removeFirst()
        ContactProfileCache.addLast(it)
    }
}

private class ContactProfile(
    val capsule: LiquidContactCapsule,
    val native: NativeContactProfile = nativeContactProfile(capsule.size),
) {
    private val scaleX = capsule.bounds.width.toDouble() / capsule.size.width
    private val scaleY = capsule.bounds.height.toDouble() / capsule.size.height
    private val origin = Point(capsule.bounds.left.toDouble(), capsule.bounds.top.toDouble())
    val radiusX = min(capsule.size.width, capsule.size.height) / 2.0 * scaleX
    val radiusY = min(capsule.size.width, capsule.size.height) / 2.0 * scaleY

    fun jet(x: Double): CurveJet = transform(native.upper.jet((x - origin.x) / scaleX))

    private fun transform(jet: CurveJet): CurveJet {
        val tangent = Point(jet.tangent.x * scaleX, jet.tangent.y * scaleY)
        val speed = sqrt(tangent.x * tangent.x + tangent.y * tangent.y)
        return CurveJet(
            Point(origin.x + jet.position.x * scaleX, origin.y + jet.position.y * scaleY),
            tangent / speed, jet.curvature * scaleX * scaleY / (speed * speed * speed),
        )
    }

    fun attachment(target: Point, fromLeft: Boolean): CurveJet {
        fun at(fraction: Double) = transform(native.upper.jetAt(if (fromLeft) fraction else 1.0 - fraction))
        fun distance(fraction: Double): Double {
            val delta = target - at(fraction).position
            return delta.x * delta.x + delta.y * delta.y
        }
        // An elongated ellipse can have more than one stationary distance. Locate
        // the nearest interval before refining, rather than taking the first root.
        var nearest = 0
        var nearestDistance = distance(0.0)
        for (step in 1..16) {
            val candidate = distance(step / 16.0)
            if (candidate < nearestDistance) { nearest = step; nearestDistance = candidate }
        }
        var nearLow = maxOf(0, nearest - 1) / 16.0
        var nearHigh = min(16, nearest + 1) / 16.0
        val ratio = 0.6180339887498949
        var first = nearHigh - (nearHigh - nearLow) * ratio
        var last = nearLow + (nearHigh - nearLow) * ratio
        var firstDistance = distance(first)
        var lastDistance = distance(last)
        repeat(18) {
            if (firstDistance < lastDistance) {
                nearHigh = last
                last = first
                lastDistance = firstDistance
                first = nearHigh - (nearHigh - nearLow) * ratio
                firstDistance = distance(first)
            } else {
                nearLow = first
                first = last
                firstDistance = lastDistance
                last = nearLow + (nearHigh - nearLow) * ratio
                lastDistance = distance(last)
            }
        }
        val closest = (nearLow + nearHigh) / 2.0
        // Attach between the native support tangent and its nearest point to the
        // neck. A fixed inset can put the neck inside that tangent and cut the body.
        var low = 0.0
        var high = closest
        repeat(16) {
            val middle = (low + high) / 2.0
            val jet = at(middle)
            val delta = target - jet.position
            if (jet.tangent.x * delta.y - jet.tangent.y * delta.x > 0.0) low = middle else high = middle
        }
        val support = (low + high) / 2.0
        return at((support + closest) / 2.0)
    }

    fun trim(start: Double, end: Double): List<ContactSegment> = native.upper
        .trim((start - origin.x) / scaleX, (end - origin.x) / scaleX)
        .map { ContactSegment(it, scaleX, scaleY, origin) }

    fun mirrorY(): ContactProfile = ContactProfile(
        capsule.copy(bounds = Rect(capsule.bounds.left, -capsule.bounds.bottom, capsule.bounds.right, -capsule.bounds.top)),
        native,
    )

    fun fullPath(): Path = Path().apply {
        val segments = native.segments.map { ContactSegment(it, scaleX, scaleY, origin) }
        if (segments.isNotEmpty()) {
            moveTo(segments.first().from.x.toFloat(), segments.first().from.y.toFloat())
            segments.forEach { it.drawTo(this) }
            close()
        }
    }
}

/** Affine arcs stay native elliptical arcs instead of being approximated with cubics. */
private data class ContactSegment(
    val segment: PathSegment,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val origin: Point = Point.Zero,
) {
    private fun Point.transform() = Point(origin.x + x * scaleX, origin.y + y * scaleY)
    val from: Point get() = segment.from.transform()

    fun drawTo(path: Path) {
        when (val curve = segment) {
            is PathSegment.Line -> curve.to.transform().let { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
            is PathSegment.Cubic -> {
                val first = curve.p1.transform()
                val second = curve.p2.transform()
                val last = curve.p3.transform()
                path.cubicTo(first.x.toFloat(), first.y.toFloat(), second.x.toFloat(), second.y.toFloat(), last.x.toFloat(), last.y.toFloat())
            }
            is PathSegment.Arc -> {
                val center = curve.center.transform()
                path.arcTo(Rect(
                    (center.x - curve.radius * scaleX).toFloat(), (center.y - curve.radius * scaleY).toFloat(),
                    (center.x + curve.radius * scaleX).toFloat(), (center.y + curve.radius * scaleY).toFloat(),
                ), (curve.startAngle * 180.0 / PI).toFloat(), (curve.sweepAngle * 180.0 / PI).toFloat(), false)
            }
            is PathSegment.Circle -> {
                val center = curve.center.transform()
                path.addOval(Rect(
                    (center.x - curve.radius * scaleX).toFloat(), (center.y - curve.radius * scaleY).toFloat(),
                    (center.x + curve.radius * scaleX).toFloat(), (center.y + curve.radius * scaleY).toFloat(),
                ))
            }
        }
    }

    fun mirrorAndReverse(): ContactSegment = copy(
        segment = segment.reflectAndReverse(0.0), origin = Point(origin.x, -origin.y),
    )
}

private data class ContactGeometry(val path: Path, val hasConsistentWinding: Boolean)

private fun contactCapsulePath(first: LiquidContactCapsule, second: LiquidContactCapsule, contactDistance: Float): ContactGeometry {
    val left = ContactProfile(first)
    val right = ContactProfile(second)
    fun union() = ContactGeometry(Path.combine(PathOperation.Union, left.fullPath(), right.fullPath()), false)
    // A hidden source remains valid before contact and after separation. Preserve the
    // two native closed contours without extending a connector across empty space.
    if (second.bounds.left - first.bounds.right >= contactDistance ||
        min(first.bounds.bottom, second.bounds.bottom) <= maxOf(first.bounds.top, second.bounds.top)
    ) return union()
    // Containment has no facing pair of sides; retain the exact visible union instead
    // of forcing a reversed bridge through one of the bodies.
    if (first.bounds.left >= second.bounds.left || first.bounds.right >= second.bounds.right) return union()
    val gap = second.bounds.left.toDouble() - first.bounds.right
    val joinDistance = contactDistance / 2.0
    val approach = ((contactDistance - gap) / joinDistance).coerceIn(0.0, 1.0).let {
        it * it * it * (10.0 + it * (-15.0 + 6.0 * it))
    }
    val joined = gap < joinDistance
    val centerY = (first.bounds.center.y.toDouble() + second.bounds.center.y) / 2.0
    val radius = min(left.radiusY, right.radiusY)
    val middleX = (first.bounds.right.toDouble() + second.bounds.left) / 2.0
    val openingArea = ((joinDistance - gap) / joinDistance).coerceIn(0.0, 1.0).let {
        it * it * (3.0 - 2.0 * it)
    }
    // Ease the cross-sectional area so the neck opens into a rounded waist.
    val neckRadius = radius * sqrt(openingArea) * 0.6
    val tipGap = maxOf(0.0, gap) * (1.0 - approach)
    val leftTip = Point(middleX - tipGap / 2.0, interpolate(first.bounds.center.y.toDouble(), centerY, approach))
    val rightTip = Point(middleX + tipGap / 2.0, interpolate(second.bounds.center.y.toDouble(), centerY, approach))

    fun upper(firstProfile: ContactProfile, secondProfile: ContactProfile, mirror: Boolean): Pair<List<ContactSegment>, List<ContactSegment>> {
        val direction = if (mirror) -1.0 else 1.0
        var seamY = centerY * direction - neckRadius
        if (joined && gap < 0.0) {
            // The seam must stay outside both native bodies as they overlap.
            for (profile in listOf(firstProfile, secondProfile)) {
                if (middleX > profile.capsule.bounds.left && middleX < profile.capsule.bounds.right) {
                    seamY = min(seamY, profile.jet(middleX).position.y - radius * 0.1)
                }
            }
        }
        val seam = Point(middleX, seamY)
        val leftTarget = if (joined) seam else Point(leftTip.x, leftTip.y * direction)
        val rightTarget = if (joined) seam else Point(rightTip.x, rightTip.y * direction)
        val leftJet = firstProfile.attachment(leftTarget, fromLeft = true)
        val rightJet = secondProfile.attachment(rightTarget, fromLeft = false)
        val end = leftJet.position.x
        val start = rightJet.position.x
        val leftEnd: CurveJet
        val rightStart: CurveJet
        val leftTipSpeed: Double
        val rightTipSpeed: Double
        if (joined) {
            val curvature = if (neckRadius > 0.0) -1.0 / neckRadius else 0.0
            leftEnd = CurveJet(seam, Point(1.0, 0.0), curvature)
            rightStart = leftEnd
            val speed = min(min(middleX - end, start - middleX) * 0.3, neckRadius * 0.7)
            leftTipSpeed = speed
            rightTipSpeed = speed
        } else {
            // Each lobe is independently closed while there is still a gap. Its
            // rounded tip contracts to the same point as the future neck; no path
            // segment ever crosses the empty space between the two tips.
            val leftTipRadius = left.radiusY * left.radiusY / left.radiusX * (1.0 - approach)
            val rightTipRadius = right.radiusY * right.radiusY / right.radiusX * (1.0 - approach)
            leftEnd = CurveJet(Point(leftTip.x, leftTip.y * direction), Point(0.0, 1.0),
                if (leftTipRadius > 0.0) 1.0 / leftTipRadius else 0.0)
            rightStart = CurveJet(Point(rightTip.x, rightTip.y * direction), Point(0.0, -1.0),
                if (rightTipRadius > 0.0) 1.0 / rightTipRadius else 0.0)
            val leftDelta = leftEnd.position - leftJet.position
            val rightDelta = rightJet.position - rightStart.position
            leftTipSpeed = min(sqrt(leftDelta.x * leftDelta.x + leftDelta.y * leftDelta.y) * 0.5, leftTipRadius * 0.7)
            rightTipSpeed = min(sqrt(rightDelta.x * rightDelta.x + rightDelta.y * rightDelta.y) * 0.5, rightTipRadius * 0.7)
        }
        val firstHalf = buildList {
            addAll(firstProfile.trim(first.bounds.left.toDouble(), end))
            addAll(contactSpline(leftJet, leftEnd, (leftEnd.position.x - end) * 0.3,
                leftTipSpeed, nativeStart = true).map { ContactSegment(it) })
        }
        val secondHalf = buildList {
            addAll(contactSpline(rightStart, rightJet, rightTipSpeed,
                (start - rightStart.position.x) * 0.3, nativeStart = false).map { ContactSegment(it) })
            addAll(secondProfile.trim(start, second.bounds.right.toDouble()))
        }
        return firstHalf to secondHalf
    }

    val (topLeft, topRight) = upper(left, right, false)
    val (bottomLeft, bottomRight) = upper(left.mirrorY(), right.mirrorY(), true)

    // Stitch the native arcs and their tangent bridges into the actual boundary.
    // Boolean union can replace nearly coincident tangent curves with closure chords.
    return ContactGeometry(Path().apply {
        fun contour(top: List<ContactSegment>, bottom: List<ContactSegment>) {
            if (top.isEmpty()) return
            moveTo(top.first().from.x.toFloat(), top.first().from.y.toFloat())
            top.forEach { it.drawTo(this) }
            bottom.asReversed().forEach { it.mirrorAndReverse().drawTo(this) }
            close()
        }
        if (joined) contour(topLeft + topRight, bottomLeft + bottomRight)
        else {
            contour(topLeft, bottomLeft)
            contour(topRight, bottomRight)
        }
    }, true)
}

/** A G2 connector whose endpoint speeds can vanish independently at pinch-off. */
private fun contactSpline(start: CurveJet, end: CurveJet, startSpeed: Double, endSpeed: Double, nativeStart: Boolean): List<PathSegment.Cubic> {
    if (end.position.x <= start.position.x) return emptyList()
    val normalStart = Point(-start.tangent.y, start.tangent.x)
    val normalEnd = Point(-end.tangent.y, end.tangent.x)
    val delta = end.position - start.position
    var firstSpeed = startSpeed
    var lastSpeed = endSpeed
    val firstCurvatureX = start.curvature * normalStart.x
    val lastCurvatureX = end.curvature * normalEnd.x
    // Parameter speeds can change without changing either endpoint's tangent or
    // curvature. Keep the control polygon ordered even for vertically offset lobes.
    if (abs(firstCurvatureX) > 1e-12) firstSpeed = min(firstSpeed, sqrt(0.9 * delta.x / abs(firstCurvatureX)))
    if (abs(lastCurvatureX) > 1e-12) lastSpeed = min(lastSpeed, sqrt(0.9 * delta.x / abs(lastCurvatureX)))
    if (firstCurvatureX < 0.0) firstSpeed = min(firstSpeed, 2.0 * maxOf(0.0, start.tangent.x) / -firstCurvatureX)
    if (lastCurvatureX > 0.0) lastSpeed = min(lastSpeed, 2.0 * maxOf(0.0, end.tangent.x) / lastCurvatureX)
    val handle = sqrt(delta.x * delta.x + delta.y * delta.y) * 0.55
    val reference = PathSegment.Cubic(
        start.position,
        start.position + start.tangent * if (nativeStart) handle else min(handle, firstSpeed * 5.0 / 3.0),
        end.position - end.tangent * if (nativeStart) min(handle, lastSpeed * 5.0 / 3.0) else handle,
        end.position,
    )
    val firstControl = start.position + start.tangent * firstSpeed + normalStart * (start.curvature * firstSpeed * firstSpeed / 3.0)
    val lastControl = end.position - end.tangent * lastSpeed + normalEnd * (end.curvature * lastSpeed * lastSpeed / 3.0)
    val firstInterior = reference.point(0.38).let { Point(it.x.coerceIn(firstControl.x, lastControl.x), it.y) }
    val lastInterior = reference.point(0.62).let { Point(it.x.coerceIn(firstInterior.x, lastControl.x), it.y) }
    val controls = listOf(
        start.position,
        start.position + start.tangent * (firstSpeed / 3.0),
        firstControl,
        firstInterior,
        lastInterior,
        lastControl,
        end.position - end.tangent * (lastSpeed / 3.0),
        end.position,
    )
    val knots = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 5.0, 5.0, 5.0)
    val derivative = controls.zipWithNext().mapIndexed { index, (first, second) ->
        (second - first) * (3.0 / (knots[index + 4] - knots[index + 1]))
    }
    val derivativeKnots = knots.copyOfRange(1, knots.lastIndex)
    var first = evaluateSpline(controls, knots, 3, 0.0)
    var firstDerivative = evaluateSpline(derivative, derivativeKnots, 2, 0.0)
    return (0..4).map { index ->
        val parameter = index.toDouble() + 1.0
        val last = evaluateSpline(controls, knots, 3, parameter)
        val lastDerivative = evaluateSpline(derivative, derivativeKnots, 2, parameter)
        PathSegment.Cubic(first, first + firstDerivative / 3.0, last - lastDerivative / 3.0, last).also {
            first = last
            firstDerivative = lastDerivative
        }
    }
}

/** Library G2 capsule segments connected by a curvature-matched cubic B-spline. */
private data class ToolbarActionShape(
    val runs: List<List<ToolbarActionOutline?>>,
    val separation: Float,
    private val lensTopStart: CornerSize = CornerSize(50),
    private val lensTopEnd: CornerSize = CornerSize(50),
    private val lensBottomEnd: CornerSize = CornerSize(50),
    private val lensBottomStart: CornerSize = CornerSize(50),
) : CornerBasedShape(lensTopStart, lensTopEnd, lensBottomEnd, lensBottomStart) {
    private var cachedOutline: Outline? = null

    // Backdrop's lens requires corner metadata even when the clipping outline is a path.
    // Retain its capsule refraction while the precise liquid outline clips all passes.
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ToolbarActionShape(runs, separation, topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        // Geometry is immutable and already expressed in local pixels. The shadow,
        // backdrop clip and highlights can therefore reuse the same exact path.
        cachedOutline?.let { return it }
        val path = Path()
        runs.forEach { run ->
            val connected = mutableListOf<ToolbarActionOutline>()
            run.forEach { action ->
                val previous = connected.lastOrNull()
                if (action == null || (previous != null &&
                    maxOf(separation, previous.separation, action.separation) >= 1f)
                ) {
                    appendContinuousActions(path, connected, separation)
                    connected.clear()
                }
                if (action != null) connected.add(action)
            }
            appendContinuousActions(path, connected, separation)
        }
        return Outline.Generic(path).also { cachedOutline = it }
    }
}

private fun continuousActionSegments(bounds: Rect): List<PathSegment> {
    val radius = min(bounds.width, bounds.height).toDouble() / 2.0
    val origin = Point(bounds.left.toDouble(), bounds.top.toDouble())
    return ToolbarContinuousShape.continuity.createRoundedRectanglePathSegments(
        bounds.width.toDouble(), bounds.height.toDouble(), radius, radius, radius, radius,
    ).map { segment ->
        when (segment) {
            is PathSegment.Line -> PathSegment.Line(segment.from + origin, segment.to + origin)
            is PathSegment.Arc -> segment.copy(center = segment.center + origin)
            is PathSegment.Circle -> segment.copy(center = segment.center + origin)
            is PathSegment.Cubic -> PathSegment.Cubic(
                segment.p0 + origin, segment.p1 + origin, segment.p2 + origin, segment.p3 + origin,
            )
        }
    }
}

private fun appendContinuousActions(path: Path, actions: List<ToolbarActionOutline>, separation: Float) {
    if (actions.isEmpty()) return
    val edges = actions.zipWithNext { left, right -> maxOf(separation, left.separation, right.separation).toDouble() }
    if (actions.size == 1 || edges.all { it == 0.0 }) {
        val bounds = Rect(
            actions.first().bounds.left,
            actions.minOf { it.bounds.top },
            actions.last().bounds.right,
            actions.maxOf { it.bounds.bottom },
        )
        path.addPath(continuousActionSegments(bounds).toPath())
        return
    }

    // Bring the inner side arcs into place before the neck becomes thin. The broad
    // connection then follows those arcs instead of cutting a notch into a flat top.
    val retraction = edges.map { 1.0 - (1.0 - it) * (1.0 - it) * (1.0 - it) }
    val left = DoubleArray(actions.size) { actions[it].bounds.left.toDouble() }
    val right = DoubleArray(actions.size) { actions[it].bounds.right.toDouble() }
    for (index in 1..actions.lastIndex) left[index] = interpolate(left[index - 1], left[index], retraction[index - 1])
    for (index in actions.lastIndex - 1 downTo 0) right[index] = interpolate(right[index + 1], right[index], retraction[index])
    val profiles = actions.mapIndexed { index, action ->
        ContinuousActionProfile(Rect(left[index].toFloat(), action.bounds.top, right[index].toFloat(), action.bounds.bottom))
    }
    val starts = left.copyOf()
    val ends = right.copyOf()
    edges.forEachIndexed { index, progress ->
        val first = actions[index].bounds
        val second = actions[index + 1].bounds
        val center = (first.right.toDouble() + second.left) / 2.0
        if (progress == 0.0) {
            ends[index] = center
            starts[index + 1] = center
        } else {
            val joinedProfile = ContinuousActionProfile(Rect(
                left[index].toFloat(), min(first.top, second.top),
                right[index + 1].toFloat(), maxOf(first.bottom, second.bottom),
            ))
            val firstRadius = min(first.width, first.height).toDouble() / 2.0
            val secondRadius = min(second.width, second.height).toDouble() / 2.0
            val initialEnd = (first.right - firstRadius).coerceIn(joinedProfile.flatTop)
            val initialStart = (second.left + secondRadius).coerceIn(joinedProfile.flatTop)
            ends[index] = interpolate(initialEnd, first.right.toDouble(), progress)
            starts[index + 1] = interpolate(initialStart, second.left.toDouble(), progress)
        }
    }
    val upper = mutableListOf<PathSegment>()
    profiles.forEachIndexed { index, profile ->
        upper.addAll(profile.trim(starts[index], ends[index]))
        if (index < actions.lastIndex) {
            val progress = edges[index]
            if (progress > 0.0) {
                val first = actions[index].bounds
                val second = actions[index + 1].bounds
                val middleY = (first.center.y.toDouble() + second.center.y) / 2.0
                val radius = min(first.height, second.height).toDouble() / 2.0
                // Keep a full, rounded neck through the early stretch, then let it
                // narrow between the actual side arcs before the shapes detach.
                val neckProgress = progress * progress * progress * (10.0 + progress * (-15.0 + 6.0 * progress))
                val neckY = middleY - radius * cos(PI / 2.0 * neckProgress)
                upper.addAll(curvatureMatchedBridge(
                    profile.jet(ends[index]), profiles[index + 1].jet(starts[index + 1]),
                    neckY, radius, progress,
                ))
            }
        }
    }
    if (upper.isEmpty()) return
    path.moveTo(upper.first().from.x.toFloat(), upper.first().from.y.toFloat())
    upper.forEach { it.drawTo(path) }
    val centerY = actions.first().bounds.center.y.toDouble()
    upper.asReversed().forEach { it.reflectAndReverse(centerY).drawTo(path) }
    path.close()
}

private data class CurveJet(val position: Point, val tangent: Point, val curvature: Double)

/** Retains the library's actual arcs and cubics, including their G2 corner transitions. */
private class ContinuousActionProfile(bounds: Rect, segments: List<PathSegment> = continuousActionSegments(bounds)) {
    private val upper = segments.flatMap { segment ->
        when (segment) {
            is PathSegment.Circle -> listOf(PathSegment.Arc(segment.center, segment.radius, PI, PI))
            is PathSegment.Arc -> (-2..2).mapNotNull { turn ->
                val start = maxOf(segment.startAngle, PI + turn * 2.0 * PI)
                val end = minOf(segment.startAngle + segment.sweepAngle, 2.0 * PI + turn * 2.0 * PI)
                if (end > start) segment.copy(startAngle = start, sweepAngle = end - start) else null
            }
            else -> if (segment.to.x >= segment.from.x &&
                segment.from.y <= bounds.center.y + 0.001 && segment.to.y <= bounds.center.y + 0.001
            ) listOf(segment) else emptyList()
        }
    }.filterNot { it is PathSegment.Line && it.from == it.to }.sortedBy { it.from.x }

    val flatTop: ClosedFloatingPointRange<Double> = upper.filterIsInstance<PathSegment.Line>()
        .firstOrNull { it.from.y == bounds.top.toDouble() && it.to.y == bounds.top.toDouble() }
        ?.let { it.from.x..it.to.x }
        ?: (bounds.center.x.toDouble()..bounds.center.x.toDouble())

    fun jet(x: Double): CurveJet {
        val segment = upper.firstOrNull { x >= it.from.x - 1e-6 && x <= it.to.x + 1e-6 } ?: upper.last()
        return jet(segment, segment.parameterAt(x))
    }

    fun jetAt(fraction: Double): CurveJet {
        val progress = fraction.coerceIn(0.0, 1.0) * upper.size
        val index = progress.toInt().coerceAtMost(upper.lastIndex)
        return jet(upper[index], progress - index)
    }

    private fun jet(segment: PathSegment, parameter: Double): CurveJet {
        val first = segment.derivative(parameter)
        val second = segment.secondDerivative(parameter)
        val speed = sqrt(first.x * first.x + first.y * first.y)
        return CurveJet(
            segment.point(parameter), first / speed,
            (first.x * second.y - first.y * second.x) / (speed * speed * speed),
        )
    }

    fun trim(startX: Double, endX: Double): List<PathSegment> = upper.mapNotNull { segment ->
        if (segment.to.x < startX || segment.from.x > endX) return@mapNotNull null
        val from = if (startX <= segment.from.x) 0.0 else segment.parameterAt(startX)
        val to = if (endX >= segment.to.x) 1.0 else segment.parameterAt(endX)
        if (to <= from) null else segment.trim(from, to)
    }
}

private fun PathSegment.parameterAt(x: Double): Double {
    if (x <= from.x) return 0.0
    if (x >= to.x) return 1.0
    var low = 0.0
    var high = 1.0
    repeat(30) {
        val middle = (low + high) / 2.0
        if (point(middle).x < x) low = middle else high = middle
    }
    return (low + high) / 2.0
}

private fun PathSegment.point(t: Double): Point = when (this) {
    is PathSegment.Line -> from * (1.0 - t) + to * t
    is PathSegment.Arc -> Point(center.x + radius * cos(startAngle + sweepAngle * t), center.y + radius * sin(startAngle + sweepAngle * t))
    is PathSegment.Circle -> error("Circle segments are expanded before evaluation")
    is PathSegment.Cubic -> {
        val s = 1.0 - t
        p0 * (s * s * s) + p1 * (3.0 * s * s * t) + p2 * (3.0 * s * t * t) + p3 * (t * t * t)
    }
}

private fun PathSegment.derivative(t: Double): Point = when (this) {
    is PathSegment.Line -> to - from
    is PathSegment.Arc -> Point(-sin(startAngle + sweepAngle * t), cos(startAngle + sweepAngle * t)) * (radius * sweepAngle)
    is PathSegment.Circle -> error("Circle segments are expanded before evaluation")
    is PathSegment.Cubic -> (p1 - p0) * (3.0 * (1.0 - t) * (1.0 - t)) +
        (p2 - p1) * (6.0 * (1.0 - t) * t) + (p3 - p2) * (3.0 * t * t)
}

private fun PathSegment.secondDerivative(t: Double): Point = when (this) {
    is PathSegment.Line -> Point.Zero
    is PathSegment.Arc -> Point(-cos(startAngle + sweepAngle * t), -sin(startAngle + sweepAngle * t)) * (radius * sweepAngle * sweepAngle)
    is PathSegment.Circle -> error("Circle segments are expanded before evaluation")
    is PathSegment.Cubic -> (p2 - p1 * 2.0 + p0) * (6.0 * (1.0 - t)) + (p3 - p2 * 2.0 + p1) * (6.0 * t)
}

private fun PathSegment.trim(from: Double, to: Double): PathSegment = when (this) {
    is PathSegment.Line -> PathSegment.Line(point(from), point(to))
    is PathSegment.Arc -> copy(startAngle = startAngle + sweepAngle * from, sweepAngle = sweepAngle * (to - from))
    is PathSegment.Circle -> error("Circle segments are expanded before trimming")
    is PathSegment.Cubic -> {
        val first = point(from)
        val last = point(to)
        PathSegment.Cubic(
            first, first + derivative(from) * ((to - from) / 3.0),
            last - derivative(to) * ((to - from) / 3.0), last,
        )
    }
}

private fun PathSegment.reflectAndReverse(centerY: Double): PathSegment {
    fun Point.reflect() = Point(x, 2.0 * centerY - y)
    return when (this) {
        is PathSegment.Line -> PathSegment.Line(to.reflect(), from.reflect())
        is PathSegment.Arc -> copy(center = center.reflect(), startAngle = -startAngle - sweepAngle)
        is PathSegment.Circle -> copy(center = center.reflect())
        is PathSegment.Cubic -> PathSegment.Cubic(p3.reflect(), p2.reflect(), p1.reflect(), p0.reflect())
    }
}

private fun curvatureMatchedBridge(start: CurveJet, end: CurveJet, neckY: Double, radius: Double, separation: Double): List<PathSegment.Cubic> {
    val span = end.position.x - start.position.x
    if (span <= 0.0) return emptyList()
    val speed = min(span * 0.3, radius * (1.0 - separation) * 0.7)
    val normalStart = Point(-start.tangent.y, start.tangent.x)
    val normalEnd = Point(-end.tangent.y, end.tangent.x)
    val middle = (start.position.x + end.position.x) / 2.0
    // For the clamped uniform cubic spline, C'(0)=3(P1-P0) and
    // C''(0)=3(P2-3P1+2P0). These controls therefore match the library jet exactly:
    // C'=v*T, C''=k*v*v*N. The end uses the same equations in reverse.
    val controls = listOf(
        start.position,
        start.position + start.tangent * (speed / 3.0),
        start.position + start.tangent * speed + normalStart * (start.curvature * speed * speed / 3.0),
        Point(middle - span * 0.12, neckY),
        Point(middle + span * 0.12, neckY),
        end.position - end.tangent * speed + normalEnd * (end.curvature * speed * speed / 3.0),
        end.position - end.tangent * (speed / 3.0),
        end.position,
    )
    val knots = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 5.0, 5.0, 5.0)
    val derivative = controls.zipWithNext().mapIndexed { index, (first, second) ->
        (second - first) * (3.0 / (knots[index + 4] - knots[index + 1]))
    }
    val derivativeKnots = knots.copyOfRange(1, knots.lastIndex)
    // Every interior knot is simple, so the entire bridge is C2. Each span is already
    // a cubic polynomial: endpoint positions/derivatives convert it exactly, not by sampling.
    var first = evaluateSpline(controls, knots, 3, 0.0)
    var firstDerivative = evaluateSpline(derivative, derivativeKnots, 2, 0.0)
    return (0..4).map { index ->
        val to = index.toDouble() + 1.0
        val last = evaluateSpline(controls, knots, 3, to)
        val lastDerivative = evaluateSpline(derivative, derivativeKnots, 2, to)
        PathSegment.Cubic(
            first, first + firstDerivative / 3.0,
            last - lastDerivative / 3.0, last,
        ).also {
            first = last
            firstDerivative = lastDerivative
        }
    }
}

private fun evaluateSpline(controls: List<Point>, knots: DoubleArray, degree: Int, parameter: Double): Point {
    val span = if (parameter >= knots[controls.size]) controls.lastIndex else
        (degree..controls.lastIndex).first { parameter < knots[it + 1] }
    val points = MutableList(degree + 1) { controls[span - degree + it] }
    for (level in 1..degree) {
        for (index in degree downTo level) {
            val knot = span - degree + index
            val weight = (parameter - knots[knot]) / (knots[knot + degree - level + 1] - knots[knot])
            points[index] = points[index - 1] * (1.0 - weight) + points[index] * weight
        }
    }
    return points[degree]
}

private fun interpolate(start: Double, end: Double, fraction: Double) = start + (end - start) * fraction
