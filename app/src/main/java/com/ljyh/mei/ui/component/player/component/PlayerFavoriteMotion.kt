package com.ljyh.mei.ui.component.player.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PlayerFavoriteMotion(private val scope: CoroutineScope, private val haptics: HapticFeedback) {
    val scale = Animatable(1f)
    private var animation: Job? = null

    fun reset() {
        animation?.cancel()
        animation = scope.launch(start = CoroutineStart.UNDISPATCHED) { scale.snapTo(1f) }
    }

    fun dispose() { animation?.cancel() }

    fun click(isLiked: Boolean, onClick: () -> Unit) {
        haptics.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        animation?.cancel()
        animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (isLiked) scale.snapTo(1f) else {
                scale.animateTo(1.3f, tween(durationMillis = 200))
                scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
            }
        }
        onClick()
    }
}

@Composable
internal fun rememberPlayerFavoriteMotion(isLiked: Boolean, trackKey: Any?): PlayerFavoriteMotion {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val motion = remember(trackKey, scope, haptics) { PlayerFavoriteMotion(scope, haptics) }
    LaunchedEffect(isLiked, motion) { if (!isLiked) motion.reset() }
    androidx.compose.runtime.DisposableEffect(motion) { onDispose { motion.dispose() } }
    return motion
}
