package com.ljyh.mei.ui.component.playlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.LocalGlassColors

@Composable
internal fun songListDividerColor(): Color {
    val colors = LocalGlassColors.current
    return if (colors.isDark) colors.separator else Color(0xFF3C3C43).copy(alpha = 0.22f)
}
