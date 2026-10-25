package com.ljyh.mei.ui.component.utils

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import top.yukonga.miuix.kmp.utils.MiuixOverscrollFactory

internal val LocalPageOverscrollFactory = staticCompositionLocalOf<OverscrollFactory?> { null }

/** Cover short, empty, and loading pages without adding a logical scroll range. */
@Composable
internal fun PageOverscrollHost(content: @Composable () -> Unit) {
    val page = remember { PageOverscrollScope() }
    val factory = remember(page) { PageOverscrollFactory(page) }
    val stationaryScroll = rememberScrollableState { 0f }
    CompositionLocalProvider(
        LocalPageOverscrollFactory provides factory,
        LocalOverscrollFactory provides factory,
    ) {
        Box(
            Modifier.fillMaxSize()
                .overscroll(page.effect)
                .nestedScroll(page.connection)
                .scrollable(stationaryScroll, Orientation.Vertical),
        ) { content() }
    }
}

private class PageOverscrollScope {
    val effect = MiuixOverscrollFactory.createOverscrollEffect()
    var nativeScrollDepth = 0
    var nativeFlingDepth = 0
    private var hasFallbackDrag = false

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Scrollable children keep their own effect. Refresh and other nested consumers
            // have already had their turn before the fallback receives this remainder.
            if (nativeScrollDepth > 0 || available.y == 0f || source != NestedScrollSource.UserInput) {
                return Offset.Zero
            }
            hasFallbackDrag = true
            return effect.applyToScroll(Offset(0f, available.y), source) { Offset.Zero }
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (nativeFlingDepth > 0) {
                // Data can become scrollable during a pull. Settle the old page offset
                // without taking any velocity away from the newly scrollable child.
                if (hasFallbackDrag || effect.isInProgress) {
                    effect.applyToFling(Velocity.Zero) { Velocity.Zero }
                    hasFallbackDrag = false
                }
                return Velocity.Zero
            }
            if (!hasFallbackDrag && available.y == 0f) return Velocity.Zero
            val velocity = Velocity(0f, available.y)
            effect.applyToFling(velocity) { Velocity.Zero }
            hasFallbackDrag = false
            return velocity
        }
    }
}

private data class PageOverscrollFactory(private val page: PageOverscrollScope) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        TrackedPageOverscrollEffect(page, MiuixOverscrollFactory.createOverscrollEffect())
}

/** Track only the original effect's dispatch; its drawing node and physics stay intact. */
private class TrackedPageOverscrollEffect(
    private val page: PageOverscrollScope,
    private val delegate: OverscrollEffect,
) : OverscrollEffect {
    override val node = delegate.node
    override val isInProgress: Boolean get() = delegate.isInProgress

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val vertical = delta.y != 0f
        if (vertical) page.nativeScrollDepth++
        try {
            return delegate.applyToScroll(delta, source, performScroll)
        } finally {
            if (vertical) page.nativeScrollDepth--
        }
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        val vertical = velocity.y != 0f
        if (vertical) page.nativeFlingDepth++
        try {
            delegate.applyToFling(velocity, performFling)
        } finally {
            if (vertical) page.nativeFlingDepth--
        }
    }
}
