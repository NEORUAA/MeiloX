package com.ljyh.mei.ui.component.player.component.applemusic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ljyh.mei.ui.glass.SfIcon

internal fun appleMusicPlaybackSpec(isPlaying: Boolean) = if (isPlaying) {
    spring(dampingRatio = 0.65f, stiffness = 150f, visibilityThreshold = 0.001f)
} else {
    tween<Float>(durationMillis = 480, easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f))
}

@Composable
internal fun rememberAppleMusicPlaybackScale(isPlaying: Boolean, expanded: Boolean): State<Float> {
    // Normalize the motion so Flamingo's completion threshold survives our smaller size change.
    val pausedFraction = animateFloatAsState(
        targetValue = if (isPlaying) 0f else 1f,
        animationSpec = if (expanded) appleMusicPlaybackSpec(isPlaying) else snap(),
        label = "AppleMusicPlaybackFraction",
    )
    return remember(pausedFraction) { derivedStateOf { 1f - 0.1f * pausedFraction.value } }
}

@Composable
internal fun AppleMusicPlayPauseIcon(systemName: String) {
    AnimatedContent(
        targetState = systemName,
        modifier = Modifier.size(44.dp),
        transitionSpec = {
            (scaleIn(
                initialScale = 0.3f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 150f, visibilityThreshold = 0.001f),
            ) + fadeIn()).togetherWith(
                scaleOut(
                    targetScale = 0.3f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 150f, visibilityThreshold = 0.001f),
                ) + fadeOut()
            )
        },
        label = "AppleMusicPlayPauseIcon",
    ) { icon ->
        SfIcon(
            systemName = icon,
            contentDescription = null,
            tint = Color.White,
            size = 44.dp,
            modifier = if (icon == "play.fill") Modifier.offset(x = 2.dp) else Modifier,
        )
    }
}
