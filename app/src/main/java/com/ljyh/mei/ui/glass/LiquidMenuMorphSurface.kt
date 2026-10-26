package com.ljyh.mei.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.Shader
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight

/** Draw the moving contour at its physical dimensions, with one material and one portal. */
@Composable
internal fun LiquidMenuMorphSurface(
    motion: LiquidMenuMorphMotion,
    screenOrigin: Offset,
    backdrop: Backdrop,
    exportChildBackdrop: Boolean,
    sourcePortal: ToolbarMenuPortal?,
    shaderPrograms: LiquidMenuShaderPrograms?,
    menuScale: () -> Float,
    transformOrigin: TransformOrigin,
    highlight: InteractiveHighlight,
    content: @Composable ColumnScope.(LayerBackdrop) -> Unit,
) {
    val colors = LocalGlassColors.current
    val source = rememberCrossWindowBackdrop(backdrop)
    val renderer = rememberGlassMorphRenderer(source, active = true, blurRadius = if (colors.isDark) 12.dp else 16.dp, programs = shaderPrograms)
    val portal = remember(shaderPrograms) { shaderPrograms?.portal ?: RuntimeShader(LiquidMenuPortalShader) }
    DisposableEffect(portal) {
        onDispose {
            bindLiquidMenuField(portal, null)
            LiquidMenuNativeField.release()
        }
    }
    val childBackdrop = rememberLayerBackdrop()
    val contourWorkspace = remember { LiquidMenuContourWorkspace() }
    val contourPath = remember { Path() }
    val samplingLayer = remember { GraphicsLayerBlockScope() }
    val fieldTexture = remember { LiquidMenuFieldTexture() }
    val foreground = rememberGraphicsLayer()
    val sourceContents = rememberGraphicsLayer()
    val drawCache = remember(motion) { LiquidMenuDrawCache() }
    val menuLayerBlock: GraphicsLayerScope.() -> Unit = {
        this.transformOrigin = transformOrigin
        applyGlassDragScale(highlight.pressProgress, highlight.offset)
        val scale = menuScale()
        scaleX *= scale
        scaleY *= scale
    }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // A fixed capture band avoids changing the sampled background as the contour moves.
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { coordinates = it }
            .graphicsLayer(menuLayerBlock)
            .then(highlight.gestureModifier)
            .then(if (exportChildBackdrop) Modifier.layerBackdrop(childBackdrop).trackBackdropPosition(childBackdrop) else Modifier)
            .drawWithContent {
                val host = coordinates ?: return@drawWithContent
                val frame = motion.frame
                val incoming = frame.bounds.translate(-screenOrigin)
                if (incoming.isEmpty) return@drawWithContent
                val outgoing = frame.sourceLobe?.translate(-screenOrigin)
                val worldContour = LiquidMenuContour(
                    incoming, Offset(frame.radiusX, frame.radiusY), outgoing,
                    Offset(frame.sourceRadiusX, frame.sourceRadiusY), frame.smoothing,
                )
                val bounds = worldContour.exteriorBounds()
                val shape = LiquidMenuContour(
                    incoming.translate(-bounds.topLeft), Offset(frame.radiusX, frame.radiusY),
                    outgoing?.translate(-bounds.topLeft), Offset(frame.sourceRadiusX, frame.sourceRadiusY), frame.smoothing,
                    contourWorkspace,
                )
                val path = contourPath.apply {
                    rewind()
                    addPath(shape.path(bounds.size))
                    translate(bounds.topLeft)
                }
                fieldTexture.update(shape.sampledField(), bounds.topLeft)
                // Evaluate the live block rather than the layer's possibly previous-frame
                // coordinate matrix. The measured screen origin belongs to the stable host.
                samplingLayer.reset(this, size)
                menuLayerBlock(samplingLayer)
                val sampleScale = Offset(samplingLayer.scaleX, samplingLayer.scaleY)
                val stableOrigin = screenOrigin - host.findRootCoordinates().localToScreen(Offset.Zero)
                val origin = stableOrigin + Offset(
                    size.width * samplingLayer.transformOrigin.pivotFractionX * (1f - sampleScale.x) + samplingLayer.translationX,
                    size.height * samplingLayer.transformOrigin.pivotFractionY * (1f - sampleScale.y) + samplingLayer.translationY,
                )
                val visualBounds = Rect(
                    origin.x + bounds.left * sampleScale.x, origin.y + bounds.top * sampleScale.y,
                    origin.x + bounds.right * sampleScale.x, origin.y + bounds.bottom * sampleScale.y,
                )
                // The native lens reaches 75 pt; dispersion can sample twice its
                // displacement. Reserve the complete optical range before the first frame.
                val sampleGuard = 200.dp.toPx()
                val opticalGuard = 2f * kotlin.math.abs(frame.lensAmount) * maxOf(1f, sampleScale.x, sampleScale.y) + 50.dp.toPx()
                val required = visualBounds.inflate(opticalGuard)
                val previousCapture = drawCache.viewport
                val capture = if (previousCapture == null) Rect(
                    minOf(origin.x, visualBounds.left) - sampleGuard,
                    minOf(origin.y, visualBounds.top) - sampleGuard,
                    maxOf(origin.x + size.width * sampleScale.x, visualBounds.right) + sampleGuard,
                    maxOf(origin.y + size.height * sampleScale.y, visualBounds.bottom) + sampleGuard,
                ) else if (required.left < previousCapture.left || required.top < previousCapture.top ||
                    required.right > previousCapture.right || required.bottom > previousCapture.bottom
                ) Rect(
                    minOf(required.left, previousCapture.left), minOf(required.top, previousCapture.top),
                    maxOf(required.right, previousCapture.right), maxOf(required.bottom, previousCapture.bottom),
                ) else previousCapture
                drawCache.viewport = capture
                renderer.draw(
                    scope = this,
                    coordinates = host,
                    viewport = capture,
                    origin = origin,
                    bounds = bounds,
                    radius = minOf(frame.radiusX, frame.radiusY),
                    cornerRadii = Offset(frame.radiusX, frame.radiusY),
                    lensBounds = incoming,
                    secondaryLensBounds = outgoing,
                    secondaryLensRadii = Offset(frame.sourceRadiusX, frame.sourceRadiusY),
                    lensSmoothing = frame.smoothing,
                    menuField = fieldTexture,
                    shape = shape,
                    outlinePath = path,
                    tint = colors.container,
                    highlightAlpha = 0.5f * (0.54f + 0.38f * highlight.pressProgress),
                    refractionHeight = frame.lensHeight.toDp(),
                    refractionAmount = (-frame.lensAmount).toDp(),
                    navigationMaterial = true,
                    dropShadowAlpha = frame.contentProgress,
                    isDark = colors.isDark,
                    sampleScale = sampleScale,
                )
                // Clip in physical surface space. Scaling a clipped full-size menu would
                // squeeze its corners and make the initial capsule rectangular.
                clipPath(path) {
                    if (frame.lensHeight <= 0.01f && frame.portalBlur <= 0.01f) {
                        this@drawWithContent.drawContent()
                    } else {
                        // AnimationKit filters the shared portal container: Gaussian first,
                        // then the same SDF displacement map used by the glass surface.
                        // The portal has one native displacement sample (75 pt max),
                        // plus the four-sigma support of its 4 pt global blur.
                        val initialGuard = 92.dp.toPx()
                        val initial = Rect(
                            minOf(0f, bounds.left) - initialGuard, minOf(0f, bounds.top) - initialGuard,
                            maxOf(size.width, bounds.right) + initialGuard, maxOf(size.height, bounds.bottom) + initialGuard,
                        )
                        val guard = kotlin.math.abs(frame.lensAmount) + 4f * frame.portalBlur + 1f
                        val needed = Rect(
                            minOf(0f, bounds.left) - guard, minOf(0f, bounds.top) - guard,
                            maxOf(size.width, bounds.right) + guard, maxOf(size.height, bounds.bottom) + guard,
                        )
                        val old = drawCache.foregroundCapture
                        val capture = if (old == null) initial else Rect(
                            minOf(old.left, needed.left), minOf(old.top, needed.top),
                            maxOf(old.right, needed.right), maxOf(old.bottom, needed.bottom),
                        )
                        drawCache.foregroundCapture = capture
                        foreground.record(IntSize(ceil(capture.width).toInt(), ceil(capture.height).toInt())) {
                            withTransform({ translate(-capture.left, -capture.top) }) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        portal.setFloatUniform("contentOrigin", capture.left, capture.top)
                        portal.setFloatUniform("drawingBounds", bounds.left - 1f, bounds.top - 1f, bounds.right + 1f, bounds.bottom + 1f)
                        portal.setFloatUniform("center", incoming.center.x, incoming.center.y)
                        portal.setFloatUniform("halfSize", incoming.width / 2f, incoming.height / 2f)
                        portal.setFloatUniform("cornerRadii", frame.radiusX, frame.radiusY)
                        portal.setFloatUniform("secondaryEnabled", if (outgoing != null) 1f else 0f)
                        val second = outgoing ?: incoming
                        portal.setFloatUniform("secondaryCenter", second.center.x, second.center.y)
                        portal.setFloatUniform("secondaryHalfSize", second.width / 2f, second.height / 2f)
                        portal.setFloatUniform("secondaryRadii", frame.sourceRadiusX, frame.sourceRadiusY)
                        portal.setFloatUniform("smoothing", frame.smoothing)
                        portal.setFloatUniform("height", frame.lensHeight)
                        portal.setFloatUniform("amount", frame.lensAmount)
                        bindLiquidMenuField(portal, fieldTexture)
                        val warp = RenderEffect.createRuntimeShaderEffect(portal, "content")
                        foreground.renderEffect = (if (frame.portalBlur > 0.01f) {
                            RenderEffect.createChainEffect(warp, RenderEffect.createBlurEffect(
                                frame.portalBlur, frame.portalBlur, Shader.TileMode.DECAL,
                            ))
                        } else warp).asComposeRenderEffect()
                        withTransform({ translate(capture.left, capture.top) }) { drawLayer(foreground) }
                    }
                }
            }
            // Draw the press light inside the same moving skin as both foreground
            // portals. Only the renderer's exterior shadow remains outside this clip.
            .then(highlight.modifier),
    ) {
        if (sourcePortal != null) {
            Box(Modifier.fillMaxSize().drawWithContent {
                val frame = motion.frame
                if (frame.sourceOpacity <= 0f) return@drawWithContent
                val target = frame.sourceLobe?.translate(-screenOrigin) ?: return@drawWithContent
                val crop = sourcePortal.currentSourceBounds
                if (drawCache.recordedSource !== sourcePortal || drawCache.recordedSourceBounds != crop) {
                    sourceContents.clip = true
                    sourceContents.record(IntSize(ceil(crop.width).toInt(), ceil(crop.height).toInt())) {
                        withTransform({ translate(-crop.left, -crop.top) }) { drawLayer(sourcePortal.layer) }
                    }
                    drawCache.recordedSource = sourcePortal
                    drawCache.recordedSourceBounds = crop
                }
                sourceContents.renderEffect = if (frame.sourceBlur > 0.01f) RenderEffect.createBlurEffect(
                    frame.sourceBlur, frame.sourceBlur, Shader.TileMode.CLAMP,
                ).asComposeRenderEffect() else null
                sourceContents.alpha = frame.sourceOpacity
                withTransform({
                    translate(target.center.x, target.center.y)
                    scale(target.width / crop.width * frame.sourceZoom, target.height / crop.height * frame.sourceZoom, Offset.Zero)
                    translate(-crop.width / 2f, -crop.height / 2f)
                }) {
                    drawLayer(sourceContents)
                }
            })
        }
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                val frame = motion.frame
                val bounds = frame.bounds.translate(-screenOrigin)
                this.transformOrigin = TransformOrigin(0f, 0f)
                scaleX = bounds.width / size.width.coerceAtLeast(1f) * frame.contentZoom
                scaleY = bounds.height / size.height.coerceAtLeast(1f) * frame.contentZoom
                translationX = bounds.left + bounds.width * 0.5f * (1f - frame.contentZoom)
                translationY = bounds.top + bounds.height * 0.5f * (1f - frame.contentZoom)
                alpha = frame.contentProgress
                val blur = frame.contentBlur
                renderEffect = if (blur > 0.01f) {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                } else null
            }.padding(10.dp),
        ) { content(childBackdrop) }
    }
}

