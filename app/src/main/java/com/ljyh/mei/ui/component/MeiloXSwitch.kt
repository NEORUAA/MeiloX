package com.ljyh.mei.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.ui.GlasmioSwitch

/** Keep app switches aligned with the active accent while retaining explicit tint overrides. */
@Composable
fun MeiloXSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    onTintColor: Color = LocalGlassColors.current.accent,
) {
    GlasmioSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop,
        onTintColor = onTintColor,
    )
}
