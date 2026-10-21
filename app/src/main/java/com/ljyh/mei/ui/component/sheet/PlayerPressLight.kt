package com.ljyh.mei.ui.component.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ljyh.mei.ui.glass.GlassPressHighlight
import com.ljyh.mei.ui.liquidglass.pressHighlightSpring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Captured press light survives cancellation of the live control's deformation. */
internal class PlayerPressLight(private val scope: CoroutineScope) {
    private var captured by mutableStateOf<GlassPressHighlight?>(null)
    private var releaseAnimation by mutableStateOf<Animatable<Float, AnimationVector1D>?>(null)
    private var releaseJob: Job? = null
    private var pointerPosition by mutableStateOf<Offset?>(null)

    val hasCapture get() = captured != null

    fun capture(highlight: GlassPressHighlight) {
        releaseJob?.cancel()
        releaseJob = null
        captured = highlight
        releaseAnimation = null
        pointerPosition = null
    }

    fun updatePointer(rootPosition: Offset) {
        if (captured != null && releaseJob == null) pointerPosition = rootPosition
    }

    fun current(progress: Float, sourceBounds: Rect? = null): GlassPressHighlight {
        val highlight = captured ?: return GlassPressHighlight()
        val pointer = pointerPosition
        val position = if (pointer != null && sourceBounds?.isUsable() == true) {
            Offset((pointer.x - sourceBounds.left) / sourceBounds.width,
                (pointer.y - sourceBounds.top) / sourceBounds.height)
        } else highlight.position
        return highlight.copy(
            progress = releaseAnimation?.value?.coerceIn(0f, 1f)
                ?: (highlight.progress * playerPressHighlightAlpha(progress)),
            position = position,
        )
    }

    fun release(progress: Float, sourceBounds: Rect? = null) {
        if (releaseJob != null || captured == null) return
        val highlight = current(progress, sourceBounds)
        captured = captured?.copy(position = highlight.position)
        pointerPosition = null
        val animation = Animatable(highlight.progress, 0.001f)
        releaseAnimation = animation
        releaseJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animation.animateTo(0f, pressHighlightSpring())
            captured = null
        }
    }

    fun clear() {
        releaseJob?.cancel()
        releaseJob = null
        captured = null
        releaseAnimation = null
        pointerPosition = null
    }
}