/** Draw-local recordings must not invalidate composition when their capture bands grow. */
private class LiquidMenuDrawCache {
    var recordedSource: ToolbarMenuPortal? = null
    var recordedSourceBounds: Rect? = null
    var foregroundCapture: Rect? = null
    var viewport: Rect? = null
}

/** Signed FP16 data avoids color conversion, premultiplication and RGBA8 distance quantization. */
internal class LiquidMenuFieldTexture {
    private class Buffer(val bitmap: Bitmap) {
        val pixels: ByteBuffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    private val buffers = ArrayList<Buffer>(4)
    private var current: Buffer? = null
    private var generation = Long.MIN_VALUE
    private var sampled: LiquidMenuGaussianField? = null
    var origin = Offset.Zero
        private set
    val step: Float get() = sampled?.step ?: 1f
    val width: Int get() = sampled?.width ?: 1
    val height: Int get() = sampled?.height ?: 1
    val input: BitmapShader? get() = if (sampled != null) current?.shader else null

    fun update(value: LiquidMenuGaussianField?, offset: Offset) {
        val previous = sampled
        sampled = value
        if (value == null) return
        origin = value.origin + offset
        if (previous === value && generation == value.generation) return
        generation = value.generation
        // Smooth-off needs a fine grid; the next coarse-grid phase must not keep
        // copying and uploading that largest historical bitmap on every frame.
        val requiredArea = value.width * value.height
        var buffer = buffers.filter {
            it.bitmap.width >= value.width && it.bitmap.height >= value.height &&
                it.bitmap.width * it.bitmap.height <= maxOf(4096, requiredArea * 4)
        }.minByOrNull { it.bitmap.byteCount }
        if (buffer == null) {
            val width = (value.width + 63) / 64 * 64
            val height = (value.height + 63) / 64 * 64
            buffer = Buffer(Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16,
                false, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)).apply { setPremultiplied(false) })
            if (buffers.size == 4) buffers.removeAt(0)
        } else {
            buffers.remove(buffer)
        }
        buffers.add(buffer)
        current = buffer
        val bytes = buffer.pixels
        bytes.clear()
        val stride = buffer.bitmap.rowBytes
        if (!LiquidMenuNativeField.pack(value.rgba, value.width, value.height, stride, bytes)) {
            repeat(value.height) { row ->
                bytes.position(row * stride)
                val start = row * value.width * 4
                for (i in start until start + value.width * 4) bytes.putShort(Half.toHalf(value.rgba[i]))
            }
        }
        bytes.rewind()
        buffer.bitmap.copyPixelsFromBuffer(bytes)
    }
}

