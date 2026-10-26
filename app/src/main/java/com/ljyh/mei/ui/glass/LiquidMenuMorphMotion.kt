package com.ljyh.mei.ui.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Physical geometry in screen coordinates; the renderer joins both lobes into one field. */
internal data class LiquidMenuMorphFrame(
    val bounds: Rect,
    val radiusX: Float,
    val radiusY: Float,
    val contentProgress: Float,
    val lensProgress: Float,
    val sourceLobe: Rect? = null,
    val sourceRadiusX: Float = 0f,
    val sourceRadiusY: Float = 0f,
    val smoothing: Float = 0f,
    val sourceOpacity: Float = 1f,
    val sourceBlur: Float = 0f,
    val sourceZoom: Float = 1f,
    val contentBlur: Float = 0f,
    val contentZoom: Float = 1f,
    val portalBlur: Float = 0f,
    val lensHeight: Float = 0f,
    val lensAmount: Float = 0f,
)

/**
 * Independent spring channels for the two liquid envelopes, their pivot, and the optical field.
 * Parameters follow AnimationKit V2. Its geometry helper applies withSpeed(0.7),
 * multiplying spring response while retaining each channel's live velocity.
 */
internal class LiquidMenuMorphMotion {
    var initialized by mutableStateOf(false)
        private set
    var frame by mutableStateOf(
        LiquidMenuMorphFrame(Rect.Zero, 0f, 0f, 0f, 1f),
    )
        private set

    private var source = Rect.Zero
    private var through = Rect.Zero
    private var destination = Rect.Zero
    private var destinationRadius = 0f
    private var density = 1f
    private var expanded = false
    private var elapsed = 0f
    private var correctionApplied = false
    private var smoothingReleased = false
    private var lensReleased = false
    private var closingKickReleased = false
    private var sourceKickReleased = false
    private var portalBlurReleased = false
    private var request = 0

    private val x = LiquidMenuSpring()
    private val y = LiquidMenuSpring()
    private val width = LiquidMenuSpring()
    private val height = LiquidMenuSpring()
    private val radiusX = LiquidMenuSpring()
    private val radiusY = LiquidMenuSpring()
    private val sourceX = LiquidMenuSpring()
    private val sourceY = LiquidMenuSpring()
    private val sourceWidth = LiquidMenuSpring()
    private val sourceHeight = LiquidMenuSpring()
    private val sourceScale = LiquidMenuSpring(0.0005f, 0.01f)
    private val incomingScale = LiquidMenuSpring(0.0005f, 0.01f)
    private val kick = LiquidMenuSpring()
    private val sourceKick = LiquidMenuSpring()
    private val content = LiquidMenuSpring(0.001f, 0.01f)
    private val sourceContent = LiquidMenuSpring(0.001f, 0.01f)
    private val lens = LiquidMenuSpring(0.001f, 0.01f)
    private val portalBlur = LiquidMenuSpring(0.001f, 0.01f)
    private val smoothing = LiquidMenuSpring(0.05f, 1f)
    private val channels = listOf(
        x, y, width, height, radiusX, radiusY,
        sourceX, sourceY, sourceWidth, sourceHeight, sourceScale, incomingScale,
        kick, sourceKick, content, sourceContent, lens, portalBlur, smoothing,
    )

    fun initialize(source: Rect, target: Rect, targetRadius: Float, density: Float = 1f, through: Rect = source) {
        require(source.isFiniteAndPositive() && target.isFiniteAndPositive() && through.isFiniteAndPositive())
        require(targetRadius.isFinite() && targetRadius >= 0f)
        require(density.isFinite() && density > 0f)
        this.source = source
        this.through = through
        destination = target
        destinationRadius = targetRadius.coerceAtMost(target.size.minDimension / 2f)
        this.density = density
        val diameter = initialIncomingDiameter()
        val incomingCenter = initialIncomingCenter()
        x.reset(incomingCenter.x)
        y.reset(incomingCenter.y)
        // UIKit starts from a square base item and a separate transform. Multiplying
        // the animated height by that scale keeps the growing droplet rounder than a
        // direct interpolation between the small and final physical rectangles.
        width.reset(target.width)
        height.reset(target.width)
        radiusX.reset(target.width / 2f)
        radiusY.reset(target.width / 2f)
        incomingScale.reset(diameter / target.width)
        sourceX.reset(source.center.x)
        sourceY.reset(source.center.y)
        sourceWidth.reset(source.width)
        sourceHeight.reset(source.height)
        sourceScale.reset(1f)
        kick.reset(0f)
        sourceKick.reset(0f)
        content.reset(0f)
        sourceContent.reset(1f)
        lens.reset(1f)
        portalBlur.reset(0f)
        smoothing.reset(0f)
        expanded = false
        elapsed = 0f
        correctionApplied = false
        smoothingReleased = false
        lensReleased = false
        closingKickReleased = false
        sourceKickReleased = false
        portalBlurReleased = false
        initialized = true
        publish()
    }

