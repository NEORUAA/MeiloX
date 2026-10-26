package com.ljyh.mei.ui.screen.main.library.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import com.kyant.capsule.ContinuousRoundedRectangle

@Composable
fun LibraryGridItem(
    title: String,
    subtitle: String,
    cover: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable { onClick() }
    ) {
        Card(
            shape = ContinuousRoundedRectangle(10.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.aspectRatio(1f)
        ) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = title,
            style = IosTypography.body,
            color = LocalGlassColors.current.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = IosTypography.caption,
            color = LocalGlassColors.current.secondaryContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
