package com.ljyh.mei.ui.screen.social

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OfficialMessagesAvatar(avatarUrl: String?) {
    val context = LocalContext.current
    val background by produceState<ImageBitmap?>(null, avatarUrl, context) {
        value = null
        if (avatarUrl.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(avatarUrl)
                .allowHardware(false)
                .size(64)
                .build()
            SingletonImageLoader.get(context).execute(request).image?.toBitmap()
                ?.let(::officialAvatarBackground)?.asImageBitmap()
        }
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(LocalGlassColors.current.elevatedBackground),
        contentAlignment = Alignment.Center,
    ) {
        background?.let { Image(it, null, modifier = Modifier.matchParentSize()) }
        SfIcon(
            "speaker.wave.2.fill", null, size = 24.dp,
            tint = Color.White,
        )
    }
}

private fun officialAvatarBackground(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    // NetEase loads the complete server-provided avatar. Keep its colored background
    // and interpolate underneath the neutral foreground before adding the SF Symbol.
    fun isBackground(pixel: Int): Boolean {
        val red = (pixel ushr 16) and 255
        val green = (pixel ushr 8) and 255
        val blue = pixel and 255
        return (pixel ushr 24) > 128 && maxOf(red, green, blue) - minOf(red, green, blue) > 60
    }
    for (y in 0 until height) {
        val row = y * width
        val first = (0 until width).firstOrNull { isBackground(pixels[row + it]) } ?: continue
        val last = (width - 1 downTo first).first { isBackground(pixels[row + it]) }
        var left = first
        while (left < last) {
            val right = (left + 1..last).first { it == last || isBackground(pixels[row + it]) }
            val start = pixels[row + left]
            val end = pixels[row + right]
            for (x in left + 1 until right) {
                val fraction = (x - left).toFloat() / (right - left)
                var color = 0xFF000000.toInt()
                for (shift in intArrayOf(16, 8, 0)) {
                    val a = (start ushr shift) and 255
                    val b = (end ushr shift) and 255
                    color = color or ((a + (b - a) * fraction).toInt() shl shift)
                }
                pixels[row + x] = color
            }
            left = right
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
