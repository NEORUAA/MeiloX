package com.ljyh.mei.ui.component.search

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.TextFieldValue
import com.kyant.shapes.Capsule
import io.github.neoruaa.glasmio.core.LocalGlassDimensions
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar

/** Keep glass edge lighting at rest while retaining the native search editor and focus behavior. */
@Composable
fun MeiloXSearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    placeholder: String,
    closeContentDescription: String,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
) {
    val height = LocalGlassDimensions.current.controlHeight
    val restingGlass by animateFloatAsState(
        targetValue = if (active) 0f else 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "restingSearchGlass",
    )
    Box(modifier) {
        if (restingGlass > 0f) GlassSurface(
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = restingGlass },
            shape = Capsule(),
            style = GlassSurfaceStyle.Navigation,
        ) {}
        GlasmioSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            active = active,
            onActiveChange = onActiveChange,
            onClose = onClose,
            placeholder = placeholder,
            closeContentDescription = closeContentDescription,
            onSearch = onSearch,
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}
