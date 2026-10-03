package com.ljyh.mei.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Lets a detail page temporarily replace the player and navigation controls. */
class SelectionToolbarState {
    val content = mutableStateOf<(@Composable () -> Unit)?>(null)
}

val LocalSelectionToolbar = staticCompositionLocalOf { SelectionToolbarState() }