    /** Follow the original button's decaying press transform without restarting its springs. */
    fun updateSource(source: Rect, through: Rect = source) {
        if (!initialized || !source.isFiniteAndPositive() || !through.isFiniteAndPositive()) return
        this.source = source
        this.through = through
        if (!expanded) configureCollapse()
    }

    suspend fun animateTo(expanded: Boolean) {
        if (!initialized) return
        val currentRequest = ++request
        retarget(expanded)
        var previousFrame = withFrameNanos { it }
        while (currentRequest == request) {
            val currentFrame = withFrameNanos { it }
            val seconds = ((currentFrame - previousFrame) / 1_000_000_000.0).toFloat()
            previousFrame = currentFrame
            if (advanceBy(seconds)) return
        }
    }

    /** Retarget every live channel. Values and velocities are never reset on interruption. */
    internal fun retarget(expanded: Boolean) {
        if (!initialized) return
        this.expanded = expanded
        elapsed = 0f
        correctionApplied = false
        smoothingReleased = false
        lensReleased = false
        closingKickReleased = false
        sourceKickReleased = expanded
        portalBlurReleased = false
        portalBlur.target(1f, BlurInSpring)
        lens.target(1f, LensOnSpring)
        if (expanded) {
            val pivot = collapsedPivot()
            sourceX.target(pivot.x, EjectSpring)
            sourceY.target(pivot.y, EjectSpring)
            sourceWidth.target(source.size.minDimension, CollapseSpring)
            sourceHeight.target(source.size.minDimension, CollapseSpring)
            // The later zero-delay Eject action owns position/transform/progress; bounds retain
            // their explicit critically damped collapse behavior.
            sourceScale.target(0.25f, EjectSpring)
            x.target(destination.center.x, EjectSpring)
            y.target(destination.center.y, EjectSpring)
            width.target(destination.width, EjectSpring)
            height.target(destination.height, EjectSpring)
            incomingScale.target(1f, EjectSpring)
            // Native Eject first drives all four raw corner radii to the full menu width.
            // Fully expanded later corrects them, retaining that overrounding velocity.
            radiusX.target(destination.width, EjectSpring)
            radiusY.target(destination.width, EjectSpring)
            kick.target(kickAmount(-80f), KickSpring)
            sourceKick.target(0f, KickSpring)
            sourceContent.target(0f, EjectSpring)
            content.target(1f, EjectSpring)
            smoothing.target(20f * density, CollapseSpring)
        } else configureCollapse()
        publish()
    }

    /** Advance the exact second-order solutions, splitting at timeline action boundaries. */
    internal fun advanceBy(seconds: Float): Boolean {
        if (!initialized) return true
        var remaining = seconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        while (remaining > 0f) {
            applyTimelineActions()
            val nextAction = minOf(
                if (expanded && !correctionApplied) CorrectionDelay else Float.POSITIVE_INFINITY,
                if (!lensReleased) opticalReleaseDelay() else Float.POSITIVE_INFINITY,
                if (!portalBlurReleased) opticalReleaseDelay() else Float.POSITIVE_INFINITY,
                if (!smoothingReleased) {
                    if (expanded) SmoothingReleaseDelay else AbsorbSmoothingReleaseDelay
                } else Float.POSITIVE_INFINITY,
                if (!expanded && !closingKickReleased) AbsorbEjecteeUnkickDelay else Float.POSITIVE_INFINITY,
                if (!expanded && !sourceKickReleased) AbsorbSourceUnkickDelay else Float.POSITIVE_INFINITY,
            )
            val step = minOf(remaining, (nextAction - elapsed).coerceAtLeast(0f))
            if (step <= 0f) {
                applyTimelineActions()
                continue
            }
            channels.forEach { it.advance(step) }
            elapsed += step
            remaining -= step
        }
        applyTimelineActions()
        publish()
        if (!expanded && visuallyCollapsed()) {
            // Hidden channels may still move entirely inside the restored capsule. Do not
            // hold a blank trigger on screen while those invisible tails settle.
            channels.forEach { it.finish() }
            publish()
            return true
        }
        return (!expanded || smoothingReleased) && channels.all { it.isSettled }
    }

