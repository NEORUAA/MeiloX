package com.ljyh.mei.ui.component.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI

// Miuix's standard 0.4s critically damped spring, expressed as unit-mass stiffness.
private val RefreshReboundSpring = spring<Float>(
    dampingRatio = 1f,
    stiffness = (4.0 * PI * PI / (0.4 * 0.4)).toFloat(),
    visibilityThreshold = 0.1f,
)

/** Follow the existing pull gesture, then rebound independently of the refresh request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Modifier.pullToRefreshRebound(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    enabled: Boolean = true,
): Modifier {
    val threshold = with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    val rebound = remember(state) { Animatable(0f) }
    var followingPull by remember(state) { mutableStateOf(false) }
    val refreshingNow by rememberUpdatedState(isRefreshing)
    val enabledNow by rememberUpdatedState(enabled)

    LaunchedEffect(state, threshold) {
        var canFollowPull = true
        var lastPullOffset = 0f
        var returnAnimation: Job? = null
        snapshotFlow {
            Triple(state.distanceFraction, state.isAnimating || refreshingNow, enabledNow)
        }.collect { (fraction, released, available) ->
            if (!available) {
                returnAnimation?.cancel()
                rebound.snapTo(0f)
                followingPull = false
                canFollowPull = false
                lastPullOffset = 0f
            } else if (released) {
                canFollowPull = false
                if (followingPull) {
                    val offset = maxOf(lastPullOffset, rebound.value)
                    returnAnimation?.cancel()
                    rebound.snapTo(offset)
                    followingPull = false
                    returnAnimation = launch { rebound.animateTo(0f, RefreshReboundSpring) }
                }
            } else if (fraction <= 0f) {
                canFollowPull = true
                followingPull = false
                lastPullOffset = 0f
            } else if (canFollowPull) {
                lastPullOffset = fraction * threshold
                followingPull = true
            }
        }
    }
    return this.clipToBounds().graphicsLayer {
        translationY = when {
            !enabled -> 0f
            followingPull -> maxOf(state.distanceFraction.coerceAtLeast(0f) * threshold, rebound.value)
            else -> rebound.value
        }
    }
}
