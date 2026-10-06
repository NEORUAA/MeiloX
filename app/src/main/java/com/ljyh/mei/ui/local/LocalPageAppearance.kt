package com.ljyh.mei.ui.local

import androidx.compose.runtime.staticCompositionLocalOf

/** Shares a route's appearance with chrome until its owning page is disposed. */
val LocalPageAppearance = staticCompositionLocalOf<(String, Any, Boolean?) -> Unit> {
    { _, _, _ -> }
}