private val EmptyLiquidMenuField by lazy {
    BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
}

internal fun bindLiquidMenuField(
    shader: RuntimeShader,
    field: LiquidMenuFieldTexture?,
    coordinateScale: Offset = Offset(1f, 1f),
    coordinateOffset: Offset = Offset.Zero,
) {
    val input = field?.input
    shader.setInputBuffer("smoothedField", input ?: EmptyLiquidMenuField)
    shader.setFloatUniform("smoothedFieldEnabled", if (input != null) 1f else 0f)
    val step = field?.step ?: 1f
    val origin = field?.origin ?: Offset.Zero
    shader.setFloatUniform("fieldCoordinateScale", 1f / (coordinateScale.x * step), 1f / (coordinateScale.y * step))
    shader.setFloatUniform("fieldCoordinateOffset",
        (-coordinateOffset.x / coordinateScale.x - origin.x) / step + 0.5f,
        (-coordinateOffset.y / coordinateScale.y - origin.y) / step + 0.5f,
    )
    val distanceScale = minOf(coordinateScale.x, coordinateScale.y)
    shader.setFloatUniform("fieldDistanceScale", distanceScale)
    shader.setFloatUniform("fieldGradientScale", distanceScale / coordinateScale.x, distanceScale / coordinateScale.y)
    shader.setFloatUniform("fieldExtent", field?.width?.toFloat() ?: 1f, field?.height?.toFloat() ?: 1f)
}

