package com.ljyh.mei.ui.component.player.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.neoruaa.glasmio.glass.render.backdrops.LayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import com.ljyh.mei.constants.MeshFlowSpeedKey
import com.ljyh.mei.constants.MeshLowFreqVolumeKey
import com.ljyh.mei.constants.MeshPlayingKey
import com.ljyh.mei.constants.MeshRenderScaleKey
import com.ljyh.mei.constants.MeshStaticModeKey
import com.ljyh.mei.constants.MeshSubdivisionKey
import com.ljyh.mei.playback.PlaybackBeatMeter
import com.ljyh.mei.playback.PlaybackSpectrum
import com.ljyh.mei.ui.component.player.LocalPlayerBackgroundReadyChanged
import com.ljyh.mei.ui.component.player.component.mesh.MeshAlbum
import com.ljyh.mei.ui.component.player.component.mesh.MeshGradientRenderer
import com.ljyh.mei.ui.component.player.component.mesh.runMeshFrames
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.playerBackgroundAlpha
import com.ljyh.mei.ui.component.utils.LifecycleStartedEffect
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import com.ljyh.mei.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FluidBackground(
    imageUrl: String?,
    beatMeter: PlaybackBeatMeter,
    isPlaying: Boolean = true,
    alpha: Float = 1f,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sheet = LocalPlayerSheet.current
    val onBackgroundReadyChanged by rememberUpdatedState(LocalPlayerBackgroundReadyChanged.current)
    val expanded by remember(sheet) {
        derivedStateOf { sheet == null || sheet.state.isExpanded }
    }
    val sheetVisible by remember(sheet) {
        derivedStateOf { sheet == null || sheet.state.isExpanded || sheet.state.isTransitioning }
    }
    val backgroundActive = alpha > 0.01f && sheetVisible
    val (flowSpeed) = rememberPreference(MeshFlowSpeedKey, defaultValue = 0.25f)
    val (renderScale) = rememberPreference(MeshRenderScaleKey, defaultValue = 0.75f)
    val (staticMode) = rememberPreference(MeshStaticModeKey, defaultValue = false)
    val (meshPlaying) = rememberPreference(MeshPlayingKey, defaultValue = true)
    val (sensitivity) = rememberPreference(MeshLowFreqVolumeKey, defaultValue = 0.1f)
    val (subdivision) = rememberPreference(MeshSubdivisionKey, defaultValue = 50)
    val currentSubdivision by rememberUpdatedState(subdivision)
    val renderer = remember { MeshGradientRenderer() }
    val meshLayer = rememberGraphicsLayer()
    val frameVersion = remember { mutableIntStateOf(0) }
    val shouldAnimate = !meshPlaying || isPlaying
    val audioReactive = backgroundActive && expanded && isPlaying && !staticMode && sensitivity > 0f

    // Each renderer owns its gray album's mutable shader. Prepare it once, off main,
    // so loading and failed artwork use the same mesh pipeline as real covers.
    val placeholderAlbum by produceState<MeshAlbum?>(null, renderer) {
        val preparedSubdivision = currentSubdivision
        value = withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF808080.toInt())
            }
            try {
                MeshGradientRenderer.prepareAlbum(bitmap, preparedSubdivision)
            } finally {
                bitmap.recycle()
            }
        }
    }

    // Keep the current album while the next request loads. Missing or failed
    // artwork falls back to gray without making the player transparent.
    val loadedAlbum by produceState<MeshAlbum?>(null, imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            value = null
            return@produceState
        }
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(imageUrl)
                .size(256)
                .allowHardware(false)
                .build(),
        )
        value = if (result is SuccessResult) {
            withContext(Dispatchers.Default) {
                MeshGradientRenderer.prepareAlbum(result.image.toBitmap(), currentSubdivision)
            }
        } else null
    }
    val album = loadedAlbum ?: placeholderAlbum

    LaunchedEffect(renderer, flowSpeed, staticMode, shouldAnimate) {
        renderer.flowSpeed = flowSpeed
        renderer.staticMode = staticMode
        renderer.isPlaying = shouldAnimate
        frameVersion.intValue++
    }

    LaunchedEffect(renderer, album) {
        album?.let(renderer::setAlbum)
        frameVersion.intValue++
        // Publish readiness after a complete mesh is available, including the placeholder.
        onBackgroundReadyChanged(renderer.hasAlbum)
    }

    LifecycleStartedEffect(renderer, beatMeter, audioReactive, sensitivity) {
        renderer.audioStrength = if (audioReactive) (sensitivity * 2f).coerceIn(0f, 1f) else 0f
        try {
            if (audioReactive) beatMeter.spectrum.collect { renderer.spectrum = it }
        } finally {
            renderer.spectrum = PlaybackSpectrum.Zero
            renderer.audioStrength = 0f
            frameVersion.intValue++
        }
    }

    LifecycleStartedEffect(renderer, backgroundActive, album, staticMode, shouldAnimate, audioReactive) {
        // Never include elapsed hidden/stopped time in flow or crossfade progression.
        renderer.resetFrameClock()
        if (!backgroundActive) return@LifecycleStartedEffect
        runMeshFrames(
            intervalNanos = if (audioReactive) 16_666_667L else 33_333_333L,
            needsAnimation = { renderer.needsAnimation },
        ) { now ->
            renderer.advanceFrame(now)
            frameVersion.intValue++
        }
    }

    DisposableEffect(Unit) {
        onDispose { onBackgroundReadyChanged(false) }
    }

    Box(
        modifier
            .fillMaxSize()
            // Presentation fades outside the recording so glass samples the full mesh.
            .graphicsLayer {
                // The mesh renderer already flattens album crossfades into one texture.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                this.alpha = alpha.coerceIn(0f, 1f) *
                    (sheet?.state?.progress?.let(::playerBackgroundAlpha) ?: 1f)
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .trackBackdropPosition(backdrop, frameVersion)
                .drawBehind {
                    frameVersion.intValue
                    if (renderer.hasAlbum) {
                        renderer.draw(this, meshLayer, renderScale)
                    } else {
                        // Match the neutral cover after the texture processor's 0.75 brightness
                        // while its first shader is prepared, without exposing the page below.
                        drawRect(Color(0xFF606060))
                    }
                },
        )
    }
}
