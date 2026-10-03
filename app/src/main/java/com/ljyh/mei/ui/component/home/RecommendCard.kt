package com.ljyh.mei.ui.component.home

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.RecommendCardHeight
import com.ljyh.mei.constants.RecommendCardWidth
import com.ljyh.mei.ui.screen.main.home.HomeViewModel
import kotlinx.coroutines.CancellationException
import com.ljyh.mei.utils.largeImage

@Composable
fun RecommendCard(
    cover: String,
    title: String? = null,
    extInfo: CardExtInfo,
    showPlay: Boolean = false,
    cardWidth: Dp = RecommendCardWidth,
    cardHeight: Dp = RecommendCardHeight,
    viewModel: HomeViewModel,
    onClick: () -> Unit = {}
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
    val iconModel = extInfo.icon?.takeIf { it.isNotBlank() }
    val currentCoverModel = rememberUpdatedState(coverModel)
    val currentIconModel = rememberUpdatedState(iconModel)
    val iconTerminal = remember(iconModel) { mutableStateOf(iconModel == null) }
    val iconSucceeded = remember(iconModel) { mutableStateOf(false) }
    val upperHalfColor = remember(coverModel) {
        mutableStateOf(if (coverModel == null) Color.DarkGray else null)
    }
    var extractedColor by remember(cover) {
        mutableStateOf(viewModel.getCachedColor(cover) ?: Color.DarkGray)
    }

    LaunchedEffect(cover) {
        if (cover.isBlank()) return@LaunchedEffect
        try {
            extractedColor = viewModel.getOrExtractColor(cover)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Keep the cached or fallback color when extraction fails.
        }
    }

    val baseColor by animateColorAsState(extractedColor, label = "recommendCardColor")
    val imageForegroundColor = remember(upperHalfColor.value) {
        imageForeground(upperHalfColor.value ?: Color.DarkGray)
    }
    val footerForegroundColor = remember(baseColor) {
        footerForeground(baseColor)
    }
    Box(
        modifier = Modifier
            .width(cardWidth)
            .clip(ContinuousRoundedRectangle(8.dp))
            .clickable { onClick() }
    ) {
        Column {
            // Cover image and the top metadata row.
            Box(
                modifier = Modifier
                    .size(cardWidth, cardHeight)
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverRequest,
                        modifier = Modifier.matchParentSize(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onLoading = {
                            if (currentCoverModel.value == coverModel) {
                                upperHalfColor.value = null
                            }
                        },
                        onSuccess = { state ->
                            if (currentCoverModel.value == coverModel) {
                                upperHalfColor.value = runCatching {
                                    sampleUpperHalfColor(state.result.image.toBitmap())
                                }.getOrElse { extractedColor }
                            }
                        },
                        onError = {
                            if (currentCoverModel.value == coverModel) {
                                upperHalfColor.value = Color.DarkGray
                            }
                        },
                    )
                }

                // Top gradient overlay for text readability.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    baseColor.copy(alpha = 0.6f),
                                    baseColor.copy(alpha = 0.1f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = 200f
                            )
                        )
                )

                CompositionLocalProvider(LocalContentColor provides imageForegroundColor) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (iconModel != null && (!iconTerminal.value || iconSucceeded.value)) {
                            AsyncImage(
                                model = iconModel,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(ContinuousRoundedRectangle(4.dp)),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                colorFilter = ColorFilter.tint(imageForegroundColor),
                                onLoading = {
                                    if (currentIconModel.value == iconModel) {
                                        iconTerminal.value = false
                                        iconSucceeded.value = false
                                    }
                                },
                                onSuccess = {
                                    if (currentIconModel.value == iconModel) {
                                        iconTerminal.value = true
                                        iconSucceeded.value = true
                                    }
                                },
                                onError = {
                                    if (currentIconModel.value == iconModel) {
                                        iconTerminal.value = true
                                        iconSucceeded.value = false
                                    }
                                },
                            )
                            if (iconSucceeded.value) {
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Text(
                            text = extInfo.text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (showPlay) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = "Play",
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(28.dp)
                        )
                    }
                }
            }

            // Bottom title area.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                baseColor.copy(alpha = 0.9f),
                                baseColor
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides footerForegroundColor) {
                    Text(
                        text = title ?: "",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

data class CardExtInfo(val icon: String? = null, val text: String)

private fun imageForeground(baseColor: Color): Color {
    val opaqueBaseColor = baseColor.copy(alpha = 1f).toArgb()
    val luminance = ColorUtils.calculateLuminance(opaqueBaseColor)
    return if (luminance < 0.5) Color.White else Color.Black
}

private fun footerForeground(baseColor: Color): Color {
    val opaqueBaseColor = baseColor.copy(alpha = 1f).toArgb()
    val luminance = ColorUtils.calculateLuminance(opaqueBaseColor)
    return if (luminance < 0.5) Color.White else Color.Black
}

private fun sampleUpperHalfColor(bitmap: Bitmap): Color {
    if (bitmap.width == 0 || bitmap.height == 0) return Color.DarkGray

    val sampleColumns = 16
    val sampleRows = 8
    val upperHalfHeight = (bitmap.height / 2).coerceAtLeast(1)
    var red = 0f
    var green = 0f
    var blue = 0f
    var weight = 0f

    for (row in 0 until sampleRows) {
        val y = ((row + 0.5f) * upperHalfHeight / sampleRows)
            .toInt()
            .coerceIn(0, bitmap.height - 1)
        for (column in 0 until sampleColumns) {
            val x = ((column + 0.5f) * bitmap.width / sampleColumns)
                .toInt()
                .coerceIn(0, bitmap.width - 1)
            val pixel = bitmap.getPixel(x, y)
            val alpha = AndroidColor.alpha(pixel) / 255f
            red += AndroidColor.red(pixel) * alpha
            green += AndroidColor.green(pixel) * alpha
            blue += AndroidColor.blue(pixel) * alpha
            weight += alpha
        }
    }

    if (weight <= 0f) return Color.DarkGray
    return Color(
        AndroidColor.rgb(
            (red / weight).toInt().coerceIn(0, 255),
            (green / weight).toInt().coerceIn(0, 255),
            (blue / weight).toInt().coerceIn(0, 255),
        )
    )
}