// QuartzCore sdf_glass_displacement followed by displacement_map_lpf.
internal const val LiquidMenuPortalShader = """
uniform shader content;
uniform float2 contentOrigin;
uniform float4 drawingBounds;
uniform float2 center;
uniform float2 halfSize;
uniform float2 cornerRadii;
uniform float secondaryEnabled;
uniform float2 secondaryCenter;
uniform float2 secondaryHalfSize;
uniform float2 secondaryRadii;
uniform float smoothing;
uniform float height;
uniform float amount;
$LiquidMorphFieldShader
half4 main(float2 coord) {
    float2 point = coord + contentOrigin;
    if (point.x < drawingBounds.x || point.y < drawingBounds.y ||
        point.x > drawingBounds.z || point.y > drawingBounds.w) return half4(0.0);
    float3 field = liquidFieldGradient(point);
    float inside = -field.x;
    float aa = max(abs(liquidFieldGradient(point + float2(1.0, 0.0)).x - field.x) +
        abs(liquidFieldGradient(point + float2(0.0, 1.0)).x - field.x), 0.0001);
    float coverage = inside < -5.0 ? 0.0 : clamp(inside / aa + 0.5, 0.0, 1.0);
    if (coverage < 0.0039215686) return half4(0.0);
    float t = clamp(inside / max(height, 0.0001), 0.0, 1.0);
    float profile = sqrt(max(1.0 - (1.0 - t) * (1.0 - t), 0.0));
    float innerAA = clamp((height - inside) / aa + 0.5, 0.0, 1.0);
    return content.eval(coord + amount * field.yz * (1.0 - profile) * innerAA) * half(coverage);
}
"""
