package com.ljyh.mei.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule

private const val GlassSearchTransitionMillis = 300

/**
 * iOS-style search control that transitions from the segmented-control track color to the
 * navigation glass used by top-bar buttons. The close button expands from the trailing edge,
 * which makes the input field yield its width instead of jumping between fixed layouts.
 */
@Composable
fun GlassSearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    placeholder: String,
    closeContentDescription: String,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    backdrop: Backdrop = LocalGlassBackdrop.current,
    style: GlassSurfaceStyle = GlassSurfaceStyle.Navigation,
    controlHeight: Dp = LocalGlassDimensions.current.controlHeight,
    searchIconSize: Dp = 22.dp,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val colors = LocalGlassColors.current
    val focusManager = LocalFocusManager.current
    val transitionProgress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(GlassSearchTransitionMillis, easing = FastOutSlowInEasing),
        label = "glassSearchMaterial",
    )

    Row(
        modifier = modifier.height(controlHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(controlHeight)
                .clickable(
                    interactionSource = null,
                    indication = null,
                ) {
                    onActiveChange(true)
                    focusRequester.requestFocus()
                },
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - transitionProgress }
                    .background(colors.segmentedControlBackground, Capsule()),
            )
            if (transitionProgress > 0f) {
                GlassSurface(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = transitionProgress },
                    backdrop = backdrop,
                    shape = Capsule(),
                    style = style,
                ) {}
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 11.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SfIcon(
                    systemName = "magnifyingglass",
                    contentDescription = null,
                    size = searchIconSize,
                    tint = colors.content,
                    weight = FontWeight.Medium,
                    modifier = Modifier.width(26.dp)
                )
                Spacer(Modifier.width(4.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused && !active) onActiveChange(true)
                        },
                    singleLine = true,
                    textStyle = IosTypography.body.copy(
                        color = colors.content,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query.text) }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.text.isEmpty()) {
                                androidx.compose.material3.Text(
                                    text = placeholder,
                                    style = IosTypography.body,
                                    color = colors.tertiaryContent,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = active,
            enter = expandHorizontally(
                animationSpec = tween(GlassSearchTransitionMillis, easing = FastOutSlowInEasing),
                expandFrom = Alignment.End,
            ) + slideInHorizontally(
                animationSpec = tween(GlassSearchTransitionMillis, easing = FastOutSlowInEasing),
                initialOffsetX = { it },
            ) + fadeIn(animationSpec = tween(GlassSearchTransitionMillis)),
            exit = shrinkHorizontally(
                animationSpec = tween(GlassSearchTransitionMillis, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.End,
            ) + slideOutHorizontally(
                animationSpec = tween(GlassSearchTransitionMillis, easing = FastOutSlowInEasing),
                targetOffsetX = { it },
            ) + fadeOut(animationSpec = tween(GlassSearchTransitionMillis)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(12.dp))
                GlassIconButton(
                    onClick = onClose,
                    modifier = Modifier.size(controlHeight),
                    backdrop = backdrop,
                    style = style,
                ) {
                    SfIcon(
                        systemName = "xmark",
                        contentDescription = closeContentDescription,
                        size = 20.dp,
                        tint = colors.content,
                        weight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    LaunchedEffect(active) {
        if (active) focusRequester.requestFocus() else focusManager.clearFocus()
    }
    BackHandler(enabled = active, onBack = onClose)
}
