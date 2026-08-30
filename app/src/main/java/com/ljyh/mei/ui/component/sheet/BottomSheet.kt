package com.ljyh.mei.ui.component.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.NavigationBarAnimationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


private fun Modifier.bottomSheetGestureHandlers(
    state: BottomSheetState,
    onHorizontalSwipe: ((direction: HorizontalSwipeDirection) -> Unit)?,
): Modifier = then(
    if (onHorizontalSwipe == null) {
        Modifier
    } else {
        Modifier.pointerInput(onHorizontalSwipe) {
            val velocityTracker = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { velocityTracker.resetTracking() },
                onHorizontalDrag = { change, _ ->
                    velocityTracker.addPointerInputChange(change)
                },
                onDragEnd = {
                    val velocity = velocityTracker.calculateVelocity().x
                    val swipeThreshold = 500f

                    if (velocity > swipeThreshold) {
                        onHorizontalSwipe(HorizontalSwipeDirection.Right)
                    } else if (velocity < -swipeThreshold) {
                        onHorizontalSwipe(HorizontalSwipeDirection.Left)
                    }
                },
            )
        }
    },
).pointerInput(state) {
    val velocityTracker = VelocityTracker()
    var dragEnabled = true

    detectVerticalDragGestures(
        onDragStart = {
            dragEnabled = !state.isDismissed
        },
        onVerticalDrag = { change, dragAmount ->
            if (dragEnabled) {
                velocityTracker.addPointerInputChange(change)
                state.dispatchRawDelta(dragAmount)
            }
        },
        onDragCancel = {
            if (dragEnabled) {
                velocityTracker.resetTracking()
                state.snapTo(state.collapsedBound)
            }
            dragEnabled = true
        },
        onDragEnd = {
            if (dragEnabled) {
                val velocity = -velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                state.performFling(velocity, null)
            } else {
                velocityTracker.resetTracking()
            }
            dragEnabled = true
        },
    )
}


