package com.ljyh.mei.ui.glass

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import com.kyant.capsule.Continuity
import com.kyant.capsule.ContinuousRoundedRectangle

/**
 * Keep the exact outline but bound path masks to the corners, without an extra saveLayer.
 * Default G2 corners extend at most 1.529 radii along each edge; two radii safely contain
 * that curve. The seven disjoint rectangles preserve alpha without drawing any pixel twice.
 */
internal fun DrawScope.clipGlassShape(
    bounds: Rect,
    radius: Float,
    shape: Shape,
    outline: Path,
    draw: DrawScope.() -> Unit,
) {
    if (bounds.isEmpty) return
    if (shape !is ContinuousRoundedRectangle || shape.continuity != Continuity.Default) {
        clipPath(outline, block = draw)
        return
    }
    val cornerWidth = (radius.coerceAtLeast(0f) * 2f).coerceAtMost(bounds.width / 2f)
    val cornerHeight = (radius.coerceAtLeast(0f) * 2f).coerceAtMost(bounds.height / 2f)
    val left = bounds.left + cornerWidth
    val right = bounds.right - cornerWidth
    val top = bounds.top + cornerHeight
    val bottom = bounds.bottom - cornerHeight
    drawClipTile(left, bounds.top, right, bounds.bottom, null, draw)
    drawClipTile(bounds.left, top, left, bottom, null, draw)
    drawClipTile(right, top, bounds.right, bottom, null, draw)
    drawClipTile(bounds.left, bounds.top, left, top, outline, draw)
    drawClipTile(right, bounds.top, bounds.right, top, outline, draw)
    drawClipTile(bounds.left, bottom, left, bounds.bottom, outline, draw)
    drawClipTile(right, bottom, bounds.right, bounds.bottom, outline, draw)
}

private inline fun DrawScope.drawClipTile(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    outline: Path?,
    draw: DrawScope.() -> Unit,
) {
    if (right <= left || bottom <= top) return
    clipRect(left, top, right, bottom) {
        if (outline == null) draw() else clipPath(outline, block = draw)
    }
}
