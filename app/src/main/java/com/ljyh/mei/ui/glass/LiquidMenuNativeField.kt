package com.ljyh.mei.ui.glass

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** Native numerical fast path; local JVM tests retain the identical Kotlin reference. */
@Keep
internal object LiquidMenuNativeField {
    private val available = runCatching { System.loadLibrary("liquid_menu_sdf") }.isSuccess

    fun warmUp(): Boolean = available

    fun build(
        geometry: FloatArray,
        originX: Float,
        originY: Float,
        step: Float,
        width: Int,
        height: Int,
        weights: FloatArray,
        radius: Int,
        rgba: FloatArray,
    ): Boolean = available && buildNative(geometry, originX, originY, step, width, height, weights, radius, rgba)

    fun pack(rgba: FloatArray, width: Int, height: Int, rowBytes: Int, target: ByteBuffer): Boolean =
        available && target.isDirect && packNative(rgba, width, height, rowBytes, target)

    fun release() {
        if (available) releaseNative()
    }

    private external fun buildNative(
        geometry: FloatArray, originX: Float, originY: Float, step: Float,
        width: Int, height: Int, weights: FloatArray, radius: Int, rgba: FloatArray,
    ): Boolean

    private external fun packNative(
        rgba: FloatArray, width: Int, height: Int, rowBytes: Int, target: ByteBuffer,
    ): Boolean

    private external fun releaseNative()
}
