package com.ljyh.mei.ui.component.player.component.mesh

import android.opengl.EGL14
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAudioResponseTest {
    @Test
    fun maximumResponseKeepsNeutralArtworkOpaqueAndColoredArtworkMoves() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        try {
            fun compile(type: Int, source: String): Int {
                val shader = GLES30.glCreateShader(type)
                GLES30.glShaderSource(shader, source)
                GLES30.glCompileShader(shader)
                val status = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
                return shader
            }
            val vertex = compile(GLES30.GL_VERTEX_SHADER, ShaderSource.MESH_VERTEX_SHADER)
            val fragment = compile(GLES30.GL_FRAGMENT_SHADER, ShaderSource.MESH_FRAGMENT_SHADER)
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(program) }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            GLES30.glUseProgram(program)
            val vertices = ByteBuffer.allocateDirect(28 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(floatArrayOf(-1f,-1f,1f,1f,1f,0f,0f, 1f,-1f,1f,1f,1f,1f,0f,
                -1f,1f,1f,1f,1f,0f,1f, 1f,1f,1f,1f,1f,1f,1f)).flip()
            val buffers = IntArray(1)
            GLES30.glGenBuffers(1, buffers, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 28 * 4, vertices, GLES30.GL_STATIC_DRAW)
            for ((name, size, offset) in listOf(Triple("a_pos", 2, 0), Triple("a_color", 3, 8), Triple("a_uv", 2, 20))) {
                val location = GLES30.glGetAttribLocation(program, name)
                GLES30.glEnableVertexAttribArray(location)
                GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 28, offset)
            }
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_aspect"), 1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_time"), 0f)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "u_texture"), 0)
            val texture = IntArray(1)
            GLES30.glGenTextures(1, texture, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_MIRRORED_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_MIRRORED_REPEAT)
            val artwork = ByteBuffer.allocateDirect(64 * 64 * 4)
            fun upload(colored: Boolean) {
                artwork.clear()
                repeat(64 * 64) { i ->
                    artwork.put(if (colored) ((i % 64) * 4).toByte() else 128.toByte())
                    artwork.put(if (colored) ((i / 64) * 4).toByte() else 128.toByte())
                    artwork.put(128.toByte()).put(255.toByte())
                }
                artwork.flip()
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 64, 64, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, artwork)
            }
            fun render(scale: Float, contrast: Float, saturation: Float): ByteBuffer {
                GLES30.glViewport(0, 0, 64, 64)
                GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "u_audioResponse"), scale, contrast, saturation)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
                GLES30.glReadPixels(0, 0, 64, 64, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                return pixels
            }
            upload(false)
            val neutral = render(1f, 1f, 1f)
            val peak = render(1.66f, 1.076f, 1.166f)
            for (i in 0 until neutral.capacity()) {
                assertTrue("Uniform neutral artwork must not flash or lose opacity at peak: byte $i",
                    abs((neutral[i].toInt() and 255) - (peak[i].toInt() and 255)) <= 1)
                if (i % 4 == 3) assertTrue((peak[i].toInt() and 255) == 255)
            }
            upload(true)
            val rest = render(1f, 1f, 1f)
            val expanded = render(1.66f, 1f, 1f)
            val changed = (0 until rest.capacity()).count { abs((rest[it].toInt() and 255) - (expanded[it].toInt() and 255)) > 5 }
            assertTrue("Audio must move the artwork spatially", changed > 1_000)
            GLES30.glDeleteTextures(1, texture, 0)
            GLES30.glDeleteBuffers(1, buffers, 0)
            GLES30.glDeleteProgram(program)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
