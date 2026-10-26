package com.ljyh.mei.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable geometry key; a frame workspace may retain its buffers without changing the skin. */
internal data class LiquidMenuContour(
    val incoming: Rect,
    val incomingRadii: Offset,
    val outgoing: Rect?,
    val outgoingRadii: Offset,
    val smoothing: Float,
    private val workspace: LiquidMenuContourWorkspace? = null,
) : Shape {
    private val incomingField = RoundedMenuField(incoming, incomingRadii)
    private val outgoingField = outgoing?.let { RoundedMenuField(it, outgoingRadii) }
    private val blendRadius = smoothing.coerceAtLeast(0f)
    private val localWorkspace by lazy(LazyThreadSafetyMode.NONE) { LiquidMenuContourWorkspace() }

    fun path(size: Size): Path = (workspace ?: localWorkspace).path(this, size)

    internal fun sampleBoundary(size: Size): FloatArray = (workspace ?: localWorkspace).vertices(this, size)

    internal fun distance(point: Offset): Float = distance(point.x, point.y)

    internal fun distance(x: Float, y: Float): Float {
        val field = sampledField()
        return field?.distance(x, y) ?: rawDistance(x, y)
    }

    internal fun sampledField(): LiquidMenuGaussianField? =
        (workspace ?: localWorkspace).gaussianField(this)

    internal fun rawDistance(point: Offset): Float = rawDistance(point.x, point.y)

    internal fun rawDistance(x: Float, y: Float): Float {
        val a = incomingField.distance(x, y)
        val source = outgoingField ?: return a
        val b = source.distance(x, y)
        if (abs(a - b) >= blendRadius) return min(a, b)
        // CASDF's SDFUnion scales its polynomial blend by the opposing field normals.
        // Parallel edges stay fixed; facing edges receive at most smoothness / 4 lift.
        val normalA = incomingField.gradient(x, y)
        val normalB = source.gradient(x, y)
        val alignment = normalA.x * normalB.x + normalA.y * normalB.y
        val radius = blendRadius * (0.5f - 0.5f * alignment).coerceIn(0f, 1f)
        if (radius <= 0.000001f || abs(a - b) >= radius) return min(a, b)
        val weight = (0.5f + 0.5f * (a - b) / radius).coerceIn(0f, 1f)
        return a + (b - a) * weight - radius * weight * (1f - weight)
    }

    internal fun rawSample(x: Float, y: Float, output: FloatArray, index: Int) {
        val a = incomingField.distance(x, y)
        val source = outgoingField
        val b = source?.distance(x, y) ?: Float.POSITIVE_INFINITY
        if (source == null || abs(a - b) >= blendRadius) {
            val normal = if (source == null || a <= b) incomingField.gradient(x, y) else source.gradient(x, y)
            output[index] = min(a, b)
            output[index + 1] = normal.x
            output[index + 2] = normal.y
            return
        }
        val normalA = incomingField.gradient(x, y)
        val normalB = source.gradient(x, y)
        val alignment = normalA.x * normalB.x + normalA.y * normalB.y
        val radius = blendRadius * (0.5f - 0.5f * alignment).coerceIn(0f, 1f)
        val weight = if (radius <= 0.000001f) {
            if (a <= b) 0f else 1f
        } else (0.5f + 0.5f * (a - b) / radius).coerceIn(0f, 1f)
        output[index] = a + (b - a) * weight - radius * weight * (1f - weight)
        output[index + 1] = normalA.x + (normalB.x - normalA.x) * weight
        output[index + 2] = normalA.y + (normalB.y - normalA.y) * weight
    }

    internal fun exteriorBounds(): Rect {
        val source = outgoing
        val base = if (source == null) incoming else Rect(
            min(incoming.left, source.left), min(incoming.top, source.top),
            max(incoming.right, source.right), max(incoming.bottom, source.bottom),
        )
        // The generalized corner field scales by its smaller radius. Account for that
        // anisotropy when bounding CASDF's maximum polynomial union expansion.
        val aspect = max(incomingField.cornerAspect, outgoingField?.cornerAspect ?: 1f)
        return base.inflate(if (source == null) 0.001f else blendRadius * 0.25f * aspect + 0.001f)
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(path(size))
}

/**
 * Reuse once per menu surface. It preserves all 96 contour samples and certifies each radial
 * root to 1/32 px, while secant steps and last-frame seeds avoid 14 blind field evaluations.
 */
internal class LiquidMenuContourWorkspace {
    private val blurredField = LiquidMenuGaussianField()
    private var geometry: LiquidMenuContour? = null
    private var size: Size? = null
    private var pathGeometry: LiquidMenuContour? = null
    private var pathSize: Size? = null
    private var reusablePath: Path? = null
    private val points = FloatArray(SampleCount * 2)
    private val radii = FloatArray(SampleCount)
    private var hasSeeds = false
    internal var fieldEvaluations = 0
        private set
    internal var totalFieldEvaluations = 0L
        private set

    internal fun gaussianField(contour: LiquidMenuContour): LiquidMenuGaussianField? =
        blurredField.update(contour)

    fun vertices(contour: LiquidMenuContour, size: Size): FloatArray {
        if (geometry == contour && this.size == size) return points
        val source = contour.outgoing
        val sourceArea = source?.let { it.width * it.height } ?: 0f
        val destinationArea = contour.incoming.width * contour.incoming.height
        val weight = sourceArea / (sourceArea + destinationArea)
        val centerX = if (source != null) contour.incoming.center.x * (1f - weight) + source.center.x * weight else contour.incoming.center.x
        val centerY = if (source != null) contour.incoming.center.y * (1f - weight) + source.center.y * weight else contour.incoming.center.y
        val exterior = contour.exteriorBounds()
        fieldEvaluations = 0
        val centerDistance = evaluate(contour, centerX, centerY)
        repeat(SampleCount) { index ->
            val dx = Directions[index * 2]
            val dy = Directions[index * 2 + 1]
            val horizontal = when {
                dx > 0.000001f -> (exterior.right - centerX) / dx
                dx < -0.000001f -> (exterior.left - centerX) / dx
                else -> Float.POSITIVE_INFINITY
            }
            val vertical = when {
                dy > 0.000001f -> (exterior.bottom - centerY) / dy
                dy < -0.000001f -> (exterior.top - centerY) / dy
                else -> Float.POSITIVE_INFINITY
            }
            val radius = solveRadius(
                contour, centerX, centerY, dx, dy,
                min(horizontal, vertical).coerceAtLeast(RootTolerance), centerDistance,
                if (hasSeeds) radii[index] else Float.NaN,
            )
            radii[index] = radius
            points[index * 2] = centerX + dx * radius
            points[index * 2 + 1] = centerY + dy * radius
        }
        hasSeeds = true
        geometry = contour
        this.size = size
        return points
    }

    fun path(contour: LiquidMenuContour, size: Size): Path {
        if (pathGeometry == contour && pathSize == size) return requireNotNull(reusablePath)
        vertices(contour, size)
        val path = reusablePath ?: Path().also { reusablePath = it }
        path.rewind()
        path.moveTo(points[0], points[1])
        repeat(SampleCount) { index ->
            val a = (index + SampleCount - 1) % SampleCount * 2
            val b = index * 2
            val c = (index + 1) % SampleCount * 2
            val d = (index + 2) % SampleCount * 2
            path.cubicTo(
                points[b] + (points[c] - points[a]) / 6f,
                points[b + 1] + (points[c + 1] - points[a + 1]) / 6f,
                points[c] - (points[d] - points[b]) / 6f,
                points[c + 1] - (points[d + 1] - points[b + 1]) / 6f,
                points[c], points[c + 1],
            )
        }
        path.close()
        pathGeometry = contour
        pathSize = size
        return path
    }

    private fun solveRadius(
        contour: LiquidMenuContour,
        cx: Float, cy: Float, dx: Float, dy: Float,
        limit: Float, centerDistance: Float, seed: Float,
    ): Float {
        var low = 0f
        var high = limit
        var below = centerDistance
        var above = evaluate(contour, cx + dx * high, cy + dy * high)
        // A conservative field bound should already be outside. Retain the old solver's
        // safety for unexpected geometry instead of clipping an extreme spring excursion.
        repeat(4) {
            if (above < 0f) {
                high *= 1.5f
                above = evaluate(contour, cx + dx * high, cy + dy * high)
            }
        }
        if (abs(above) < 0.000001f) return high
        if (seed > low && seed < high) {
            val value = evaluate(contour, cx + dx * seed, cy + dy * seed)
            if (value <= 0f) { low = seed; below = value } else { high = seed; above = value }
        }
        repeat(20) { iteration ->
            if (high - low <= RootTolerance * 2f) return (low + high) / 2f
            val secant = (low * above - high * below) / (above - below)
            val radius = if (iteration % 3 == 2 || !secant.isFinite() || secant <= low || secant >= high) {
                (low + high) / 2f
            } else secant
            val value = evaluate(contour, cx + dx * radius, cy + dy * radius)
            if (abs(value) < 0.2f) {
                val left = (radius - RootTolerance).coerceAtLeast(low)
                val right = (radius + RootTolerance).coerceAtMost(high)
                val leftValue = evaluate(contour, cx + dx * left, cy + dy * left)
                val rightValue = evaluate(contour, cx + dx * right, cy + dy * right)
                if (leftValue <= 0f && rightValue >= 0f) return radius
            }
            if (value <= 0f) { low = radius; below = value } else { high = radius; above = value }
        }
        // This is rarely reached for a smooth skin; continue with a bounded bisection.
        while (high - low > RootTolerance * 2f) {
            val radius = (low + high) / 2f
            if (evaluate(contour, cx + dx * radius, cy + dy * radius) <= 0f) low = radius else high = radius
        }
        return (low + high) / 2f
    }

    private fun evaluate(contour: LiquidMenuContour, x: Float, y: Float): Float {
        fieldEvaluations++
        totalFieldEvaluations++
        return contour.distance(x, y)
    }

    private companion object {
        const val SampleCount = 96
        const val RootTolerance = 1f / 32f
        val Directions = FloatArray(SampleCount * 2).apply {
            repeat(SampleCount) { index ->
                val angle = index * (2.0 * PI / SampleCount)
                this[index * 2] = cos(angle).toFloat()
                this[index * 2 + 1] = sin(angle).toFloat()
            }
        }
    }
}

/**
 * A separable discretization of CASDFGaussian's exp(-x*x/(2*sigma*sigma)) kernel.
 * CPU roots and the F16 shader buffer use these same texel centers and bilinear samples.
 * The four-pixel sigma bounds the kernel work, as the native renderer bounds its narrow
 * kernel after downsampling. This is a numerical Gaussian approximation, not a shape fit.
 */
internal class LiquidMenuGaussianField {
    var origin: Offset = Offset.Zero
        private set
    var step: Float = 1f
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var rgba: FloatArray = FloatArray(0)
        private set
    var generation: Long = 0L
        private set
    private var geometry: LiquidMenuContour? = null
    private var active = false
    private var raw = FloatArray(0)
    private var horizontal = FloatArray(0)
    private val weights = FloatArray(17)
    private val nativeGeometry = FloatArray(14)

    internal fun update(contour: LiquidMenuContour): LiquidMenuGaussianField? {
        if (geometry == contour) return if (active) this else null
        geometry = contour
        val sigma = contour.smoothing.coerceAtLeast(0f)
        if (sigma <= 0.000001f) {
            active = false
            return null
        }
        step = max(2f, sigma / 4f)
        val localSigma = sigma / step
        val maximumRadius = ceil(localSigma * 4f).toInt().coerceAtMost(weights.lastIndex)
        var sum = 0f
        for (index in 0..maximumRadius) {
            val value = exp(-index * index / (2f * localSigma * localSigma))
            weights[index] = value
            sum += if (index == 0) value else value * 2f
        }
        for (index in 0..maximumRadius) weights[index] /= sum
        var radius = maximumRadius
        while (radius > 0 && weights[radius] < 0.002f) radius--
        if (radius == 0) {
            active = false
            return null
        }
        sum = weights[0]
        for (index in 1..radius) sum += weights[index] * 2f
        for (index in 0..radius) weights[index] /= sum

        val bounds = contour.exteriorBounds()
        // Coverage probes the field one pixel beyond the moving skin. Keep an output
        // texel on every side so those derivatives never hit the texture clamp. Moving
        // by one whole step preserves the original interior sampling lattice.
        origin = bounds.topLeft - Offset(step, step)
        width = max(2, ceil(bounds.width / step).toInt() + 1) + 2
        height = max(2, ceil(bounds.height / step).toInt() + 1) + 2
        val paddedWidth = width + radius * 2
        val paddedHeight = height + radius * 2
        val rawSize = paddedWidth * paddedHeight * 3
        val horizontalSize = width * paddedHeight * 3
        val outputSize = width * height * 4
        if (rgba.size < outputSize) rgba = FloatArray(outputSize)

        fun geometry(bounds: Rect?, radii: Offset, index: Int) {
            nativeGeometry[index] = bounds?.left ?: 0f
            nativeGeometry[index + 1] = bounds?.top ?: 0f
            nativeGeometry[index + 2] = bounds?.width ?: 0f
            nativeGeometry[index + 3] = bounds?.height ?: 0f
            nativeGeometry[index + 4] = radii.x
            nativeGeometry[index + 5] = radii.y
        }
        geometry(contour.incoming, contour.incomingRadii, 0)
        geometry(contour.outgoing, contour.outgoingRadii, 6)
        nativeGeometry[12] = contour.smoothing
        nativeGeometry[13] = if (contour.outgoing != null) 1f else 0f
        if (LiquidMenuNativeField.build(nativeGeometry, origin.x, origin.y, step,
                width, height, weights, radius, rgba)
        ) {
            active = true
            generation++
            return this
        }

        // The portable JVM implementation is also the numerical reference for the JNI path.
        if (raw.size < rawSize) raw = FloatArray(rawSize)
        if (horizontal.size < horizontalSize) horizontal = FloatArray(horizontalSize)

        // The halo evaluates the actual analytic field, so convolution never samples an
        // artificial transparent or clamped boundary around the source orb or the menu.
        for (row in 0 until paddedHeight) {
            val y = origin.y + (row - radius) * step
            for (column in 0 until paddedWidth) {
                contour.rawSample(origin.x + (column - radius) * step, y, raw,
                    (row * paddedWidth + column) * 3)
            }
        }
        for (row in 0 until paddedHeight) {
            for (column in 0 until width) {
                val center = (row * paddedWidth + column + radius) * 3
                var distance = raw[center] * weights[0]
                var nx = raw[center + 1] * weights[0]
                var ny = raw[center + 2] * weights[0]
                for (offset in 1..radius) {
                    val left = center - offset * 3
                    val right = center + offset * 3
                    val weight = weights[offset]
                    distance += (raw[left] + raw[right]) * weight
                    nx += (raw[left + 1] + raw[right + 1]) * weight
                    ny += (raw[left + 2] + raw[right + 2]) * weight
                }
                val output = (row * width + column) * 3
                horizontal[output] = distance
                horizontal[output + 1] = nx
                horizontal[output + 2] = ny
            }
        }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val center = ((row + radius) * width + column) * 3
                var distance = horizontal[center] * weights[0]
                var nx = horizontal[center + 1] * weights[0]
                var ny = horizontal[center + 2] * weights[0]
                for (offset in 1..radius) {
                    val above = center - offset * width * 3
                    val below = center + offset * width * 3
                    val weight = weights[offset]
                    distance += (horizontal[above] + horizontal[below]) * weight
                    nx += (horizontal[above + 1] + horizontal[below + 1]) * weight
                    ny += (horizontal[above + 2] + horizontal[below + 2]) * weight
                }
                // Native sdf_gaussian_resolve divides R by convolved A and normalizes GB.
                // A is one throughout this analytic field and its complete padded halo.
                val length = sqrt(nx * nx + ny * ny)
                val output = (row * width + column) * 4
                rgba[output] = distance
                rgba[output + 1] = if (length > 0.000001f) nx / length else 0f
                rgba[output + 2] = if (length > 0.000001f) ny / length else 0f
                rgba[output + 3] = 1f
            }
        }
        active = true
        generation++
        return this
    }

    internal fun distance(x: Float, y: Float): Float {
        val fx = ((x - origin.x) / step).coerceIn(0f, (width - 1).toFloat())
        val fy = ((y - origin.y) / step).coerceIn(0f, (height - 1).toFloat())
        val column = fx.toInt().coerceAtMost(width - 2)
        val row = fy.toInt().coerceAtMost(height - 2)
        val tx = fx - column
        val ty = fy - row
        val index = (row * width + column) * 4
        val a = rgba[index]
        val b = rgba[index + 4]
        val c = rgba[index + width * 4]
        val d = rgba[index + width * 4 + 4]
        return (a + (b - a) * tx) * (1f - ty) + (c + (d - c) * tx) * ty
    }
}

