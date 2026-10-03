package com.ljyh.mei.ui.component.player.component

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ljyh.mei.constants.MeshFlowSpeedKey
import com.ljyh.mei.constants.MeshLowFreqVolumeKey
import com.ljyh.mei.constants.MeshPlayingKey
import com.ljyh.mei.constants.MeshRenderScaleKey
import com.ljyh.mei.constants.MeshStaticModeKey
import com.ljyh.mei.constants.MeshSubdivisionKey
import com.ljyh.mei.ui.component.sheet.LocalPlayerSheet
import com.ljyh.mei.ui.component.sheet.playerBackgroundAlpha
import com.ljyh.mei.ui.component.player.LocalPlayerBackdropFrame
import com.ljyh.mei.ui.component.player.component.mesh.MeshBackgroundView
import com.ljyh.mei.ui.component.utils.rememberLifecycleStarted
import com.ljyh.mei.ui.glass.trackBackdropPosition
import com.ljyh.mei.playback.PlaybackBeatMeter
import com.ljyh.mei.playback.PlaybackSpectrum
import com.ljyh.mei.utils.rememberPreference
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val BackdropCaptureShortSide = 360
private const val BackdropCaptureLongSide = 720
private const val BackdropCaptureIntervalMillis = 67L
private const val StaticBackdropCaptureAttempts = 18