@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    collapsedDragOffset: () -> Dp = { 0.dp },
    collapsedDragHeight: Dp = 0.dp,
    collapsedContentPadding: Dp = 0.dp,
    onDismiss: (() -> Unit)? = null,
    onHorizontalSwipe: ((direction: HorizontalSwipeDirection) -> Unit)? = null,
    backgroundContent: @Composable BoxScope.() -> Unit = {},
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // The collapsed glass surface draws a crisp outer stroke. Give its host a small amount of
    // headroom so the stroke is not cut by the rounded sheet clip, while compensating both the
    // sheet and the child so the mini-player keeps the same visual anchor.
    val collapsedSurfaceOffset = if (
        collapsedDragHeight > 0.dp && collapsedContentPadding > 0.dp && !state.isDismissed
    ) {
        collapsedContentPadding * (1f - state.progress).coerceIn(0f, 1f)
    } else {
        0.dp
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .offset {
                val y = (state.expandedBound - state.value)
                    .roundToPx()
                    .coerceAtLeast(0) - collapsedSurfaceOffset.roundToPx()
                IntOffset(x = 0, y = y)
            }
            .then(
                if (!state.isDismissed &&
                    !(state.isCollapsed && collapsedDragHeight > 0.dp)
                ) {
                    Modifier.bottomSheetGestureHandlers(state, onHorizontalSwipe)
                } else {
                    Modifier
                },
            )
            .clip(
                ContinuousRoundedRectangle(
                    topStart = if (!state.isExpanded) 16.dp else 0.dp,
                    topEnd = if (!state.isExpanded) 16.dp else 0.dp
                )
            )
            // The collapsed player is a floating liquid-glass capsule. Keep the sheet host
            // transparent at that anchor so the page remains visible around it, then restore
            // the full player background continuously while expanding.
            .background(backgroundColor.copy(alpha = state.progress.coerceIn(0f, 1f)))
    ) {
        // Native player backgrounds use a full-screen AndroidView. Do not keep that view in
        // the collapsed composition: even when transparent, it can remain the top hit target
        // and block the page behind the mini player.
        if (!state.isCollapsed) {
            backgroundContent()
        }

        if (!state.isCollapsed && !state.isDismissed) {
            BackHandler(onBack = state::collapseSoft)
        }

        if (!state.isCollapsed) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ((state.progress - 0.25f) * 4).coerceIn(0f, 1f)
                    },
                content = content
            )
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = 1f - (state.progress * 4).coerceAtMost(1f)
                    }
                    .fillMaxWidth()
                    .height(state.collapsedBound),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            if (collapsedDragHeight > 0.dp) {
                                collapsedDragHeight
                            } else {
                                state.collapsedBound
                            },
                        )
                        .offset {
                            val offset = if (collapsedDragHeight > 0.dp) {
                                collapsedDragOffset() + collapsedSurfaceOffset
                            } else {
                                0.dp
                            }
                            IntOffset(x = 0, y = offset.roundToPx())
                        }
                        .then(
                            if (!state.isExpanded && !state.isDismissed && collapsedDragHeight > 0.dp) {
                                Modifier.bottomSheetGestureHandlers(state, onHorizontalSwipe)
                            } else {
                                Modifier
                            },
                        ),
                    content = collapsedContent,
                )
            }
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
    relocatingCollapsedAnchor: Boolean = false,
) : DraggableState by draggableState {
    // Moving an already-collapsed sheet to a new layout anchor must not mount the invisible
    // expanded player. The sheet still follows the same animation through [value].
    private var isRelocatingCollapsedAnchor by mutableStateOf(relocatingCollapsedAnchor)

    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        isRelocatingCollapsedAnchor || value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        value == animatable.upperBound
    }

    val progress by derivedStateOf {
        if (isRelocatingCollapsedAnchor) {
            0f
        } else {
            1f -
                (animatable.upperBound!! - animatable.value) /
                (animatable.upperBound!! - collapsedBound)
        }
    }

    /** Delays expanded-player artwork until the sheet has visibly left the mini-player. */
    val revealProgress by derivedStateOf {
        ((progress - 0.12f) / 0.28f).coerceIn(0f, 1f)
    }

    internal fun finishCollapsedAnchorRelocation() {
        isRelocatingCollapsedAnchor = false
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(collapsedAnchor)
        coroutineScope.launch {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(expandedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.upperBound!!, animationSpec)
        }
    }

    private fun collapse() {
        collapse(SpringSpec())
    }

    private fun expand() {
        expand(SpringSpec())
    }

    fun collapseSoft() {
        collapse(spring(stiffness = Spring.StiffnessMediumLow))
    }

    fun expandSoft() {
        expand(spring(stiffness = Spring.StiffnessMediumLow))
    }

    fun dismiss() {
        onAnchorChanged(dismissedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.lowerBound!!)
        }
    }

    fun snapTo(value: Dp) {
        coroutineScope.launch {
            animatable.snapTo(value)
        }
    }

    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
        } else {
            val l0 = dismissedBound
            val l1 = (collapsedBound - dismissedBound) / 2
            val l2 = (expandedBound - collapsedBound) / 2
            val l3 = expandedBound

            when (value) {
                in l0..l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse()
                    }
                }

                in l1..l2 -> collapse()
                in l2..l3 -> expand()
                else -> Unit
            }
        }
    }

    val preUpPostDownNestedScrollConnection
        get() = object : NestedScrollConnection {
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
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable = remember {
        Animatable(0.dp, Dp.VectorConverter)
    }


    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue = when (previousAnchor) {
            expandedAnchor -> expandedBound
            collapsedAnchor -> collapsedBound
            dismissedAnchor -> dismissedBound
            else -> error("Unknown BottomSheet anchor")
        }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        val state = BottomSheetState(
            draggableState = DraggableState { delta ->
                coroutineScope.launch {
                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                }
            },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound,
            relocatingCollapsedAnchor =
                previousAnchor == collapsedAnchor && animatable.value != initialValue,
        )
        coroutineScope.launch {
            try {
                animatable.animateTo(initialValue, NavigationBarAnimationSpec)
            } finally {
                state.finishCollapsedAnchorRelocation()
            }
        }
        state
    }
}
// 在你的文件顶部或一个合适的位置定义这个枚举
enum class HorizontalSwipeDirection {
    Left, Right
}
