package com.ljyh.mei.ui.component.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.PlaylistCardSize
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.utils.largeImage

@Composable
fun PlaylistCard(
    id: String,
    title: String,
    coverImg: String,
    subTitle: List<String>? = null,
    extInfo: String? = null,
    imageSize: Boolean = true,
    cardSize: Dp = PlaylistCardSize,
    titleMaxLines: Int = 2,
    secondaryTitle: String? = null,
    onClick: () -> Unit
) {
    // 常用阴影样式，提取出来复用
    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.7f),
        offset = Offset(2f, 2f),
        blurRadius = 4f
    )

    Column(
        modifier = Modifier
            .width(cardSize)
            .clip(ContinuousRoundedRectangle(8.dp)) // 整个组件裁切，防止水波纹溢出
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
            )
    ) {
        Box(
            modifier = Modifier.size(cardSize)
                .homeArtworkBorder(ContinuousRoundedRectangle(8.dp))
        ) {
            HomeCoverImage(
                model = coverImg.takeIf(String::isNotBlank)?.let { if (imageSize) it.largeImage() else it },
                modifier = Modifier
                    .matchParentSize()
                    .clip(ContinuousRoundedRectangle(8.dp)),
            )

            // 底部左侧副标题 (如果有)
            if (subTitle != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                ) {
                    subTitle.forEach { t ->
                        Text(
                            text = t,
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            style = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified, shadow = textShadow)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 标题
        Text(
            text = title,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (extInfo != null && secondaryTitle == null) 2 else titleMaxLines,
            minLines = if (extInfo != null && secondaryTitle == null) 2 else 1,
            fontSize = 13.sp, // 调整字体大小，更精致
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )
        if (secondaryTitle != null) {
            Text(
                text = secondaryTitle,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (extInfo != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Headset,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = extInfo,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun Modifier.homeArtworkBorder(shape: Shape): Modifier = border(
    width = 0.5.dp,
    color = Color(0xFF8E8E93).copy(alpha = 0.35f),
    shape = shape,
)

/** Keeps the cover's geometry and neutral placeholder stable during loading and failures. */
@Composable
internal fun HomeCoverImage(
    model: Any?,
    modifier: Modifier = Modifier,
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    var hasImage by remember(model) { mutableStateOf(false) }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!hasImage) {
            SfIcon(
                symbol = SfSymbol.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                size = 32.dp,
            )
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            onLoading = { state ->
                hasImage = false
                onLoading?.invoke(state)
            },
            onSuccess = { state ->
                hasImage = true
                onSuccess?.invoke(state)
            },
            onError = { state ->
                hasImage = false
                onError?.invoke(state)
            },
        )
    }
}
