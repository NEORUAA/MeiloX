package com.ljyh.mei.ui.component.player.component.mesh

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import com.ljyh.mei.playback.PlaybackSpectrum
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/** Prepared on a worker, then owned by one renderer, including its mutable shader. */
internal class MeshAlbum internal constructor(
    internal val bitmap: Bitmap,
    internal val positions: FloatArray,
    internal val textureCoordinates: FloatArray,
    internal val indices: ShortArray,
    internal val shader: RuntimeShader,
)

/** Main-thread renderer shared by the player and its glass backdrop. */
@MainThread
internal class MeshGradientRenderer {
    private class MeshState(val album: MeshAlbum, var alpha: Float) {
        var targetAlpha = if (alpha == 1f) 0f else 1f
        val shader = album.shader
        // Each album is first rendered in its own layer, matching the old unblended FBO.
        val meshPaint = Paint().apply {
            shader = this@MeshState.shader
            blendMode = BlendMode.SRC
        }
        val compositePaint = Paint()
        val scaledPositions = FloatArray(album.positions.size)
        var viewportSize = IntSize.Zero

        fun resize(size: IntSize) {
            if (viewportSize == size) return
            viewportSize = size
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val aspect = width / height
            val scaleX = if (aspect > 1f) 1f else 1f / aspect
            val scaleY = if (aspect > 1f) aspect else 1f
            for (i in album.positions.indices step 2) {
                scaledPositions[i] = (album.positions[i] * scaleX + 1f) * width * .5f
                // OpenGL clip coordinates grow up; Canvas coordinates grow down.
                scaledPositions[i + 1] = (1f - album.positions[i + 1] * scaleY) * height * .5f
            }
        }
    }

    private val meshStates = mutableListOf<MeshState>()
    private var currentAlbum: MeshAlbum? = null
    private var accumulatedPlayingNanos = 0L
    private var lastFrameNanos: Long? = null
    private var revision = 0L
    private var recordedRevision = -1L
    private var recordedLayer: GraphicsLayer? = null
    private var recordedSize = IntSize.Zero

    var flowSpeed: Float = .25f
        set(value) {
            if (field == value) return
            field = value
            revision++
        }

