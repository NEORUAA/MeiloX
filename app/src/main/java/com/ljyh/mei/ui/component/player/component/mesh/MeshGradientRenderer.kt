package com.ljyh.mei.ui.component.player.component.mesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import timber.log.Timber
import com.ljyh.mei.playback.PlaybackSpectrum
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

private const val TAG = "MeshGradientRenderer"

class MeshGradientRenderer : GLSurfaceView.Renderer {
    private data class MeshState(
        val vertexBuffer: Int,
        val indexBuffer: Int,
        val indexCount: Int,
        val textureId: Int,
        var alpha: Float,
        var targetAlpha: Float
    )

    private var mainProgram: Int = 0
    private var quadProgram: Int = 0
    private var quadVertexBuffer: Int = 0

    private var mainAPos = 0
    private var mainAColor = 0
    private var mainAUv = 0
    private var mainUTexture = 0
    private var mainUTime = 0
    private var mainUAudioResponse = 0
    private var mainUAspect = 0
    private var quadAPos = 0
    private var quadATexCoord = 0
    private var quadUTexture = 0
    private var quadUAlpha = 0

    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(16 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            )
            position(0)
        }

    private var fbo: Int = 0
    private var fboTexture: Int = 0

    private val meshStates = mutableListOf<MeshState>()

    private var scaledWidth: Int = 0
    private var scaledHeight: Int = 0
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    private var accumulatedPlayingNanos: Long = 0L
    private var lastFrameNanos: Long = System.nanoTime()

    @Volatile
    private var isPlaying: Boolean = true

    @Volatile
    var spectrum: PlaybackSpectrum = PlaybackSpectrum.Zero

    @Volatile
    var audioStrength: Float = 0f

    private var smoothedLow = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f
    private var smoothedPulse = 0f
    private var audioScale = 1f
    private var audioContrast = 1f
    private var audioSaturation = 1f

    @Volatile
    var flowSpeed: Float = 0.25f

    @Volatile
    var subdivision: Int = 50

    private var staticMode: Boolean = false
    private var isStatic: Boolean = false

    @Volatile
    var hasRenderedAlbum = false
        private set

    @Volatile
    var renderedFrameVersion = 0L
        private set

    @Volatile
    var onSurfaceReadyChanged: ((Boolean) -> Unit)? = null

    var onRenderDemandChanged: ((Boolean) -> Unit)? = null
    private var continuousRenderingNeeded = true

    private var pendingAlbum: Bitmap? = null
    private var albumChanged: Boolean = false
    private var currentAlbum: Bitmap? = null

    private val random = java.util.Random()

    fun setAlbum(bitmap: Bitmap) {
        synchronized(this) {
            // Idempotent: re-pushing the instance already being shown must not restart
            // the cross-fade (which also re-rolls a random mesh preset).
            if (bitmap === pendingAlbum || bitmap === currentAlbum) return
            val old = pendingAlbum
            if (old !== null && old !== bitmap) {
                old.recycle()
            }
            pendingAlbum = bitmap
            albumChanged = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        hasRenderedAlbum = false
        onSurfaceReadyChanged?.invoke(false)

        Timber.tag(TAG).d("GPU 渲染器: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
        Timber.tag(TAG).d("GPU 厂商: ${GLES30.glGetString(GLES30.GL_VENDOR)}")
        Timber.tag(TAG).d("GL 版本: ${GLES30.glGetString(GLES30.GL_VERSION)}")

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        mainProgram =
            createProgram(ShaderSource.MESH_VERTEX_SHADER, ShaderSource.MESH_FRAGMENT_SHADER)
        quadProgram =
            createProgram(ShaderSource.QUAD_VERTEX_SHADER, ShaderSource.QUAD_FRAGMENT_SHADER)
        cacheShaderLocations()
        val quadIds = IntArray(1)
        GLES30.glGenBuffers(1, quadIds, 0)
        quadVertexBuffer = quadIds[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVertexBuffer)
        quadBuffer.position(0)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, quadBuffer.capacity() * Float.SIZE_BYTES,
            quadBuffer, GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        synchronized(this) {
            // A recreated surface means every previous GL object is gone. Drop the stale
            // ids without glDelete* (they may collide with new-context objects) and rebuild
            // from the last album, otherwise dead textures sample as opaque black.
            meshStates.clear()
            fbo = 0
            fboTexture = 0
            val album = currentAlbum
            if (album != null && !album.isRecycled && pendingAlbum == null) {
                pendingAlbum = album
                albumChanged = true
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        rebuildFbo()
    }

    fun rebuildFbo() {
        isStatic = false
        // Surface buffers already use the requested working resolution. Let SurfaceFlinger
        // scale that buffer to the View, instead of upscaling it once more in this GL pass.
        scaledWidth = maxOf(1, viewWidth)
        scaledHeight = maxOf(1, viewHeight)
        createFbo(scaledWidth, scaledHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        processPendingAlbum()

        if (meshStates.isEmpty() || fbo == 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            updateRenderDemand()
            return
        }

        if (staticMode && isStatic) {
            updateRenderDemand()
            return
        }

        val now = System.nanoTime()
        val playing = isPlaying
        val frameDelta = now - lastFrameNanos
        lastFrameNanos = now
        if (playing) {
            accumulatedPlayingNanos += frameDelta
        }
        val time = accumulatedPlayingNanos / 1e9f * flowSpeed
        updateAudioResponse(frameDelta / 1e9f)

        updateMeshStates(1f / 60f)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        for (i in meshStates.lastIndex downTo 0) {
            val state = meshStates[i]
            val easeAlpha = easeInOutSine(state.alpha.coerceIn(0f, 1f))
            if (easeAlpha <= 0.0f) continue

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, scaledWidth, scaledHeight)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            drawMesh(state, time)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(
                GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA
            )
            drawQuad(fboTexture, easeAlpha)
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        renderedFrameVersion++
        if (!hasRenderedAlbum) {
            hasRenderedAlbum = true
            onSurfaceReadyChanged?.invoke(true)
        }
        updateRenderDemand()
    }

    private fun updateRenderDemand() {
        // Pausing flow must not stop an album crossfade halfway through. Once both are
        // settled, the existing Surface buffer is sufficient until a property changes.
        val needed = meshStates.isNotEmpty() &&
            ((!staticMode && isPlaying) || meshStates.any { it.targetAlpha != 0f })
        if (needed != continuousRenderingNeeded) {
            continuousRenderingNeeded = needed
            onRenderDemandChanged?.invoke(needed)
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
            // Interpolate 30 Hz analysis at the actual rendering cadence, without another timer.
            val blend = 1f - exp(-seconds.coerceIn(0f, .1f) / .06f)
            smoothedLow += (target.low - smoothedLow) * blend
            smoothedMid += (target.mid - smoothedMid) * blend
            smoothedHigh += (target.high - smoothedHigh) * blend
            val pulseBlend = 1f - exp(-seconds.coerceIn(0f, .1f) / .035f)
            smoothedPulse += (target.pulse - smoothedPulse) * pulseBlend
        }
        val scaleBand = smoothedLow * .9f + smoothedMid * .1f
        // Keep sustained loudness subtle; most of the travel belongs to individual bass rises.
        // At 50% sensitivity this permits up to 1.66x zoom, without collapsing the UV range.
        audioScale = 1f + (scaleBand * scaleBand * .04f + smoothedPulse * .62f) * strength
        audioContrast = 1f + smoothedLow * .076f * strength
        audioSaturation = 1f + smoothedHigh * .166f * strength
    }

    private fun processPendingAlbum() {
        synchronized(this) {
            if (!albumChanged) return
            albumChanged = false
            val bitmap = pendingAlbum ?: return
            pendingAlbum = null

            val previous = currentAlbum
            currentAlbum = bitmap
            if (previous !== null && previous !== bitmap) {
                // Its texture was uploaded when it was processed; the source is ours now.
                previous.recycle()
            }

            val processed = AlbumTextureProcessor.process(bitmap)
            val textureId = uploadTexture(processed)

            val preset = selectPreset()
            val mesh = BHPMesh(preset.width, preset.height)
            mesh.resetSubdivision(subdivision)
            mesh.configureFromPreset(preset, processed)

            processed.recycle()

            isStatic = false
            // A new surface has no previous image to crossfade from.
            val newState = uploadMesh(mesh, textureId, if (meshStates.isEmpty()) 1f else 0f)
            for (existing in meshStates) {
                existing.targetAlpha = -1f
            }
            meshStates.add(0, newState)
        }
    }

    private fun selectPreset(): ControlPointPreset {
        return if (random.nextFloat() < 0.8f) {
            CONTROL_POINT_PRESETS[random.nextInt(CONTROL_POINT_PRESETS.size)]
        } else {
            generateControlPoints(random = kotlin.random.Random(random.nextLong()))
        }
    }

    private fun updateMeshStates(dt: Float) {
        val deltaFactor = dt * 1.5f

        val iter = meshStates.iterator()
        while (iter.hasNext()) {
            val state = iter.next()
            state.alpha += deltaFactor * state.targetAlpha

            if (state.targetAlpha > 0f && state.alpha >= 1f) {
                state.alpha = 1f
                state.targetAlpha = 0f
            }

            if (state.alpha <= 0f && state.targetAlpha < 0f) {
                deleteMesh(state)
                iter.remove()
            }
        }

        if (staticMode && meshStates.size == 1 && meshStates[0].alpha >= 1f) {
            isStatic = true
        }
    }

    fun setStaticMode(enable: Boolean) {
        staticMode = enable
        if (!enable) isStatic = false
    }

    fun setPlaying(playing: Boolean) {
        if (!isPlaying && playing) {
            lastFrameNanos = System.nanoTime()
        }
        isPlaying = playing
    }

    private fun easeInOutSine(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return (1f - cos(clamped * Math.PI.toFloat())) / 2f
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_MIRRORED_REPEAT
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_MIRRORED_REPEAT
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return texIds[0]
    }

    private fun uploadMesh(mesh: BHPMesh, textureId: Int, alpha: Float): MeshState {
        val vertices = checkNotNull(mesh.buffer)
        val indices = mesh.generateIndexBuffer()
        val buffers = IntArray(2)
        GLES30.glGenBuffers(buffers.size, buffers, 0)
        // Geometry is immutable for an album. Client arrays otherwise make the driver
        // validate and upload the same vertices/indices on every animated frame.
        vertices.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, vertices.capacity() * Float.SIZE_BYTES,
            vertices, GLES30.GL_STATIC_DRAW,
        )
        indices.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.capacity() * Int.SIZE_BYTES,
            indices, GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        return MeshState(buffers[0], buffers[1], mesh.indices, textureId, alpha, 1f)
    }

    private fun deleteMesh(state: MeshState) {
        GLES30.glDeleteBuffers(2, intArrayOf(state.vertexBuffer, state.indexBuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(state.textureId), 0)
    }

    private fun drawMesh(state: MeshState, time: Float) {
        GLES30.glUseProgram(mainProgram)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, state.textureId)
        GLES30.glUniform1i(mainUTexture, 0)
        GLES30.glUniform1f(mainUTime, time)
        GLES30.glUniform3f(mainUAudioResponse, audioScale, audioContrast, audioSaturation)
        GLES30.glUniform1f(
            mainUAspect,
            if (scaledHeight > 0) scaledWidth.toFloat() / scaledHeight else 1f
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, state.vertexBuffer)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, state.indexBuffer)
        val strideBytes = 7 * 4

        GLES30.glEnableVertexAttribArray(mainAPos)
        GLES30.glVertexAttribPointer(mainAPos, 2, GLES30.GL_FLOAT, false, strideBytes, 0)

        GLES30.glEnableVertexAttribArray(mainAColor)
        GLES30.glVertexAttribPointer(mainAColor, 3, GLES30.GL_FLOAT, false, strideBytes, 2 * Float.SIZE_BYTES)

        GLES30.glEnableVertexAttribArray(mainAUv)
        GLES30.glVertexAttribPointer(mainAUv, 2, GLES30.GL_FLOAT, false, strideBytes, 5 * Float.SIZE_BYTES)

        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            state.indexCount,
            GLES30.GL_UNSIGNED_INT,
            0,
        )

        GLES30.glDisableVertexAttribArray(mainAPos)
        GLES30.glDisableVertexAttribArray(mainAColor)
        GLES30.glDisableVertexAttribArray(mainAUv)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawQuad(textureId: Int, alpha: Float) {
        GLES30.glUseProgram(quadProgram)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(quadUTexture, 0)
        GLES30.glUniform1f(quadUAlpha, alpha)

        drawFullScreenQuad()
    }

    private fun drawFullScreenQuad() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVertexBuffer)
        GLES30.glEnableVertexAttribArray(quadAPos)
        GLES30.glVertexAttribPointer(quadAPos, 2, GLES30.GL_FLOAT, false, 16, 0)

        GLES30.glEnableVertexAttribArray(quadATexCoord)
        GLES30.glVertexAttribPointer(quadATexCoord, 2, GLES30.GL_FLOAT, false, 16, 2 * Float.SIZE_BYTES)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(quadAPos)
        GLES30.glDisableVertexAttribArray(quadATexCoord)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun cacheShaderLocations() {
        mainAPos = GLES30.glGetAttribLocation(mainProgram, "a_pos")
        mainAColor = GLES30.glGetAttribLocation(mainProgram, "a_color")
        mainAUv = GLES30.glGetAttribLocation(mainProgram, "a_uv")
        mainUTexture = GLES30.glGetUniformLocation(mainProgram, "u_texture")
        mainUTime = GLES30.glGetUniformLocation(mainProgram, "u_time")
        mainUAudioResponse = GLES30.glGetUniformLocation(mainProgram, "u_audioResponse")
        mainUAspect = GLES30.glGetUniformLocation(mainProgram, "u_aspect")

        quadAPos = GLES30.glGetAttribLocation(quadProgram, "a_pos")
        quadATexCoord = GLES30.glGetAttribLocation(quadProgram, "a_texCoord")
        quadUTexture = GLES30.glGetUniformLocation(quadProgram, "u_texture")
        quadUAlpha = GLES30.glGetUniformLocation(quadProgram, "u_alpha")
    }

    private fun createFbo(width: Int, height: Int) {
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(fboTexture), 0)
        }

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        fboTexture = texIds[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, fboTexture, 0
        )

        // 【核心日志】：检查天玑 GPU 是否承认你创建的离屏 FBO
        val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Timber.tag(TAG)
                .e("FBO 创建失败，状态码为: $fboStatus (可能因为尺寸 $width x $height 导致 Mali 硬件拒绝)")
        } else {
            Timber.tag(TAG).d("FBO 成功创建并绑定完成: $width x $height")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun release() {
        synchronized(this) {
            for (state in meshStates) {
                deleteMesh(state)
            }
            meshStates.clear()
            if (fbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            }
            if (fboTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(fboTexture), 0)
            }
            if (mainProgram != 0) {
                GLES30.glDeleteProgram(mainProgram)
            }
            if (quadProgram != 0) {
                GLES30.glDeleteProgram(quadProgram)
            }
            if (quadVertexBuffer != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(quadVertexBuffer), 0)
                quadVertexBuffer = 0
            }
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) {
            Timber.tag(TAG).e("着色器组件编译失败，取消程序创建")
            return 0
        }

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            // 关键捕获：链接错误信息（如果网格属性和着色器in/out没对上，Mali会死在这里）
            Timber.tag(TAG).e("程序链接失败: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }

        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            // 关键捕获：修正 API 后的 Shader 编译期日志
            val shaderTypeStr = if (type == GLES30.GL_VERTEX_SHADER) "顶点" else "片元"
            Timber.tag(TAG).e("$shaderTypeStr 着色器编译失败: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
class MeshBackgroundView(context: Context) : GLSurfaceView(context) {

    private val renderer = MeshGradientRenderer()
    val hasRenderedAlbum get() = renderer.hasRenderedAlbum
    val renderedFrameVersion get() = renderer.renderedFrameVersion
    var onSurfaceReadyChanged: ((Boolean) -> Unit)? = null
    private var lastFlowSpeed = renderer.flowSpeed
    private var lastRenderScale = 0.75f
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var lastSubdivision = renderer.subdivision
    private var lastStaticMode = false
    private var lastPlaying = true
    private var renderingRequested = true
    private var hostStarted = true
    private var continuousRenderingNeeded = true
    private val legacyHolePaint = if (Build.VERSION.SDK_INT < 34) {
        Paint().apply { blendMode = BlendMode.DST_OUT }
    } else null

    init {
        // Readiness is a Surface lifecycle event, not a track-change or PixelCopy event.
        // Dispatch only the first album frame and context recreation to the UI thread.
        renderer.onSurfaceReadyChanged = { ready ->
            post { onSurfaceReadyChanged?.invoke(ready) }
        }
        renderer.onRenderDemandChanged = { needed ->
            post {
                continuousRenderingNeeded = needed
                applyRenderMode()
            }
        }
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        applyRenderMode()
    }

    fun setAlbum(bitmap: Bitmap) {
        queueEvent { renderer.setAlbum(bitmap) }
        requestRender()
    }

    // Android 13 ignores fractional SurfaceView alpha. This view has no foreground or
    // children: replace its opaque CLEAR hole with an alpha-modulated DST_OUT hole there.
    // It still exposes the same native Surface, respects the parent's sheet clip, and does
    // not introduce a bitmap proxy or an offscreen alpha layer. Android 14+ does this itself.
    override fun draw(canvas: Canvas) {
        if (legacyHolePaint != null) drawLegacySurfaceHole(canvas) else super.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (legacyHolePaint != null) drawLegacySurfaceHole(canvas) else super.dispatchDraw(canvas)
    }

    private fun drawLegacySurfaceHole(canvas: Canvas) {
        val paint = legacyHolePaint ?: return
        if (!hasRenderedAlbum || !holder.surface.isValid) return
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    fun setRenderingRequested(active: Boolean) {
        if (renderingRequested == active) return
        renderingRequested = active
        if (!hostStarted) return
        applyRenderMode()
        if (active) requestRender()
    }

    fun setHostStarted(started: Boolean) {
        if (hostStarted == started) return
        hostStarted = started
        if (started) {
            onResume()
            applyRenderMode()
            requestRender()
        } else {
            renderMode = RENDERMODE_WHEN_DIRTY
            onPause()
        }
    }

    fun updateSpectrum(value: PlaybackSpectrum) {
        renderer.spectrum = value
        // Active playback already renders continuously. No GL event or render request per sample.
    }

    fun setAudioStrength(value: Float) {
        if (renderer.audioStrength == value) return
        renderer.audioStrength = value
        if (renderingRequested && hostStarted) requestRender()
    }

    fun setFlowSpeed(speed: Float) {
        if (lastFlowSpeed == speed) return
        lastFlowSpeed = speed
        renderer.flowSpeed = speed
        requestRender()
    }

    fun setRenderScale(scale: Float) {
        if (lastRenderScale == scale) return
        lastRenderScale = scale
        updateSurfaceBufferSize(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSurfaceBufferSize(w, h)
    }

    private fun updateSurfaceBufferSize(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val width = maxOf(1, (viewWidth * lastRenderScale).toInt())
        val height = maxOf(1, (viewHeight * lastRenderScale).toInt())
        if (width == bufferWidth && height == bufferHeight) return
        bufferWidth = width
        bufferHeight = height
        holder.setFixedSize(width, height)
    }

    fun setSubdivision(level: Int) {
        if (lastSubdivision == level) return
        lastSubdivision = level
        renderer.subdivision = level
    }

    fun setStaticMode(enable: Boolean) {
        if (lastStaticMode == enable) return
        lastStaticMode = enable
        queueEvent { renderer.setStaticMode(enable) }
        requestRender()
    }

    fun setPlaying(playing: Boolean) {
        if (lastPlaying == playing) return
        lastPlaying = playing
        renderer.setPlaying(playing)
        requestRender()
    }

    private fun applyRenderMode() {
        if (!hostStarted) return
        renderMode = if (renderingRequested && continuousRenderingNeeded) {
            RENDERMODE_CONTINUOUSLY
        } else {
            RENDERMODE_WHEN_DIRTY
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        queueEvent { renderer.release() }
    }
}
