package com.ljyh.mei.ui.component.home

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.RecommendCardCornerRadius
import com.ljyh.mei.constants.RecommendCardHeight
import com.ljyh.mei.constants.RecommendCardWidth
import io.github.neoruaa.glasmio.glass.progressiveBackdropBlur
import com.ljyh.mei.utils.largeImage
import kotlin.math.max

@Composable
fun RecommendCard(
    cover: String,
    title: String? = null,
    extInfo: CardExtInfo,
    showPlay: Boolean = false,
    isLoading: Boolean = false,
    cardWidth: Dp = RecommendCardWidth,
    cardHeight: Dp = RecommendCardHeight,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val coverModel = cover.takeIf { it.isNotBlank() }?.largeImage()
    val coverRequest = remember(context, coverModel) {
        coverModel?.let {
            ImageRequest.Builder(context)
                .data(it)
                .allowHardware(false)
                .build()
        }
    }
    val currentCoverModel = rememberUpdatedState(coverModel)
    var loadedBitmap by remember(coverModel) { mutableStateOf<Bitmap?>(null) }
    val clearArtworkHeight = cardWidth
    val reflectionHeight = cardHeight - clearArtworkHeight
    val reflectionLayerHeight = cardHeight / 3

    Box(
        modifier = Modifier
            .size(cardWidth, cardHeight)
            .clip(ContinuousRoundedRectangle(RecommendCardCornerRadius))
            .homeArtworkBorder(ContinuousRoundedRectangle(RecommendCardCornerRadius))
            .background(Color.DarkGray)
            .clickable(enabled = !isLoading, onClick = onClick),
    ) {
        HomeCoverImage(
            model = coverRequest,
            modifier = Modifier.size(cardWidth, clearArtworkHeight),
            onLoading = {
                if (currentCoverModel.value == coverModel) loadedBitmap = null
            },
            onSuccess = { state ->
                if (currentCoverModel.value == coverModel) {
                    loadedBitmap = state.result.image.toBitmap()
                }
            },
            onError = {
                if (currentCoverModel.value == coverModel) loadedBitmap = null
            },
        )
        loadedBitmap?.let { bitmap ->
            RecommendationArtworkExtension(
                bitmap,
                clearArtworkHeight,
                reflectionHeight,
                Modifier.align(Alignment.BottomStart).size(cardWidth, reflectionLayerHeight),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(reflectionLayerHeight)
                .drawWithCache {
                    val layerTop = cardHeight.roundToPx() - size.height
                    val scrim = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                        startY = cardHeight.toPx() * 2f / 3f - layerTop,
                        endY = size.height,
                    )
                    onDrawBehind { drawRect(scrim) }
                },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = if (isLoading || showPlay) 56.dp else 16.dp,
                    bottom = 16.dp,
                ),
        ) {
            RecommendationDescription(extInfo)
            Spacer(Modifier.height(2.dp))
            Text(
                text = title.orEmpty(),
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(28.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else if (showPlay) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(28.dp),
            )
        }
    }
}

@Composable
private fun RecommendationArtworkExtension(
    bitmap: Bitmap,
    clearArtworkHeight: Dp,
    reflectionHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val cardHeight = clearArtworkHeight + reflectionHeight
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
        }
    }
    val backdrop = rememberCanvasBackdrop {
        val clearHeight = clearArtworkHeight.roundToPx().toFloat()
        val cardHeightPx = cardHeight.roundToPx().toFloat()
        val layerTop = cardHeightPx - size.height
        val scale = max(size.width / bitmap.width, clearHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (size.width - width) / 2f
        val top = (clearHeight - height) / 2f
        val artworkBounds = RectF(left, top, left + width, top + height)
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val save = nativeCanvas.save()
            nativeCanvas.translate(0f, -layerTop)

            // The blur transition samples the same center-cropped cover as the visible artwork.
            val transitionSave = nativeCanvas.save()
            nativeCanvas.clipRect(0f, layerTop, size.width, clearHeight)
            nativeCanvas.drawBitmap(bitmap, null, artworkBounds, paint)
            nativeCanvas.restoreToCount(transitionSave)

            // Extend the visible cover's bottom row while preserving its horizontal crop.
            nativeCanvas.clipRect(0f, clearHeight, size.width, cardHeightPx)
            val edgeY = kotlin.math.floor((clearHeight - top) / scale - 0.5f)
                .toInt().coerceIn(0, bitmap.height - 1)
            nativeCanvas.drawBitmap(
                bitmap,
                Rect(0, edgeY, bitmap.width, edgeY + 1),
                RectF(left, clearHeight, left + width, cardHeightPx),
                paint,
            )
            nativeCanvas.restoreToCount(save)
        }
    }
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val layerTop = cardHeight.roundToPx() - size.height
                val mask = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = cardHeight.toPx() * 2f / 3f - layerTop,
                    endY = clearArtworkHeight.toPx() - layerTop,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    val layerTop = cardHeight.roundToPx() - size.height
                    progressiveBackdropBlur(
                        maxRadius = 100.dp.toPx(),
                        startYFraction = (cardHeight.toPx() * 2f / 3f - layerTop) / size.height,
                        endYFraction = 1f,
                        startIntensity = 0f,
                        endIntensity = 1f,
                    )
                },
            ),
    )
}

@Composable
private fun RecommendationDescription(extInfo: CardExtInfo) {
    val iconModel = extInfo.icon?.takeIf { it.isNotBlank() }
    val currentIconModel = rememberUpdatedState(iconModel)
    var iconTerminal by remember(iconModel) { mutableStateOf(iconModel == null) }
    var iconSucceeded by remember(iconModel) { mutableStateOf(false) }
    val color = Color.White.copy(alpha = 0.8f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (iconModel != null && (!iconTerminal || iconSucceeded)) {
            AsyncImage(
                model = iconModel,
                modifier = Modifier.size(16.dp).clip(ContinuousRoundedRectangle(4.dp)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(color),
                onLoading = {
                    if (currentIconModel.value == iconModel) {
                        iconTerminal = false
                        iconSucceeded = false
                    }
                },
                onSuccess = {
                    if (currentIconModel.value == iconModel) {
                        iconTerminal = true
                        iconSucceeded = true
                    }
                },
                onError = {
                    if (currentIconModel.value == iconModel) {
                        iconTerminal = true
                        iconSucceeded = false
                    }
                },
            )
            if (iconSucceeded) Spacer(Modifier.width(4.dp))
        }
        Text(
            text = extInfo.text,
            color = color,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class CardExtInfo(val icon: String? = null, val text: String)