@Composable
fun FluidBackground(
    imageUrl: String?,
    beatMeter: PlaybackBeatMeter,
    isPlaying: Boolean = true,
    alpha: Float = 1f,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleStarted by rememberLifecycleStarted()
    val sheet = LocalPlayerSheet.current
    val backdropFrame = LocalPlayerBackdropFrame.current
    val expanded by remember(sheet) {
        derivedStateOf { sheet == null || sheet.state.isExpanded }
    }
    val sheetVisible by remember(sheet) {
        derivedStateOf {
            sheet == null || sheet.state.isExpanded || sheet.state.isTransitioning
        }
    }
    var meshView by remember { mutableStateOf<MeshBackgroundView?>(null) }
    var surfaceReady by remember { mutableStateOf(false) }
    val backgroundVisible = alpha > 0.01f
    val backgroundOpacity = alpha.coerceIn(0f, 1f)
    // Keep EGL and the album texture across openings without rendering hidden frames.
    // Start immediately on a new gesture, before the opening fade becomes noticeable.
    val backgroundActive = lifecycleStarted && backgroundVisible && sheetVisible
    val (flowSpeed) = rememberPreference(MeshFlowSpeedKey, defaultValue = 0.25f)
    val (renderScale) = rememberPreference(MeshRenderScaleKey, defaultValue = 0.75f)
    val (staticMode) = rememberPreference(MeshStaticModeKey, defaultValue = false)
    val (meshPlaying) = rememberPreference(MeshPlayingKey, defaultValue = true)
    val (sensitivity) = rememberPreference(MeshLowFreqVolumeKey, defaultValue = 0.1f)
    val (subdivision) = rememberPreference(MeshSubdivisionKey, defaultValue = 50)

    val audioReactive = backgroundActive && expanded && isPlaying && !staticMode && sensitivity > 0f
    LaunchedEffect(meshView, beatMeter, audioReactive, sensitivity) {
        val view = meshView ?: return@LaunchedEffect
        // Update the native renderer directly: audio samples must not recompose the player.
        // The existing sensitivity setting ranges from 0 to 0.5; visual limits stay bounded.
        view.setAudioStrength(if (audioReactive) (sensitivity * 2f).coerceIn(0f, 1f) else 0f)
        try {
            if (audioReactive) beatMeter.spectrum.collect { view.updateSpectrum(it) }
        } finally {
            view.updateSpectrum(PlaybackSpectrum.Zero)
            view.setAudioStrength(0f)
        }
    }

    // 1. 将图片加载逻辑独立出来，只负责把 Bitmap 提取出来
    // 使用 produceState 是处理这种“异步数据转同步状态”的最佳实践
    val albumBitmap by produceState<Bitmap?>(null, imageUrl) {
        if (imageUrl.isNullOrEmpty()) {
            value = null
            return@produceState
        }
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(256)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                // Detach from Coil's bitmap pool: the GL renderer owns this instance and
                // recycles it on track change, which must never corrupt a pooled bitmap.
                val decoded = result.image.toBitmap()
                value = decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: decoded
            }
        }
    }

    // Push the album exactly once per bitmap change. Calling setAlbum from AndroidView's
    // update block re-fires on every recomposition (including sheet animation),
    // and each call restarts the renderer's cross-fade with a new random mesh preset,
    // which is the visible flicker/dark-dip source.
    LaunchedEffect(meshView, albumBitmap) {
        val view = meshView ?: return@LaunchedEffect
        val bitmap = albumBitmap ?: return@LaunchedEffect
        // Track changes belong entirely to the GL renderer's crossfade. Keep the current
        // Surface visible while the next texture loads; never insert a static cover here.
        view.setAlbum(bitmap)
    }

    // 2. 组装当前需要传递给 View 的所有状态
    val shouldAnimate = !meshPlaying || isPlaying
    val pixelCopyHandler = remember { Handler(Looper.getMainLooper()) }
    val captureBuffers = remember { arrayOfNulls<Bitmap>(3) }
    val captureState = remember { IntArray(3) }
    val capturedFrameVersion = remember { longArrayOf(-1L) }

    // Configuration changes are infrequent compared with sheet recompositions. Apply
    // renderer settings from their state boundary instead of queueing GL work from every
    // AndroidView update pass.
    LaunchedEffect(meshView, flowSpeed, renderScale, subdivision, staticMode, shouldAnimate) {
        val view = meshView ?: return@LaunchedEffect
        view.setFlowSpeed(flowSpeed)
        view.setRenderScale(renderScale)
        view.setSubdivision(subdivision)
        view.setStaticMode(staticMode)
        view.setPlaying(shouldAnimate)
    }

    LaunchedEffect(meshView, lifecycleStarted) {
        meshView?.setHostStarted(lifecycleStarted)
    }

    LaunchedEffect(meshView, backgroundActive) {
        meshView?.setRenderingRequested(backgroundActive)
    }

    // SurfaceView is not part of Compose's graphics-layer recording. Copy a small live frame
    // instead; glass blurs it heavily, so this resolution preserves the visual result without
    // reading a full-screen buffer every frame. Static mode captures through the mesh fade-in
    // and then stops, while animated mode keeps the sample moving at roughly 15 fps.
    LaunchedEffect(meshView, backdropFrame, albumBitmap, staticMode, shouldAnimate, backgroundActive) {
        if (!backgroundActive) return@LaunchedEffect
        val view = meshView ?: return@LaunchedEffect
        val target = backdropFrame ?: return@LaunchedEffect
        var attempts = 0
        val continuous = !staticMode && shouldAnimate

        delay(BackdropCaptureIntervalMillis)
        while (isActive && (continuous || attempts < StaticBackdropCaptureAttempts)) {
            val sourceWidth = view.width
            val sourceHeight = view.height
            val frameVersion = view.renderedFrameVersion
            if (view.hasRenderedAlbum && view.isAttachedToWindow && sourceWidth > 0 && sourceHeight > 0 &&
                frameVersion != capturedFrameVersion[0]
            ) {
                val shortSide = minOf(sourceWidth, sourceHeight).toFloat()
                val longSide = maxOf(sourceWidth, sourceHeight).toFloat()
                val scale = minOf(
                    1f,
                    BackdropCaptureShortSide / shortSide,
                    BackdropCaptureLongSide / longSide,
                )
                val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                if (captureBuffers[0] == null ||
                    width != captureState[0] ||
                    height != captureState[1]
                ) {
                    captureState[0] = width
                    captureState[1] = height
                    captureBuffers.indices.forEach { index ->
                        captureBuffers[index] =
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    }
                    captureState[2] = 0
                }

                val bitmap = captureBuffers[captureState[2]] ?: return@LaunchedEffect
                captureState[2] = (captureState[2] + 1) % captureBuffers.size
                if (copySurfaceFrame(view, bitmap, pixelCopyHandler)) {
                    if (!isActive) return@LaunchedEffect
                    target.value = bitmap.asImageBitmap()
                    capturedFrameVersion[0] = frameVersion
                }
            }
            // Do not exhaust static capture attempts before the first album reaches GL.
            if (view.hasRenderedAlbum && view.isAttachedToWindow && sourceWidth > 0 && sourceHeight > 0) {
                attempts++
            }
            delay(BackdropCaptureIntervalMillis)
        }
    }

    Box(modifier.fillMaxSize()) {
        // This empty Compose node owns the recording coordinates. Its custom Backdrop draw
        // reads only [backdropFrame], so glass never re-records the native GL Surface.
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .trackBackdropPosition(backdrop),
        )
        AndroidView(
            factory = { ctx ->
                MeshBackgroundView(ctx).apply {
                    meshView = this
                    this.alpha = 0f
                    onSurfaceReadyChanged = { surfaceReady = it }
                    setFlowSpeed(flowSpeed)
                    setRenderScale(renderScale)
                    setSubdivision(subdivision)
                    setStaticMode(staticMode)
                    setPlaying(shouldAnimate)
                    setPreserveEGLContextOnPause(true)
                    setHostStarted(lifecycleStarted)
                    setRenderingRequested(backgroundActive)
                }
            },
            update = { view ->
                // Only this Surface is visible: no copied frame or curtain handoff at
                // either anchor. Read progress here to update just the native view.
                view.alpha = if (surfaceReady) backgroundOpacity *
                    (sheet?.state?.progress?.let(::playerBackgroundAlpha) ?: 1f) else 0f

            },
            onRelease = { view -> view.onSurfaceReadyChanged = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private suspend fun copySurfaceFrame(
    source: MeshBackgroundView,
    destination: Bitmap,
    callbackHandler: Handler,
): Boolean = suspendCancellableCoroutine { continuation ->
    try {
        PixelCopy.request(
            source,
            destination,
            { result ->
                if (continuation.isActive) {
                    continuation.resume(result == PixelCopy.SUCCESS)
                }
            },
            callbackHandler,
        )
    } catch (_: IllegalArgumentException) {
        continuation.resume(false)
    }
}
