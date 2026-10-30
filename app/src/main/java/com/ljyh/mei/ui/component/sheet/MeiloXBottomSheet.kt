package com.ljyh.mei.ui.component.sheet

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioSheetState
import io.github.neoruaa.glasmio.ui.rememberGlasmioSheetState

// Nested modals retain their original presenter instead of inheriting a menu-only source.
private val LocalMeiloXSheetPresenterBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Keep modal controls on the sheet's neutral material instead of the presenting album/page. */
@Composable
fun MeiloXBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    sheetState: GlasmioSheetState = rememberGlasmioSheetState(skipPartiallyExpanded),
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
    visible: Boolean = true,
    fitContent: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    title: String = "Sheet",
    topBar: (@Composable () -> Unit)? = null,
    topBarBlurProgress: Float = 0f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val presenterBackdrop = LocalMeiloXSheetPresenterBackdrop.current ?: LocalBlurBackdrop.current
    CompositionLocalProvider(LocalMeiloXSheetPresenterBackdrop provides presenterBackdrop) {
        GlasmioBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            skipPartiallyExpanded = skipPartiallyExpanded,
            backdrop = presenterBackdrop,
            sheetState = sheetState,
            contentWindowInsets = contentWindowInsets,
            visible = visible,
            fitContent = fitContent,
            dismissOnClickOutside = dismissOnClickOutside,
            dismissOnBackPress = dismissOnBackPress,
            title = title,
            topBar = topBar?.let { toolbar ->
                {
                    val menuBackdrop = rememberSheetControlBackdrop()
                    // The toolbar keeps Glasmio's body capture. Only its popup menus use this source.
                    CompositionLocalProvider(LocalBlurBackdrop provides menuBackdrop) { toolbar() }
                }
            },
            topBarBlurProgress = topBarBlurProgress,
        ) {
            val controlBackdrop = rememberSheetControlBackdrop()
            CompositionLocalProvider(
                LocalGlassBackdrop provides controlBackdrop,
                LocalBlurBackdrop provides controlBackdrop,
            ) { content() }
        }
    }
}

@Composable
private fun rememberSheetControlBackdrop(): Backdrop {
    val surfaceColor = LocalGlassColors.current.groupedBackground.copy(alpha = 1f)
    return rememberCanvasBackdrop { drawRect(surfaceColor) }
}