    private fun applyTimelineActions() {
        if (!portalBlurReleased && elapsed + 0.000001f >= opticalReleaseDelay()) {
            portalBlurReleased = true
            portalBlur.target(0f, BlurOutSpring)
        }
        if (!lensReleased && elapsed + 0.000001f >= opticalReleaseDelay()) {
            lensReleased = true
            lens.target(0f, if (expanded) EjectLensOffSpring else AbsorbLensOffSpring)
        }
        if (!expanded) {
            if (!closingKickReleased && elapsed + 0.000001f >= AbsorbEjecteeUnkickDelay) {
                closingKickReleased = true
                kick.target(0f, KickSpring)
            }
            if (!sourceKickReleased && elapsed + 0.000001f >= AbsorbSourceUnkickDelay) {
                sourceKickReleased = true
                sourceKick.target(0f, KickSpring)
            }
            if (!smoothingReleased && elapsed + 0.000001f >= AbsorbSmoothingReleaseDelay) {
                smoothingReleased = true
                smoothing.target(0f, SmoothingReleaseSpring)
            }
            return
        }
        if (!correctionApplied && elapsed + 0.000001f >= CorrectionDelay) {
            correctionApplied = true
            // Overrounding settles independently after the eject has already gained momentum.
            radiusX.target(destinationRadius, CorrectionSpring)
            radiusY.target(destinationRadius, CorrectionSpring)
            kick.target(0f, KickSpring)
        }
        if (!smoothingReleased && elapsed + 0.000001f >= SmoothingReleaseDelay) {
            smoothingReleased = true
            smoothing.target(0f, SmoothingReleaseSpring)
        }
    }

    private fun configureCollapse() {
        val diameter = initialIncomingDiameter()
        // V2 installs the complete source layer as the synthetic item's override position
        // tracker. Geometry tracking resolves that layer's bounds center instead of through.
        x.target(source.center.x, AbsorbSpring)
        y.target(source.center.y, AbsorbSpring)
        width.target(destination.width, AbsorbSpring)
        height.target(destination.width, AbsorbSpring)
        incomingScale.target(diameter / destination.width, AbsorbSpring)
        // The synthetic through item is a square base with half-width raw radii.
        // Keep those radii separate from its scale, including during a reversal.
        radiusX.target(destination.width / 2f, AbsorbSpring)
        radiusY.target(destination.width / 2f, AbsorbSpring)
        // The native Source action overrides every geometry channel with mainAbsorbSpring.
        // Reusing the opening collapse spring makes the capsule outrun the returning ball.
        sourceX.target(source.center.x, AbsorbSpring)
        sourceY.target(source.center.y, AbsorbSpring)
        sourceWidth.target(source.width, AbsorbSpring)
        sourceHeight.target(source.height, AbsorbSpring)
        sourceScale.target(1f, AbsorbSpring)
        kick.target(if (closingKickReleased) 0f else kickAmount(-80f), KickSpring)
        sourceKick.target(if (sourceKickReleased) 0f else kickAmount(40f), KickSpring)
        content.target(0f, AbsorbSpring)
        sourceContent.target(1f, AbsorbSpring)
        smoothing.target(if (smoothingReleased) 0f else 20f * density,
            if (smoothingReleased) SmoothingReleaseSpring else AbsorbSmoothingSpring)
    }

    private fun visuallyCollapsed(): Boolean {
        val shell = frame.sourceLobe ?: return false
        val epsilon = density * 0.5f
        if (abs(shell.left - source.left) > epsilon || abs(shell.top - source.top) > epsilon ||
            abs(shell.right - source.right) > epsilon || abs(shell.bottom - source.bottom) > epsilon
        ) return false
        val incoming = frame.bounds
        return incoming.left >= source.left - epsilon && incoming.top >= source.top - epsilon &&
            incoming.right <= source.right + epsilon && incoming.bottom <= source.bottom + epsilon &&
            frame.contentProgress < 0.02f && frame.sourceOpacity > 0.98f &&
            abs(kick.value) < epsilon && abs(sourceKick.value) < epsilon &&
            frame.smoothing < epsilon && frame.lensProgress < 0.02f
    }

