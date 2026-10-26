package com.ljyh.mei.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.icons.SfIcon


val GridMenuItemHeight = 96.dp

@Composable
fun GridMenu(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier,
        contentPadding = contentPadding,
        content = content
    )
}


fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    tint: @Composable () -> Color = { LocalContentColor.current },
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = GridMenuItem(
    modifier = modifier,
    icon = {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint(),
        )
    },
    title = title,
    enabled = enabled,
    onClick = onClick
)

fun LazyGridScope.SfGridMenuItem(
    modifier: Modifier = Modifier,
    systemName: String,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = GridMenuItem(
    modifier = modifier,
    icon = {
        SfIcon(
            systemName = systemName,
            contentDescription = null,
            size = 26.dp,
        )
    },
    title = title,
    enabled = enabled,
    onClick = onClick,
)

private fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    icon: @Composable BoxScope.() -> Unit,
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    item {
        GlasmioCard(
            modifier = modifier
                .height(GridMenuItemHeight)
                .alpha(if (enabled) 1f else 0.5f),
            onClick = onClick.takeIf { enabled },
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                    content = icon,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
