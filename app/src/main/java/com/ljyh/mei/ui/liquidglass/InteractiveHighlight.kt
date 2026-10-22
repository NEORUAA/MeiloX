package com.ljyh.mei.ui.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun pressHighlightSpring() = spring(0.5f, 300f, 0.001f)

class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {

    private val pressProgressAnimationSpec = pressHighlightSpring()
    private val positionAnimationSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    private var pressCancelled by mutableStateOf(false)
    val pressProgress: Float get() = if (pressCancelled) 0f else pressProgressAnimation.value
    val offset: Offset get() = if (pressCancelled) Offset.Zero else positionAnimation.value - startPosition

    fun startPress(position: Offset) {
        pressCancelled = false
        startPosition = position
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { positionAnimation.snapTo(startPosition) }
        }
    }

    fun endPress() {
        if (!pressCancelled) animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
        }
    }

    /** A sheet has taken this gesture; only a new down may restore feedback. */
    fun cancelPress() {
        pressCancelled = true
        animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pressProgressAnimation.snapTo(0f)
            positionAnimation.snapTo(startPosition)
        }
    }

    private val renderer = PressHighlightRenderer()

    fun highlightPosition(size: Size): Offset = position(size, positionAnimation.value)

    fun highlightModifier(
        progressProvider: () -> Float = { pressProgress },
        positionProvider: (Size) -> Offset = ::highlightPosition,
        horizontalRadiusProvider: ((Size) -> Float)? = null,
    ): Modifier = Modifier.drawWithContent {
        val progress = progressProvider()
        if (progress > 0f) renderer.draw(
            this, progress, positionProvider(size),
            horizontalRadius = horizontalRadiusProvider?.invoke(size),
        )
        drawContent()
    }

    val modifier: Modifier = highlightModifier()

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPress(down.position)
                },
                onDragEnd = {
                    endPress()
                },
                onDragCancel = {
                    endPress()
                }
            ) { change, _ ->
                if (!pressCancelled) animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}