    private fun collapsedPivot(): Offset {
        val center = through.center * 0.75f + destination.center * 0.25f
        val inset = source.size.minDimension / 8f
        return alignedCenter(Offset(
            center.x.coerceIn(destination.left + inset, destination.right - inset),
            center.y.coerceIn(destination.top + inset, destination.bottom - inset),
        ), source.size.minDimension)
    }

    private fun initialIncomingDiameter(): Float =
        (destination.width * 0.5f * through.height / maxOf(destination.width, destination.height))
            .coerceAtLeast(1f)

    private fun initialIncomingCenter(): Offset = alignedCenter(Offset(
        through.center.x.coerceIn(source.left + through.width / 2f, source.right - through.width / 2f),
        through.center.y.coerceIn(source.top + through.height / 2f, source.bottom - through.height / 2f),
    ), destination.width)

    private fun alignedCenter(center: Offset, side: Float): Offset {
        // AnimationKit aligns the synthetic base rect's origin in point coordinates,
        // using nearest rounding with ties away from zero, before applying its scale.
        fun aligned(value: Float): Float {
            val points = value / density
            return (if (points < 0f) ceil(points - 0.5f) else floor(points + 0.5f)) * density
        }
        return Offset(aligned(center.x - side / 2f) + side / 2f,
            aligned(center.y - side / 2f) + side / 2f)
    }

    private fun kickAmount(points: Float): Float {
        val heightPoints = destination.height / density
        val heightFactor = ((heightPoints - 60f) / 140f).coerceIn(0.5f, 1f)
        val anchorY = ((through.center.y - destination.top) / destination.height).coerceIn(0f, 1f)
        return points * density * heightFactor * (2f * anchorY - 1f)
    }

    private fun publish() {
        val scaleIn = incomingScale.value.coerceAtLeast(0.001f)
        val size = Size((width.value * scaleIn).coerceAtLeast(1f), (height.value * scaleIn).coerceAtLeast(1f))
        val incoming = Rect(
            // MagicMorph's kick transformer multiplies translation by the live scaleY.
            Offset(x.value - size.width / 2f, y.value + kick.value * scaleIn - size.height / 2f),
            size,
        )
        val scale = sourceScale.value.coerceAtLeast(0.01f)
        val sourceSize = Size(
            (sourceWidth.value * scale).coerceAtLeast(1f),
            (sourceHeight.value * scale).coerceAtLeast(1f),
        )
        val sourceBounds = Rect(
            // Each native MagicMorphAnimation owns its own scaled kick transformer.
            Offset(sourceX.value - sourceSize.width / 2f,
                sourceY.value + sourceKick.value * scale - sourceSize.height / 2f),
            sourceSize,
        )
        val sourceRadius = sourceSize.minDimension / 2f
        val lensDimension = if (expanded) source.size.minDimension else destination.size.minDimension
        val lensFactor = lens.value.coerceIn(0f, 1f) *
            (lensDimension / (250f * density)).coerceAtMost(1f)
        val lensHeight = 75f * density * lensFactor
        frame = LiquidMenuMorphFrame(
            bounds = incoming,
            radiusX = (radiusX.value * scaleIn).coerceAtLeast(0f),
            radiusY = (radiusY.value * scaleIn).coerceAtLeast(0f),
            contentProgress = content.value.coerceIn(0f, 1f),
            lensProgress = lens.value.coerceIn(0f, 1f),
            sourceLobe = sourceBounds,
            sourceRadiusX = sourceRadius,
            sourceRadiusY = sourceRadius,
            smoothing = smoothing.value.coerceAtLeast(0f),
            sourceOpacity = sourceContent.value.coerceIn(0f, 1f),
            sourceBlur = 4f * density * (1f - sourceContent.value.coerceIn(0f, 1f)),
            sourceZoom = 1f + (portalZoomScale() - 1f) * portalBlur.value,
            contentBlur = 4f * density * (1f - content.value.coerceIn(0f, 1f)),
            contentZoom = 1f + (portalZoomScale() - 1f) * portalBlur.value,
            portalBlur = 4f * density * portalBlur.value.coerceAtLeast(0f),
            lensHeight = lensHeight,
            lensAmount = minOf(-lensHeight, -0.001f * density),
        )
    }

    private fun opticalReleaseDelay(): Float = if (expanded) EjectOpticalReleaseDelay else AbsorbOpticalReleaseDelay

