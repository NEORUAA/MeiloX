package com.ljyh.mei.ui.component.item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors

/** Reserve the maximum caption height while keeping short titles beside their metadata. */
@Composable
internal fun FixedCardCaption(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val titleStyle = IosTypography.body.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    )
    val subtitleStyle = IosTypography.caption
    val measurer = rememberTextMeasurer()
    val heightPx = measurer.measure("\n", style = titleStyle).size.height +
        measurer.measure("", style = subtitleStyle).size.height
    Column(modifier.height(with(LocalDensity.current) { heightPx.toDp() })) {
        Text(
            text = title,
            style = titleStyle,
            color = LocalGlassColors.current.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = subtitleStyle,
            color = LocalGlassColors.current.secondaryContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