    var staticMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            resetFrameClock()
            revision++
        }

    var isPlaying: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            resetFrameClock()
        }

    var audioStrength: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            // Paused/static layers may not receive another animation tick.
            if (value <= 0f) updateAudioResponse(0f)
            revision++
        }

    var spectrum: PlaybackSpectrum = PlaybackSpectrum.Zero

    val hasAlbum: Boolean get() = meshStates.isNotEmpty()

    val needsAnimation: Boolean
        get() = hasAlbum && ((!staticMode && isPlaying) || meshStates.any { it.targetAlpha != 0f })

    private var smoothedLow = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f
    private var smoothedPulse = 0f
    private var audioScale = 1f
    private var audioContrast = 1f
    private var audioSaturation = 1f

    fun setAlbum(album: MeshAlbum) {
        if (currentAlbum === album) return
        val firstAlbum = meshStates.isEmpty()
        // A returning album must reuse its fading state: its mutable shader may only
        // belong to one state, even when A -> B -> A happens before a fade completes.
        val returningState = meshStates.firstOrNull { it.album === album }
        // Hidden players do not advance fades. Release superseded invisible states
        // here while preserving the returning album and every visible crossfade.
        meshStates.removeAll { it !== returningState && it.alpha <= 0f }
        meshStates.forEach { it.targetAlpha = -1f }
        if (returningState != null) {
            // Preserve compositing order so reversing a fade does not jump visually.
            returningState.targetAlpha = 1f
        } else {
            meshStates.add(0, MeshState(album, if (firstAlbum) 1f else 0f))
        }
        currentAlbum = album
        revision++
    }

    /** Advance once per frame, even when the resulting layer is drawn more than once. */
    fun advanceFrame(frameNanos: Long) {
        val previousFrame = lastFrameNanos
        lastFrameNanos = frameNanos
        val elapsedNanos = if (previousFrame == null) 0L else (frameNanos - previousFrame).coerceAtLeast(0L)
        if (!needsAnimation) return
        if (isPlaying && !staticMode) accumulatedPlayingNanos += elapsedNanos
        val elapsedSeconds = elapsedNanos / 1e9f
        updateAudioResponse(elapsedSeconds)

        val fadeDelta = elapsedSeconds.coerceIn(0f, .1f) * 1.5f
        val iterator = meshStates.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            state.alpha += fadeDelta * state.targetAlpha
            if (state.targetAlpha > 0f && state.alpha >= 1f) {
                state.alpha = 1f
                state.targetAlpha = 0f
            }
            if (state.targetAlpha < 0f && state.alpha <= 0f) iterator.remove()
        }
        // The recorded layer can still retain an old shader/bitmap until its next record.
        // Let those references expire together instead of recycling an in-flight bitmap.
        revision++
    }

    fun resetFrameClock() {
        lastFrameNanos = null
    }

    fun draw(scope: DrawScope, layer: GraphicsLayer, renderScale: Float) = with(scope) {
        if (size.width <= 0f || size.height <= 0f) return@with
        // Keep the same bounded working resolution as the former Surface buffer.
        val scale = minOf(
            renderScale.coerceIn(.1f, 1f),
            720f / minOf(size.width, size.height),
            1440f / maxOf(size.width, size.height),
        )
        val bufferSize = IntSize(
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
        )
        if (recordedLayer !== layer || recordedSize != bufferSize || recordedRevision != revision) {
            layer.clip = true
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.record(bufferSize) {
                drawRect(Color.Black)
                drawIntoCanvas { composeCanvas -> drawMeshes(composeCanvas.nativeCanvas, bufferSize) }
            }
            recordedLayer = layer
            recordedSize = bufferSize
            recordedRevision = revision
        }
        withTransform({
            scale(size.width / bufferSize.width, size.height / bufferSize.height, Offset.Zero)
        }) { drawLayer(layer) }
    }

    private fun drawMeshes(canvas: Canvas, size: IntSize) {
        val time = accumulatedPlayingNanos / 1e9f * flowSpeed
        // Keep insertion order, oldest to newest, even when an existing state returns.
        for (index in meshStates.lastIndex downTo 0) {
            val state = meshStates[index]
            val alpha = (1f - cos(state.alpha.coerceIn(0f, 1f) * Math.PI.toFloat())) * .5f
            if (alpha <= 0f) continue
            state.resize(size)
            state.shader.setFloatUniform("time", time)
            state.shader.setFloatUniform("audioResponse", audioScale, audioContrast, audioSaturation)
            state.compositePaint.alpha = (alpha * 255f).roundToInt()
            val saveCount = canvas.saveLayer(
                0f, 0f, size.width.toFloat(), size.height.toFloat(), state.compositePaint,
            )
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                state.scaledPositions.size,
                state.scaledPositions,
                0,
                state.album.textureCoordinates,
                0,
                null,
                0,
                state.album.indices,
                0,
                state.album.indices.size,
                state.meshPaint,
            )
            canvas.restoreToCount(saveCount)
        }
    }

    private fun updateAudioResponse(seconds: Float) {
        val strength = audioStrength
        val target = spectrum
        if (strength <= 0f) {
            smoothedLow = 0f
            smoothedMid = 0f
            smoothedHigh = 0f
            smoothedPulse = 0f
        } else {
            val blend = 1f - exp(-seconds.coerceIn(0f, .1f) / .06f)
            smoothedLow += (target.low - smoothedLow) * blend
            smoothedMid += (target.mid - smoothedMid) * blend
            smoothedHigh += (target.high - smoothedHigh) * blend
            val pulseBlend = 1f - exp(-seconds.coerceIn(0f, .1f) / .035f)
            smoothedPulse += (target.pulse - smoothedPulse) * pulseBlend
        }
        val scaleBand = smoothedLow * .9f + smoothedMid * .1f
        audioScale = 1f + (scaleBand * scaleBand * .04f + smoothedPulse * .62f) * strength
        audioContrast = 1f + smoothedLow * .076f * strength
        audioSaturation = 1f + smoothedHigh * .166f * strength
    }

    companion object {
        private val random = java.util.Random()

        @WorkerThread
        fun prepareAlbum(bitmap: Bitmap, subdivision: Int, preset: ControlPointPreset? = null): MeshAlbum {
            // Scaling a 32px source can alias it, and the processor recycles its temporary.
            // Always give it a private bitmap rather than an image-loader-owned instance.
            val source = if (bitmap.isRecycled) bitmap else checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            val processed = try {
                AlbumTextureProcessor.process(source)
            } finally {
                if (source !== bitmap && !source.isRecycled) source.recycle()
            }
            val selectedPreset = preset ?: if (random.nextFloat() < .8f) {
                CONTROL_POINT_PRESETS[random.nextInt(CONTROL_POINT_PRESETS.size)]
            } else {
                generateControlPoints(random = kotlin.random.Random(random.nextLong()))
            }
            val mesh = BHPMesh(selectedPreset.width, selectedPreset.height).apply {
                resetSubdivision(subdivision)
                configureFromPreset(selectedPreset, processed)
            }
            val vertices = checkNotNull(mesh.buffer)
            val vertexCount = vertices.capacity() / 7
            require(vertexCount <= 65536) { "Mesh exceeds Canvas index range" }
            val positions = FloatArray(vertexCount * 2)
            val textureCoordinates = FloatArray(vertexCount * 2)
            for (i in 0 until vertexCount) {
                positions[i * 2] = vertices[i * 7]
                positions[i * 2 + 1] = vertices[i * 7 + 1]
                // All current presets use white vertex colors, so the shader is unchanged
                // by omitting Canvas vertex-color modulation.
                textureCoordinates[i * 2] = vertices[i * 7 + 5]
                textureCoordinates[i * 2 + 1] = vertices[i * 7 + 6]
            }
            val indexBuffer = mesh.generateIndexBuffer()
            val indices = ShortArray(mesh.indices) { indexBuffer[it].toShort() }
            // RuntimeShader compiles synchronously. Complete compilation and texture
            // binding here, before the prepared album is handed to the main thread.
            val shader = RuntimeShader(ShaderSource.MESH_SHADER).apply {
                setInputShader(
                    "albumTexture",
                    BitmapShader(processed, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
                        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    },
                )
                setFloatUniform("textureSize", processed.width.toFloat(), processed.height.toFloat())
            }
            return MeshAlbum(processed, positions, textureCoordinates, indices, shader)
        }
    }
}
