package com.ljyh.mei.ui.screen.playlist.component

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.LayerOutsets
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.ljyh.mei.R
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.ui.component.sheet.LocalMiniPlayerVerticalOffset
import com.ljyh.mei.ui.component.sheet.LocalMiniPlayerSearchBounds
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.LocalSfToolbarSymbols
import io.github.neoruaa.glasmio.ui.CompactBottomControlSize
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val LocateButtonIdleMillis = 3_000L
private const val LocateLayoutTimeoutMillis = 10_000L

internal class PlaylistLocateRegistration(
    val listState: LazyListState,
    val track: TrackRef,
    val leadingItems: Int,
) {
    var modifier: Modifier = Modifier
    var available by mutableStateOf(true)
    var visible by mutableStateOf(true)
    var locating by mutableStateOf(false)
    var resolveIndex: suspend () -> Int? = { null }
    val shouldShow: Boolean get() = (available && visible) || locating
}

internal class PlaylistLocateHostState {
    private val registrations = mutableStateMapOf<Any, PlaylistLocateRegistration>()
    var activeEntryKey by mutableStateOf<Any?>(null)
    var navigationVisible by mutableStateOf(true)

    val registration: PlaylistLocateRegistration?
        get() = registrationFor(activeEntryKey)

    fun registrationFor(entryKey: Any?): PlaylistLocateRegistration? =
        entryKey?.let { registrations[it] }

    fun register(entryKey: Any?, value: PlaylistLocateRegistration) {
        if (entryKey != null) registrations[entryKey] = value
    }

    fun unregister(entryKey: Any?, value: PlaylistLocateRegistration) {
        if (entryKey != null && registrations[entryKey] === value) registrations.remove(entryKey)
    }
}

internal val LocalPlaylistLocateHost =
    androidx.compose.runtime.staticCompositionLocalOf<PlaylistLocateHostState?> { null }

/** Retained navigation entries must never publish another entry's locator into their header. */
internal val LocalPlaylistLocateOwner =
    androidx.compose.runtime.staticCompositionLocalOf<Any?> { null }

@Composable
internal fun PlaylistLocateButton(
    listState: LazyListState,
    track: TrackRef,
    leadingItems: Int,
    modifier: Modifier = Modifier,
    available: Boolean = true,
    findTrackIndex: suspend () -> Int?,
) {
    val context = LocalContext.current
    val host = LocalPlaylistLocateHost.current
    val owner = LocalPlaylistLocateOwner.current
    val registration = remember(owner, listState, track, leadingItems) {
        PlaylistLocateRegistration(listState, track, leadingItems)
    }
    val resolveIndex by rememberUpdatedState(findTrackIndex)

    SideEffect {
        registration.modifier = modifier
        registration.available = available
        registration.resolveIndex = resolveIndex
        host?.register(owner, registration)
    }
    DisposableEffect(host, owner, registration) {
        onDispose { host?.unregister(owner, registration) }
    }

    LaunchedEffect(registration, available) {
        if (!available) return@LaunchedEffect
        registration.visible = true
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                registration.visible = true
            } else {
                delay(LocateButtonIdleMillis)
                registration.visible = false
            }
        }
    }

    LaunchedEffect(registration.locating, listState, track, leadingItems) {
        if (!registration.locating) return@LaunchedEffect
        try {
            val index = registration.resolveIndex()?.plus(leadingItems)
            val layout = index?.let { targetIndex ->
                // The source can publish the row before the next lazy list layout.
                withTimeoutOrNull(LocateLayoutTimeoutMillis) {
                    snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > targetIndex }
                }
            }
            if (index == null || layout == null) {
                Toast.makeText(context, R.string.playlist_locate_track_failed, Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
            val usableHeight = (layout.viewportEndOffset - layout.afterContentPadding).coerceAtLeast(0)
            listState.animateScrollToItem(index, scrollOffset = -usableHeight / 3)
        } finally {
            registration.locating = false
        }
    }

    // The activity renders the registered control in the chrome layer. Keep a
    // fallback here for previews and isolated callers that do not provide a host.
    if (host == null || owner == null) {
        PlaylistLocateButtonChrome(registration, modifier, navigationVisible = true)
    }
}

@Composable
internal fun PlaylistLocateChromeLayer(
    host: PlaylistLocateHostState,
    bottomPadding: () -> Dp,
    topBarMode: Boolean = false,
) {
    host.registration?.let { registration ->
        PlaylistLocateButtonChrome(
            registration = registration,
            modifier = registration.modifier,
            bottomPadding = bottomPadding,
            navigationVisible = host.navigationVisible && !topBarMode,
        )
    }
}

@Composable
private fun PlaylistLocateButtonChrome(
    registration: PlaylistLocateRegistration,
    modifier: Modifier,
    bottomPadding: () -> Dp = { 0.dp },
    navigationVisible: Boolean,
) {
    val miniPlayerVerticalOffset = LocalMiniPlayerVerticalOffset.current
    val searchBounds = LocalMiniPlayerSearchBounds.current
    var buttonBounds by remember(registration) { mutableStateOf<Rect?>(null) }

    AnimatedVisibility(
        visible = navigationVisible && registration.shouldShow,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = modifier
            .onGloballyPositioned { buttonBounds = it.boundsInWindow() }
            .offset {
                val x = searchBounds?.right?.minus(buttonBounds?.right ?: searchBounds.right) ?: 0f
                androidx.compose.ui.unit.IntOffset(
                    x.roundToInt(),
                    (miniPlayerVerticalOffset() - bottomPadding()).toPx().roundToInt(),
                )
            }
            .zIndex(0f),
    ) {
        val actionProgress by transition.animateFloat(
            transitionSpec = { spring(dampingRatio = 0.75f, stiffness = 228.1251f) },
            label = "PlaylistLocateToolbarAction",
        ) { state ->
            when (state) {
                EnterExitState.Visible -> 1f
                EnterExitState.PreEnter, EnterExitState.PostExit -> 0f
            }
        }
        Box(
            Modifier.graphicsLayer {
                // Match GlasmioNavigationScaffold's group/member toolbar motion.
                val p = actionProgress.coerceIn(0f, 1f)
                val scale = .24f + .92f * p - .16f * p * p
                alpha = p * p
                scaleX = scale
                scaleY = scale
                outsets = LayerOutsets(32.dp, 32.dp, 32.dp, 32.dp)
                val blur = 10.dp.toPx() * (1f - p)
                renderEffect = if (blur > .01f) {
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL).asComposeRenderEffect()
                } else null
            },
        ) {
            GlasmioIconButton(
                onClick = { registration.locating = true },
                backdrop = LocalGlassBackdrop.current,
                style = GlassSurfaceStyle.Navigation,
                nativeNavigationFlex = true,
                size = CompactBottomControlSize,
                enabled = !registration.locating,
            ) {
                CompositionLocalProvider(LocalSfToolbarSymbols provides true) {
                    if (registration.locating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        SfIcon("scope", stringResource(R.string.playlist_locate_current_track))
                    }
                }
            }
        }
    }
}
