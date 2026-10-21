package com.ljyh.mei.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/** Figma's exterior-only 0/8/15/0 black 2% navigation shadow. */
internal class NavigationDropShadowRenderer(private val renderScale: Float = 1f) {
    init {
        require(renderScale.isFinite() && renderScale > 0f)
    }

    private val mask = RenderNode("Navigation exterior drop shadow")
    private var transformNode: RenderNode? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var outlinePath = android.graphics.Path()
    private var key: GeometryKey? = null
    private var blurDensity = Float.NaN
    private var padding = 0f
    private var verticalOffset = 0f

    fun draw(
        scope: DrawScope,
        shape: Shape,
        bounds: Rect = Rect(Offset.Zero, scope.size),
        alpha: Float = 1f,
    ) {
        val canvas = scope.drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated || bounds.isEmpty || alpha <= 0f) return
        prepare(scope, shape, bounds.size)
        mask.alpha = ShadowAlpha * alpha.coerceIn(0f, 1f)
        val save = canvas.save()
        try {
            canvas.translate(bounds.left, bounds.top)
            drawMask(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    internal fun drawTransformed(
        scope: DrawScope,
        shape: Shape,
        transform: GraphicsLayerBlockScope,
    ) {
        if (transform.alpha == 1f && transform.scaleX == 1f && transform.scaleY == 1f &&
            transform.translationX == 0f && transform.translationY == 0f &&
            transform.rotationX == 0f && transform.rotationY == 0f && transform.rotationZ == 0f
        ) {
            draw(scope, shape)
            return
        }
        val canvas = scope.drawContext.canvas.nativeCanvas
        if (!canvas.isHardwareAccelerated || scope.size.minDimension <= 0f || transform.alpha <= 0f) return
        prepare(scope, shape, scope.size)
        mask.alpha = ShadowAlpha
        val node = transformNode ?: RenderNode("Navigation drop shadow transform").also {
            it.setClipToBounds(false)
            transformNode = it
        }
        val width = ceil(scope.size.width + padding * 2f).toInt()
        val height = ceil(scope.size.height + padding * 2f + verticalOffset).toInt()
        node.setPosition(0, 0, width, height)
        // Keep the filtered mask cached, but always refresh this small display list:
        // HWUI can evict a child's native list while a retained surface is hidden.
        val recording = node.beginRecording(width, height)
        try {
            recording.translate(padding, padding)
            drawMask(recording)
        } finally {
            node.endRecording()
        }
        node.alpha = transform.alpha.coerceIn(0f, 1f)
        node.scaleX = transform.scaleX
        node.scaleY = transform.scaleY
        node.translationX = transform.translationX
        node.translationY = transform.translationY
        node.rotationX = transform.rotationX
        node.rotationY = transform.rotationY
        node.rotationZ = transform.rotationZ
        node.cameraDistance = transform.cameraDistance
        node.pivotX = padding + scope.size.width * transform.transformOrigin.pivotFractionX
        node.pivotY = padding + scope.size.height * transform.transformOrigin.pivotFractionY
        val save = canvas.save()
        try {
            canvas.translate(-padding, -padding)
            canvas.drawRenderNode(node)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun prepare(scope: DrawScope, shape: Shape, size: Size) {
        val nextKey = GeometryKey(shape, size, scope.layoutDirection, scope.density, scope.fontScale)
        if (nextKey == key && mask.hasDisplayList()) return
        val outline = shape.createOutline(size, scope.layoutDirection, scope)
        outlinePath = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }.asAndroidPath()
        if (blurDensity != scope.density) {
            // Figma blur 15 is Gaussian sigma 7.5. Android's public radius maps to
            // sigma = radius * 0.57735 + 0.5, so invert after reducing mask pixels.
            val sigma = 7.5f * scope.density
            val radius = ((sigma * renderScale - 0.5f) / 0.57735f).coerceAtLeast(0.001f)
            mask.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            padding = ceil(3f * sigma)
            verticalOffset = 8f * scope.density
            blurDensity = scope.density
        }
        val width = ceil((size.width + padding * 2f) * renderScale).toInt()
        val height = ceil((size.height + padding * 2f) * renderScale).toInt()
        mask.setPosition(0, 0, width, height)
        val recording = mask.beginRecording(width, height)
        try {
            recording.scale(renderScale, renderScale)
            recording.translate(padding, padding)
            recording.drawPath(outlinePath, fill)
        } finally {
            mask.endRecording()
        }
        key = nextKey
    }

    private fun drawMask(canvas: Canvas) {
        val save = canvas.save()
        try {
            // Cut at native resolution after the offset and Gaussian blur. Removing
            // the source interior before filtering would leak shadow back inside
            // translucent glass; scaling this cut would soften the real outline.
            canvas.clipOutPath(outlinePath)
            canvas.translate(-padding, verticalOffset - padding)
            canvas.scale(1f / renderScale, 1f / renderScale)
            canvas.drawRenderNode(mask)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        mask.discardDisplayList()
        transformNode?.discardDisplayList()
        key = null
    }

    private data class GeometryKey(
        val shape: Shape,
        val size: Size,
        val layoutDirection: LayoutDirection,
        val density: Float,
        val fontScale: Float,
    )

    private companion object {
        const val ShadowAlpha = 0.02f
    }
}

/** Draws outside the backdrop clip while sharing only its live press transform. */
internal fun Modifier.navigationGlassDropShadow(
    shape: () -> Shape,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = then(NavigationGlassDropShadowElement(shape, layerBlock))

private data class NavigationGlassDropShadowElement(
    val shape: () -> Shape,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : ModifierNodeElement<NavigationGlassDropShadowNode>() {
    override fun create() = NavigationGlassDropShadowNode(shape, layerBlock)

    override fun update(node: NavigationGlassDropShadowNode) {
        node.shape = shape
        node.layerBlock = layerBlock
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "navigationGlassDropShadow"
        properties["shape"] = shape
        properties["layerBlock"] = layerBlock
    }
}

private class NavigationGlassDropShadowNode(
    var shape: () -> Shape,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : DrawModifierNode, Modifier.Node() {
    private val renderer = NavigationDropShadowRenderer()
    private val layerScope = GraphicsLayerBlockScope()

    override fun ContentDrawScope.draw() {
        val block = layerBlock
        if (block == null) {
            renderer.draw(this, shape())
        } else {
            layerScope.reset(this, size)
            block(layerScope)
            renderer.drawTransformed(this, shape(), layerScope)
        }
        drawContent()
    }

    override fun onDetach() {
        renderer.release()
    }
}
