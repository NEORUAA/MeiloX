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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
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
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import com.ljyh.mei.ui.liquidglass.PressHighlightRenderer
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
    val group = remember(density) { ToolbarActionGroup(with(density) { 8.dp.toPx() }, density, progress) }
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
    val member = remember(group, highlight, material) {
        group?.let { ToolbarActionMember(it, material, currentSeparation, highlight) }
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
) {
    var coordinates: LayoutCoordinates? = null
    var bounds: Rect? by mutableStateOf(null)

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
    }
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
) {
    private val pressScope = GraphicsLayerBlockScope()
    private val pressRenderer = PressHighlightRenderer()

    val members = mutableStateListOf<ToolbarActionMember>()
    var coordinates: LayoutCoordinates? = null

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
private class ContinuousActionProfile(bounds: Rect) {
    private val upper = continuousActionSegments(bounds).flatMap { segment ->
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
        val parameter = segment.parameterAt(x)
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
