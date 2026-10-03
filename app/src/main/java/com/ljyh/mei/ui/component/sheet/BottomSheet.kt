package com.ljyh.mei.ui.component.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
class BottomSheetState internal constructor(
    private val coroutineScope: CoroutineScope,
    private val density: Float,
    private val onAnchorChanged: (Int) -> Unit,
    val dismissedBound: Dp,
    val collapsedBound: Dp,
    val expandedBound: Dp,
    initialValue: Dp,
    initialAnchor: Int,
    previouslyShown: Boolean = false,
) {
    private val animation = Animatable(initialValue, Dp.VectorConverter)
    private var motionJob: Job? = null
    private var lastDragDelta = 0f
    private var dragOriginOpen = initialAnchor == expandedAnchor
    private var relocating by mutableStateOf(initialAnchor == collapsedAnchor && initialValue != collapsedBound)

    var hasBeenShown by mutableStateOf(previouslyShown || initialAnchor == expandedAnchor)
        private set

    var value by mutableStateOf(initialValue)
        private set
    var targetAnchor by mutableIntStateOf(initialAnchor)
        private set
    var isDragging by mutableStateOf(false)
        private set

    val isDismissed get() = value <= dismissedBound && targetAnchor == dismissedAnchor
    val isCollapsed get() = relocating || value == collapsedBound && !isDragging && targetAnchor == collapsedAnchor
    val isExpanded get() = value == expandedBound && !isDragging && targetAnchor == expandedAnchor
    val progress: Float get() = if (relocating || expandedBound <= collapsedBound) 0f else
        ((value - collapsedBound) / (expandedBound - collapsedBound)).coerceIn(0f, 1f)
    val isTransitioning get() = !relocating && (isDragging || when (targetAnchor) {
        expandedAnchor -> value < expandedBound
        else -> value > collapsedBound
    })

    fun collapse(animationSpec: AnimationSpec<Dp>) = settle(collapsedAnchor, animationSpec)
    fun expand(animationSpec: AnimationSpec<Dp>) = settle(expandedAnchor, animationSpec)
    fun collapseSoft() = collapse(playerSheetSpring())
    fun expandSoft() = expand(playerSheetSpring())
    fun dismiss() = settle(dismissedAnchor, playerSheetSpring())

    internal fun relocate() = settle(targetAnchor, playerSheetSpring())
    internal fun dispose() { motionJob?.cancel() }

    private fun settle(anchor: Int, spec: AnimationSpec<Dp>, velocity: Dp? = null) {
        val releaseVelocity = velocity ?: if (motionJob?.isActive == true) animation.velocity else 0.dp
        motionJob?.cancel()
        isDragging = false
        targetAnchor = anchor
        if (anchor == expandedAnchor) {
            hasBeenShown = true
            relocating = false
        }
        onAnchorChanged(anchor)
        val target = when (anchor) {
            expandedAnchor -> expandedBound
            collapsedAnchor -> collapsedBound
            else -> dismissedBound
        }
        val start = value
        val lowerBound = if (anchor == dismissedAnchor || start < collapsedBound) dismissedBound else collapsedBound
        motionJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animation.snapTo(start)
            animation.animateTo(target, spec, initialVelocity = releaseVelocity) {
                this@BottomSheetState.value = this.value.coerceIn(lowerBound, expandedBound)
            }
            value = target
            relocating = false
        }
    }

    fun snapTo(value: Dp) {
        motionJob?.cancel()
        isDragging = false
        relocating = false
        this.value = value.coerceIn(dismissedBound, expandedBound)
    }

    fun beginDrag() {
        if (isDismissed || isDragging) return
        motionJob?.cancel()
        hasBeenShown = true
        relocating = false
        dragOriginOpen = targetAnchor == expandedAnchor
        lastDragDelta = 0f
        isDragging = true
    }

    fun dispatchRawDelta(delta: Float) {
        if (isDismissed) return
        if (!isDragging) beginDrag()
        if (delta != 0f) lastDragDelta = delta
        value = playerDragValue(value.value, delta, density, collapsedBound.value, expandedBound.value).dp
    }

    fun cancelDrag() {
        if (!isDragging) return
        settle(if (dragOriginOpen) expandedAnchor else collapsedAnchor, playerSheetSpring(), 0.dp)
    }

    /** Velocity follows the existing sheet API: positive is upward, in pixels/second. */
    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (value < collapsedBound && onDismiss != null) {
            dismiss()
            onDismiss()
            return
        }
        val opens = playerDragOpens(-velocity, lastDragDelta, dragOriginOpen)
        settle(if (opens) expandedAnchor else collapsedAnchor, playerSheetSpring(), (velocity / density).dp)
    }

    val preUpPostDownNestedScrollConnection = object : NestedScrollConnection {
        var isTopReached = false

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (isExpanded && available.y < 0) {
                isTopReached = false
            }

            return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
                dispatchRawDelta(available.y)
                available
            } else {
                Offset.Zero
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (!isTopReached) {
                isTopReached = consumed.y == 0f && available.y > 0
            }

            return if (isTopReached && source == NestedScrollSource.UserInput) {
                dispatchRawDelta(available.y)
                available
            } else {
                Offset.Zero
            }
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            return if (isTopReached) {
                val velocity = -available.y
                performFling(velocity, null)

                available
            } else {
                Velocity.Zero
            }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            isTopReached = false
            return Velocity.Zero
        }
    }
}

const val expandedAnchor = 2
const val collapsedAnchor = 1
const val dismissedAnchor = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = dismissedAnchor,
): BottomSheetState {
    val density = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    var previousAnchor by rememberSaveable { mutableIntStateOf(initialAnchor) }
    val previousState = remember { arrayOfNulls<BottomSheetState>(1) }
    val state = remember(density, dismissedBound, expandedBound, collapsedBound) {
        val initialValue = previousState[0]?.value ?: when (previousAnchor) {
            expandedAnchor -> expandedBound
            collapsedAnchor -> collapsedBound
            else -> dismissedBound
        }
        BottomSheetState(
            coroutineScope, density, { previousAnchor = it }, dismissedBound,
            collapsedBound.coerceAtMost(expandedBound), expandedBound,
            initialValue.coerceIn(dismissedBound, expandedBound), previousAnchor,
            previouslyShown = previousState[0]?.hasBeenShown == true,
        ).also { previousState[0] = it }
    }
    DisposableEffect(state) { onDispose { state.dispose() } }
    LaunchedEffect(state) { state.relocate() }
    return state
}

enum class HorizontalSwipeDirection { Left, Right }
