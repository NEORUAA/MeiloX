package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.ui.component.utils.lerp

internal fun AnimatedContentTransitionScope<*>.appleMusicArtworkTransition(): ContentTransform =
    (fadeIn(tween(400)) + scaleIn(initialScale = 0.92f, animationSpec = tween(400)))
        .togetherWith(fadeOut(tween(400)))
        // The outer artwork frame owns size, including while a painter is pending.
        .using(null)

internal fun appleMusicLyricSpring() = spring<Float>(
    dampingRatio = 1.2f,
    stiffness = 800f,
    // Fractional progress drives hundreds of pixels; the default 0.01 snaps visibly.
    visibilityThreshold = 0.0001f,
)

internal fun appleMusicAlbumInfoAlpha(progress: Float): Float =
    1f - progress.coerceIn(0f, 1f)

internal fun appleMusicHeaderInfoAlpha(progress: Float): Float =
    progress.coerceIn(0f, 1f)

internal fun appleMusicLyricsAlpha(progress: Float): Float =
    ((progress.coerceIn(0f, 1f) - 0.08f) / 0.82f).coerceIn(0f, 1f)

internal fun appleMusicLyricsTranslationY(
    progress: Float,
    albumInformationBottom: Float,
    lyricViewportTop: Float,
): Float = (1f - progress.coerceIn(0f, 1f)) *
    (albumInformationBottom - lyricViewportTop).coerceAtLeast(0f)

internal fun appleMusicControlsFadeSpring(controlsVisible: Boolean) = spring<Float>(
    dampingRatio = 1f,
    stiffness = if (controlsVisible) 405f else 105f,
)

/** Keep Flamingo's moving controls gradient independent of lyric entry travel. */
internal fun appleMusicLyricsControlsFadeRange(
    viewportHeight: Float,
    shift: Float,
    translationY: Float,
    controlsTop: Float,
): ClosedFloatingPointRange<Float> {
    val end = controlsTop.coerceIn(0f, viewportHeight)
    val start = end - viewportHeight * 0.15f
    val travel = (viewportHeight - start) * (shift / 0.6f).coerceIn(0f, 1f)
    return (start + travel - translationY)..(end + travel - translationY)
}

/** Reserve the whole information row above the footer instead of drawing above its clip. */
internal fun appleMusicAlbumInfoTop(
    viewportHeight: Float,
    controlsHeight: Float,
    informationHeight: Float,
    bottomGap: Float,
    topLimit: Float,
): Float = (viewportHeight - controlsHeight - informationHeight - bottomGap).coerceAtLeast(topLimit)

/** Each endpoint draws at the same interpolated origin, despite different text sizes. */
internal fun appleMusicInfoOffset(
    albumOrigin: Offset,
    headerOrigin: Offset,
    progress: Float,
    fromAlbum: Boolean,
    placementOrigin: Offset = if (fromAlbum) albumOrigin else headerOrigin,
): Offset {
    val p = progress.coerceIn(0f, 1f)
    val shared = Offset(lerp(albumOrigin.x, headerOrigin.x, p), lerp(albumOrigin.y, headerOrigin.y, p))
    return shared - placementOrigin
}

internal data class AppleMusicArtworkFrame(
    val bounds: Rect,
    val cornerRadius: Float,
    val shadowElevation: Float,
)

/** Match Flamingo's playing artwork insets without moving the previous bottom edge. */
internal fun appleMusicAlbumBounds(
    previous: Rect,
    viewportWidth: Float,
    horizontalInset: Float,
    topLimit: Float,
    bottomLimit: Float = previous.bottom,
): Rect {
    val bottom = minOf(previous.bottom, bottomLimit).coerceAtLeast(topLimit)
    val size = minOf((viewportWidth - horizontalInset * 2f).coerceAtLeast(0f), bottom - topLimit)
    val left = (viewportWidth - size) / 2f
    return Rect(left, bottom - size, left + size, bottom)
}

/** Visual pixels, independent of the image's fixed layout size. */
internal fun appleMusicArtworkFrame(
    normalBounds: Rect,
    headerBounds: Rect,
    normalRadius: Float,
    headerRadius: Float,
    normalShadow: Float,
    playbackScale: Float,
    progress: Float,
): AppleMusicArtworkFrame {
    val p = progress.coerceIn(0f, 1f)
    val width = lerp(normalBounds.width, headerBounds.width, p)
    val height = lerp(normalBounds.height, headerBounds.height, p)
    val scale = lerp(playbackScale, 1f, p)
    val left = lerp(normalBounds.left, headerBounds.left, p) + width * (1f - scale) / 2f
    val top = lerp(normalBounds.top, headerBounds.top, p) + height * (1f - scale) / 2f
    return AppleMusicArtworkFrame(
        bounds = Rect(left, top, left + width * scale, top + height * scale),
        cornerRadius = lerp(normalRadius, headerRadius, p) * scale,
        shadowElevation = normalShadow * (1f - p) * scale,
    )
}

internal data class AppleMusicQueueArtworkTarget(val bounds: Rect, val opacity: Float)

/** Keep an offscreen endpoint at the edge it left, so reverse motion starts nearby. */
internal fun appleMusicQueueArtworkTarget(
    header: Rect,
    viewportTop: Float,
    viewportBottom: Float,
): AppleMusicQueueArtworkTarget {
    if (viewportBottom <= viewportTop) return AppleMusicQueueArtworkTarget(header, 1f)
    val top = header.top.coerceIn(viewportTop - header.height, viewportBottom)
    val bounds = Rect(header.left, top, header.right, top + header.height)
    val visibleHeight = (minOf(bounds.bottom, viewportBottom) - maxOf(bounds.top, viewportTop)).coerceAtLeast(0f)
    return AppleMusicQueueArtworkTarget(bounds, (visibleHeight / header.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
}
