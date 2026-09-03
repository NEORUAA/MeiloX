package com.ljyh.mei.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported
import com.ljyh.mei.R

private val HorizontalProgressiveBlurShader = progressiveBlurPassShader(isVertical = false)
private val VerticalProgressiveBlurShader = progressiveBlurPassShader(isVertical = true)

private const val TopBarTintShader = """
uniform shader content;
uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;

half4 main(float2 coord) {
    float mask = smoothstep(size.y, size.y * 0.42, coord.y);
    return mix(content.eval(coord), tint, tintIntensity * mask);
}
"""

/**
 * AndroidX progressive blur uses two separable Gaussian passes and evaluates the blur radius at
 * every pixel. This local copy keeps the behavior available until the API reaches our Compose BOM.
 *
 * Source: https://android.googlesource.com/platform/frameworks/support/+/7e1430f6c57df22b6ceeaa66ff4e18b53a67edd9
 */
private fun progressiveBlurPassShader(isVertical: Boolean): String {
    val pairedOffset =
        if (isVertical) "vec2(0.0, i + weightH / weight)"
        else "vec2(i + weightH / weight, 0.0)"
    val oddOffset = if (isVertical) "vec2(0.0, r)" else "vec2(r, 0.0)"
    val boundsCheck =
        if (isVertical) {
            "return step(bounds.y, sampleCoord.y) * (1.0 - step(bounds.w, sampleCoord.y));"
        } else {
            "return step(bounds.x, sampleCoord.x) * (1.0 - step(bounds.z, sampleCoord.x));"
        }

    return """
        uniform shader content;
        uniform float blurRadius;
        uniform float4 crop;
        uniform float unbounded;
        uniform float startIntensity;
        uniform float endIntensity;
        uniform float2 startPoint;
        uniform float2 endPoint;
        const float maxRadius = 150.0;

        float gaussian(float x, float sigma) {
            return exp(-(x * x) / (2.0 * sigma * sigma));
        }

        float inBoundsOnMovedAxis(vec2 sampleCoord, float4 bounds) {
            $boundsCheck
        }

        vec4 blur(vec2 coord, float radius) {
            float r = floor(radius);
            if (r < 1.0) { return content.eval(coord); }

            float sigma = max(radius / 2.0, 1.0);
            float weightSum = 1.0;
            vec4 result = content.eval(coord);

            for (float i = 1.0; i < maxRadius; i += 2.0) {
                if (i >= r) { break; }

                float weightL = gaussian(i, sigma);
                float weightH = gaussian(i + 1.0, sigma);
                float weight = weightL + weightH;
                vec2 offset = $pairedOffset;

                vec2 newCoord1 = coord - offset;
                float mask1 = inBoundsOnMovedAxis(newCoord1, crop);
                weightSum += weight * max(mask1, unbounded);
                if (mask1 > 0.0) {
                    result += weight * content.eval(newCoord1);
                }

                vec2 newCoord2 = coord + offset;
                float mask2 = inBoundsOnMovedAxis(newCoord2, crop);
                weightSum += weight * max(mask2, unbounded);
                if (mask2 > 0.0) {
                    result += weight * content.eval(newCoord2);
                }
            }

            float oddMask = mod(r, 2.0) * (1.0 - step(maxRadius, r));
            float oddWeight = gaussian(r, sigma) * oddMask;
            vec2 tailOffset = $oddOffset;

            vec2 oddCoord1 = coord - tailOffset;
            float oddBounds1 = inBoundsOnMovedAxis(oddCoord1, crop);
            weightSum += oddWeight * max(oddBounds1, unbounded);
            if (oddBounds1 > 0.0) {
                result += oddWeight * content.eval(oddCoord1);
            }

            vec2 oddCoord2 = coord + tailOffset;
            float oddBounds2 = inBoundsOnMovedAxis(oddCoord2, crop);
            weightSum += oddWeight * max(oddBounds2, unbounded);
            if (oddBounds2 > 0.0) {
                result += oddWeight * content.eval(oddCoord2);
            }

            return result / weightSum;
        }

        half4 main(float2 coord) {
            float2 pa = coord - startPoint;
            float2 ba = endPoint - startPoint;
            float fraction = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
            float intensity = mix(startIntensity, endIntensity, fraction);
            return half4(blur(coord, blurRadius * intensity));
        }
    """.trimIndent()
}

private fun BackdropEffectScope.topBarProgressiveBlur(maxRadius: Float) {
    if (maxRadius <= 0f) return
    if (!isRuntimeShaderSupported()) {
        blur(maxRadius)
        return
    }

    val resolvedRadius = maxRadius.coerceAtMost(150f)
    val gradientStartY = size.height * 0.42f

    fun applyPass(key: String, shader: String) {
        runtimeShaderEffect(key, shader, "content") {
            setFloatUniform("blurRadius", resolvedRadius)
            setFloatUniform("crop", 0f, 0f, size.width, size.height)
            setFloatUniform("unbounded", 0f)
            setFloatUniform("startIntensity", 1f)
            setFloatUniform("endIntensity", 0f)
            setFloatUniform("startPoint", 0f, gradientStartY)
            setFloatUniform("endPoint", 0f, size.height)
        }
    }

    applyPass("IosTopBarProgressiveBlurHorizontal", HorizontalProgressiveBlurShader)
    applyPass("IosTopBarProgressiveBlurVertical", VerticalProgressiveBlurShader)
}