    private fun portalZoomScale(): Float {
        val heightFactor = ((destination.height / density - 215f) / 305f).coerceIn(0f, 1f)
        return 1.65f + (1.2f - 1.65f) * heightFactor
    }

    private companion object {
        const val GeometrySpeed = 0.7f
        const val CorrectionDelay = 0.1f * GeometrySpeed
        const val SmoothingReleaseDelay = 0.3f * GeometrySpeed
        const val EjectOpticalReleaseDelay = 0.12f * GeometrySpeed
        const val AbsorbOpticalReleaseDelay = 0.1f * GeometrySpeed
        const val AbsorbEjecteeUnkickDelay = 0.1f * GeometrySpeed
        const val AbsorbSourceUnkickDelay = 0.15f * GeometrySpeed
        const val AbsorbSmoothingReleaseDelay = 0.1f * GeometrySpeed
        val CollapseSpring = LiquidMenuSpringSpec(1f, 0.3f * GeometrySpeed)
        val EjectSpring = LiquidMenuSpringSpec(0.75f, 0.5f * GeometrySpeed)
        val CorrectionSpring = EjectSpring
        val KickSpring = LiquidMenuSpringSpec(1f, 0.2f * GeometrySpeed)
        val BlurInSpring = LiquidMenuSpringSpec(1f, 0.3f * GeometrySpeed)
        val BlurOutSpring = LiquidMenuSpringSpec(0.8f, 0.45f * GeometrySpeed)
        val LensOnSpring = LiquidMenuSpringSpec(1f, 0.2f * GeometrySpeed)
        val EjectLensOffSpring = LiquidMenuSpringSpec(1f, 0.6f * GeometrySpeed)
        val AbsorbLensOffSpring = LiquidMenuSpringSpec(1f, 0.4f * GeometrySpeed)
        val AbsorbSpring = LiquidMenuSpringSpec(0.8f, 0.7f * GeometrySpeed)
        val AbsorbSmoothingSpring = LiquidMenuSpringSpec(1f, 0.1f * GeometrySpeed)
        val SmoothingReleaseSpring = LiquidMenuSpringSpec(1f, 0.6f * GeometrySpeed)
    }
}

internal data class LiquidMenuSpringSpec(val dampingRatio: Float, val response: Float) {
    val omega = (2.0 * PI / response).toFloat()
    val alpha = dampingRatio * omega
    val frequency = if (dampingRatio >= 0.9999f) 0f else omega * sqrt(1f - dampingRatio * dampingRatio)
}

/** Closed-form damped oscillator; integrating a long frame never injects numerical energy. */
internal class LiquidMenuSpring(
    private val valueTolerance: Float = 0.2f,
    private val velocityTolerance: Float = 3f,
) {
    var value = 0f
        private set
    var velocity = 0f
        private set
    private var destination = 0f
    private var spec = LiquidMenuSpringSpec(1f, 0.3f)
    val isSettled: Boolean get() = abs(value - destination) < valueTolerance && abs(velocity) < velocityTolerance

    fun reset(value: Float) {
        this.value = value
        destination = value
        velocity = 0f
    }

    fun target(value: Float, spec: LiquidMenuSpringSpec) {
        destination = value
        this.spec = spec
    }

    fun finish() {
        value = destination
        velocity = 0f
    }

    fun advance(seconds: Float) {
        val omega = spec.omega
        val displacement = value - destination
        val alpha = spec.alpha
        val decay = exp(-alpha * seconds)
        if (spec.dampingRatio >= 0.9999f) {
            val coefficient = velocity + omega * displacement
            val nextDisplacement = decay * (displacement + coefficient * seconds)
            velocity = decay * (velocity - omega * coefficient * seconds)
            value = destination + nextDisplacement
        } else {
            val frequency = spec.frequency
            val cosine = cos(frequency * seconds)
            val sine = sin(frequency * seconds)
            val coefficient = (velocity + alpha * displacement) / frequency
            val nextDisplacement = decay * (displacement * cosine + coefficient * sine)
            velocity = decay * (
                -alpha * (displacement * cosine + coefficient * sine) +
                    frequency * (-displacement * sine + coefficient * cosine)
                )
            value = destination + nextDisplacement
        }
        if (isSettled) {
            value = destination
            velocity = 0f
        }
    }
}

private fun Rect.isFiniteAndPositive(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        width > 0f && height > 0f