/** CASDF continuous-corner sampler, evaluated in its isotropic layer coordinate space. */
private class RoundedMenuField(bounds: Rect, radii: Offset) {
    private val centerX = bounds.center.x
    private val centerY = bounds.center.y
    private val radiusX = radii.x.coerceAtLeast(0.01f)
    private val radiusY = radii.y.coerceAtLeast(0.01f)
    private val radius = min(radiusX, radiusY)
    private val inverseAxisX = radius / radiusX
    private val inverseAxisY = radius / radiusY
    private val halfWidth = bounds.width / 2f * inverseAxisX
    private val halfHeight = bounds.height / 2f * inverseAxisY
    private val expandedRadius = radius * CornerExpansion
    private val curvatureX = (2.8915570351735034f - 1.4457785175867517f * (halfWidth * 2f) / expandedRadius).coerceIn(0f, 1f)
    private val curvatureY = (2.8915570351735034f - 1.4457785175867517f * (halfHeight * 2f) / expandedRadius).coerceIn(0f, 1f)
    private val baseRadius = radius * (CornerExpansion - CornerAdjustment * max(curvatureX, curvatureY))
    private val circular = curvatureX == 1f && curvatureY == 1f
    val cornerAspect = max(radiusX, radiusY) / radius

    fun distance(x: Float, y: Float): Float {
        val qx = abs((x - centerX) * inverseAxisX) - halfWidth
        val qy = abs((y - centerY) * inverseAxisY) - halfHeight
        val circularX = qx + baseRadius
        val circularY = qy + baseRadius
        val interior = min(max(circularX, circularY), 0f)
        if (circular) {
            val dx = max(circularX, 0f)
            val dy = max(circularY, 0f)
            return interior + sqrt(dx * dx + dy * dy) - radius
        }
        val ux = (qx + expandedRadius) / expandedRadius
        val uy = (qy + expandedRadius) / expandedRadius
        val zx = max(ux, 0f)
        val zy = max(uy, 0f)
        val maximum = max(zx, zy)
        val t = if (maximum > 0f) min(zx, zy) / maximum else 0f
        val length = sqrt(zx * zx + zy * zy)
        val polynomial = ((((-0.9260540008544922f * t + 3.1560099124908447f) * t -
            3.6412200927734375f) * t + 1.268030047416687f) * t + 0.2685309946537018f) * t * t
        val continuous = length + 1f - 1f / (1f - polynomial * min(length, 1f))
        val ax = max(CornerExpansion * ux - CornerAdjustment, 0f)
        val ay = max(CornerExpansion * uy - CornerAdjustment, 0f)
        val correction = 0.3458344340324402f - continuous + 0.6541655659675598f * sqrt(ax * ax + ay * ay)
        val weight = ((if (zx < zy) 1f else -1f) * (t - 1f) + 0.5f).coerceIn(0f, 1f)
        val corner = continuous - 1f + correction * (curvatureX + (curvatureY - curvatureX) * weight)
        return interior + expandedRadius * corner
    }

