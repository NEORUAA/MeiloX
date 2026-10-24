package com.ljyh.mei.ui.local

import androidx.compose.runtime.staticCompositionLocalOf

/** Use the app surface until the shared page-background preference has loaded. */
val LocalColorfulPageBackgrounds = staticCompositionLocalOf { false }

/** Shares a route's appearance with chrome until its owning page is disposed. */
val LocalPageAppearance = staticCompositionLocalOf<(String, Any, Boolean?) -> Unit> {
    { _, _, _ -> }
}