@Composable
fun rememberIosGridCollapseProgress(
    gridState: LazyGridState,
    collapseDistance: Dp = 56.dp,
): Float {
    val collapseDistancePx = with(LocalDensity.current) { collapseDistance.toPx() }
    val progress by remember(gridState, collapseDistancePx) {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (gridState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
            }
        }
    }
    return progress
}

/**
 * Fixed iOS navigation bar over a progressively blurred scroll source.
 *
 * The scrolling layer exports its rendered result to a dedicated backdrop. The toolbar is kept
 * outside that layer, so the backdrop graph cannot recursively sample the toolbar itself.
 */
@Composable
fun IosPinnedListPage(
    title: String,
    bottomPadding: Dp,
    horizontalContentPadding: Dp = 16.dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    showsLargeTitle: Boolean = true,
    largeTitleHorizontalPadding: Dp = 4.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backgroundColor: Color? = null,
    content: LazyListScope.() -> Unit,
) {
    val collapseDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
    val collapseProgress by remember(listState, showsLargeTitle) {
        derivedStateOf {
            if (!showsLargeTitle) {
                1f
            } else if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
            }
        }
    }
    IosPinnedPage(
        title = title,
        subtitle = subtitle,
        bottomPadding = bottomPadding,
        modifier = modifier,
        onNavigateBack = onNavigateBack,
        actions = actions,
        collapseProgress = collapseProgress,
        backgroundColor = backgroundColor,
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalContentPadding,
                top = contentPadding.calculateTopPadding(),
                end = horizontalContentPadding,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = verticalArrangement,
        ) {
            if (showsLargeTitle) {
                item(key = "ios-large-title:$title") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = 1f - collapseProgress
                                val scale = 1f - 0.04f * collapseProgress
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            }
                            .blur(6.dp * collapseProgress)
                            .offset(y = (-10).dp)
                            .padding(horizontal = largeTitleHorizontalPadding, vertical = 6.dp),
                    ) {
                        Text(
                            text = title,
                            style = IosTypography.largeTitle,
                            fontWeight = FontWeight.Bold,
                            color = LocalGlassColors.current.content,
                        )
                        subtitle?.let {
                            Text(
                                text = it,
                                style = IosTypography.subheadline,
                                color = LocalGlassColors.current.secondaryContent,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
            content()
        }
    }
}

/**
 * Fixed iOS navigation bar for pages whose body is not a single [LazyColumn].
 *
 * [content] is the only exported sample layer. All glass controls inside it continue to read
 * [LocalGlassBackdrop], while the toolbar reads this dedicated page layer, preventing feedback.
 */
@Composable
fun IosPinnedPage(
    title: String,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    collapseProgress: Float = 1f,
    backgroundColor: Color? = null,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val pageBackdrop = rememberLayerBackdrop()
    val parentBackdrop = LocalGlassBackdrop.current
    val topBarBackdrop = rememberCombinedBackdrop(parentBackdrop, pageBackdrop)
    val colors = LocalGlassColors.current
    val pageBackground = backgroundColor ?: colors.groupedBackground
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val toolbarHeight = statusBarHeight + 62.dp
    val contentPadding = PaddingValues(
        start = 16.dp,
        top = toolbarHeight + 10.dp,
        end = 16.dp,
        bottom = bottomPadding + 24.dp,
    )

    CompositionLocalProvider(LocalContentColor provides colors.content) {
        Box(modifier.fillMaxSize()) {
            // Keep the background inside the recorded draw chain so transparent content regions
            // sample the real page surface instead of an empty backdrop.
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(pageBackdrop)
                    .background(pageBackground),
            ) {
                content(contentPadding)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(toolbarHeight + 34.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = collapseProgress.coerceIn(0f, 1f)
                    }
                    .drawPlainBackdrop(
                        backdrop = pageBackdrop,
                        shape = { RectangleShape },
                        effects = {
                            topBarProgressiveBlur(10.dp.toPx())
                            runtimeShaderEffect("IosTopBarTint", TopBarTintShader, "content") {
                                setFloatUniform("size", size.width, size.height)
                                setColorUniform("tint", pageBackground)
                                setFloatUniform("tintIntensity", 0.78f)
                            }
                        },
                    ),
            )
            CompositionLocalProvider(LocalGlassBackdrop provides topBarBackdrop) {
                IosTopToolbar(
                    title = title,
                    subtitle = subtitle,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter),
                    collapseProgress = collapseProgress,
                    navigation = onNavigateBack?.let { navigateBack ->
                        {
                            GlassIconButton(navigateBack) {
                                SfIcon(
                                    SfSymbol.ChevronBack,
                                    stringResource(R.string.navigation_back),
                                    mirrored = true,
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            }
        }
    }
}