    fun gradient(x: Float, y: Float): Offset {
        val localX = (x - centerX) * inverseAxisX
        val localY = (y - centerY) * inverseAxisY
        val qx = abs(localX) - halfWidth + baseRadius
        val qy = abs(localY) - halfHeight + baseRadius
        var nx = max(qx, 0f)
        var ny = max(qy, 0f)
        val cornerLength = sqrt(nx * nx + ny * ny)
        if (cornerLength > 0.000001f) {
            nx /= cornerLength
            ny /= cornerLength
        } else if (qx > qy) {
            nx = 1f
            ny = 0f
        } else {
            nx = 0f
            ny = 1f
        }
        if (localX < 0f) nx = -nx
        if (localY < 0f) ny = -ny
        var ovalX = localX
        var ovalY = localY * halfWidth / halfHeight.coerceAtLeast(0.000001f)
        val ovalLength = sqrt(ovalX * ovalX + ovalY * ovalY)
        if (ovalLength > 0.000001f) {
            ovalX /= ovalLength
            ovalY /= ovalLength
        } else {
            ovalX = nx
            ovalY = ny
        }
        // LiquidMorph sets gradientOvalization to 0.5 before tracking its element layers.
        nx += ovalX
        ny += ovalY
        val length = sqrt(nx * nx + ny * ny)
        if (length <= 0.000001f) return Offset.Zero
        // CASDF applies the inverse affine normal transform after normalization. Its
        // magnitude carries the distance scale and is retained for the union's dot product.
        return Offset(nx / length * inverseAxisX, ny / length * inverseAxisY)
    }

    private companion object {
        const val CornerExpansion = 1.5286649465560913f
        const val CornerAdjustment = 0.5286649465560913f
    }
}
