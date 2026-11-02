/*
 * Copyright 2026 Glasmio contributors
 * App-local player adaptation of Glasmio's pinned retained renderer:
 * https://github.com/NEORUAA/Glasmio/blob/864e958676e38c5416d573c6affeaf4b0279be98/glasmio-glass/src/main/java/io/github/neoruaa/glasmio/glass/GlassMorphRenderer.kt
 * Only this adapter accesses the pinned library's internal rendering helpers.
 * Material-reference changes stay in the app; dependency source is unchanged.
 *
 * Refraction math adapted from AndroidLiquidGlass, Copyright 2025 Kyant.
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ljyh.mei.ui.component.sheet

import io.github.neoruaa.glasmio.glass.*

import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.icons.*
import io.github.neoruaa.glasmio.glass.render.optics.NativeGlassSdfFunctions
import io.github.neoruaa.glasmio.glass.render.optics.NativeGlassRefractionFunctions
import io.github.neoruaa.glasmio.glass.render.optics.NativeGlassSpectralFunctions
import io.github.neoruaa.glasmio.glass.render.optics.CustomGlassChromaticFunctions
import io.github.neoruaa.glasmio.glass.render.BackdropEffectScopeImpl
import io.github.neoruaa.glasmio.glass.render.nativeGlassNeedsColorManagement
import io.github.neoruaa.glasmio.glass.render.nativeGlassDisplayHeadroom
import io.github.neoruaa.glasmio.glass.render.highlight.NativeCasdfHighlightMaskFunctions
import io.github.neoruaa.glasmio.glass.render.highlight.NativeGlassEdgeFunctions
import io.github.neoruaa.glasmio.glass.render.highlight.NativeVibrantColorMatrixBlender
import io.github.neoruaa.glasmio.glass.render.highlight.Highlight
import io.github.neoruaa.glasmio.glass.render.highlight.HighlightStyle

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.pow
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.PressHighlightRenderer

// Sample and soft material fields share this scale. Keep moving outlines, optical
// highlights and foreground at native resolution while reducing filter bandwidth.
private const val DefaultGlassRenderScale = 0.5f

@Composable
internal fun rememberPlayerSheetGlassMorphRenderer(
    backdrop: Backdrop,
    active: Boolean,
    blurRadius: Dp = 5.dp,
    renderScale: Float = DefaultGlassRenderScale,
    programs: LiquidMenuShaderPrograms? = null,
    materialPreset: NavigationGlassMaterial? = null,
    isDark: Boolean = false,
    darkFraction: Float = if (isDark) 1f else 0f,
): PlayerSheetGlassMorphRenderer {
    val density = LocalDensity.current
    val appearance = LocalGlassAppearance.current
    val view = LocalView.current
    val graphicsContext = LocalGraphicsContext.current
    val source = rememberGraphicsLayer()
    val refracted = rememberGraphicsLayer()
    val material = rememberGraphicsLayer()
    val atlasProducer = rememberGraphicsLayer()
    val atlasBacking = rememberGraphicsLayer()
    val materialDark = materialPreset != null && isDark
    val renderer = remember(backdrop, density, blurRadius, renderScale, source, refracted, material, appearance, programs, materialPreset, materialDark) {
        PlayerSheetGlassMorphRenderer(backdrop, density, blurRadius, renderScale, source, refracted, material, appearance, programs, materialPreset, materialDark)
    }
    val colorManagement = remember(view) { { nativeGlassNeedsColorManagement(view) } }
    val headroom = remember(view) { { nativeGlassDisplayHeadroom(view) } }
    renderer.presentationDarkFraction = darkFraction.coerceIn(0f, 1f)
    renderer.configureAtlasLayers(atlasProducer, atlasBacking, colorManagement, headroom, graphicsContext)
    DisposableEffect(renderer, active) {
        // Keep shaders/layers across endpoints, but never reuse an old capture when a new
        // transition starts after scrolling, changing pages, or updating the backdrop.
        if (active) renderer.invalidateRecording()
        onDispose { }
    }
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }
    return renderer
}

/** Fixed-size source/effect layers shared by the player sheet and home control morphs. */
internal class PlayerSheetGlassMorphRenderer(
    private val backdrop: Backdrop,
    private val density: Density,
    blurRadius: Dp,
    private val renderScale: Float,
    private val source: GraphicsLayer,
    private val refracted: GraphicsLayer,
    private val material: GraphicsLayer,
    private val appearance: GlassAppearance,
    programs: LiquidMenuShaderPrograms? = null,
    private val materialPreset: NavigationGlassMaterial? = null,
    private val materialDark: Boolean = false,
) {
    private val shader = if (appearance.usesRefraction) programs?.refraction ?: RuntimeShader(TransitionRefractionShader) else null
    private val highlight = programs?.highlight ?: RuntimeShader(TransitionHighlightShader)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = highlight }
    private val highlightBlender: NativeVibrantColorMatrixBlender? =
        if (Build.VERSION.SDK_INT >= 36) NativeVibrantColorMatrixBlender() else null
    private var sampledHighlight: Highlight? = null
    private var sampledHighlightAlpha = Float.NaN
    private val pressLight = PressHighlightRenderer()
    private val innerShadows = NavigationInnerShadowRenderer(renderScale = renderScale)
    private var nativeRecipe: NavigationMaterialRecipe? = null
    internal var presentationDarkFraction: Float = if (materialDark) 1f else 0f
    private var nativeRecipeDarkFraction = Float.NaN
    private var nativeRecipeDark = materialDark
    private var nativeRecipeTint: Color? = null
    private var nativeRecipeMultiplier = Float.NaN
    private val untintedRecipes = arrayOfNulls<NavigationMaterialRecipe>(10)
    private val dropShadow = NavigationDropShadowRenderer(
        renderScale = renderScale,
        shadowRadius = materialPreset?.shadowRadius ?: 15.dp,
        shadowOffsetY = materialPreset?.shadowOffsetY ?: 8.dp,
        shadowAlpha = materialPreset?.let { if (materialDark) it.darkShadowAlpha else it.lightShadowAlpha } ?: 0.02f,
        useNativeProfile = true,
        nativeMaterial = materialPreset,
    )
    private val shadowLiftShader = if (appearance.usesBackdrop) RuntimeShader(NavigationShadowLiftShader) else null
    private val shadowLiftEffect = shadowLiftShader?.let { RenderEffect.createRuntimeShaderEffect(it, "content") }
    private val reusableOutlinePath = Path()
    private var materialWidth = 0
    private var materialHeight = 0
    private var completionViewportSize = Size.Unspecified
    private var completionWidth = 0
    private var completionHeight = 0
    private var recordedViewport: Rect? = null
    private val sourceColorEffect = if (appearance.usesBackdrop) {
        RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }),
        )
    } else null
    private var sourceEffectRadius = blurRadius
    private var sourceUsesNativeMaterial = false
    private var sourceUsesNativeFilter = false
    private var sourceEffect = createSourceEffect(blurRadius)
    private val nativeFilterScope: BackdropEffectScopeImpl = MorphNativeFilterScope()
    private var atlasPasses: NativeGlassAtlasPasses? = null
    private var atlasProducer: GraphicsLayer? = null
    private var atlasBacking: GraphicsLayer? = null
    private var atlasInputActive = false
    private var atlasColorManagement: () -> Boolean = { false }
    private var atlasHeadroom: () -> Float = { 1f }

    internal fun configureAtlasLayers(producer: GraphicsLayer, backing: GraphicsLayer, colorManagement: () -> Boolean,
        headroom: () -> Float, graphicsContext: GraphicsContext) {
        if (atlasPasses == null) atlasPasses = NativeGlassAtlasPasses(graphicsContext)
        atlasProducer = producer
        atlasBacking = backing
        atlasColorManagement = colorManagement
        atlasHeadroom = headroom
    }

    private fun createSourceEffect(blurRadius: Dp): androidx.compose.ui.graphics.RenderEffect? {
        if (!appearance.usesBackdrop) return null
        if (sourceUsesNativeFilter) return null
        val color = sourceColorEffect.takeUnless { sourceUsesNativeMaterial }
        val blur = with(density) { blurRadius.toPx() } * renderScale
        return (if (blur > 0f) {
            if (color != null) RenderEffect.createBlurEffect(blur, blur, color, Shader.TileMode.CLAMP)
            else RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        } else color)?.asComposeRenderEffect()
    }

    internal fun materialEffect(isDark: Boolean, alpha: Float = 1f): RenderEffect =
        innerShadows.materialEffect(resolveRecipe(isDark, null, 1.25f), alpha)

    internal fun drawDropShadow(scope: DrawScope, shape: Shape, bounds: Rect, alpha: Float) {
        dropShadow.draw(scope, shape, bounds, alpha, nativeMorphShadowField(shape))
    }

    internal fun paintMaterial(scope: DrawScope, isDark: Boolean, material: NavigationGlassMaterial?, alpha: Float) {
        val index = (material?.ordinal ?: 4) * 2 + if (isDark) 1 else 0
        val recipe: NavigationMaterialRecipe = if (presentationDarkFraction > 0f && presentationDarkFraction < 1f)
            NavigationMaterialRecipe.resolve(presentationDarkFraction, material)
        else untintedRecipes[index] ?: NavigationMaterialRecipe.resolve(isDark, material).also { untintedRecipes[index] = it }
        if (!innerShadows.paintMaterial(scope, recipe, alpha)) {
            scope.drawNavigationGlassMaterial(Color.Transparent, isDark, material = material, alpha = alpha)
        }
    }

    private fun resolveRecipe(isDark: Boolean, tint: Color?, multiplier: Float): NavigationMaterialRecipe {
        if (nativeRecipe == null || nativeRecipeDark != isDark || nativeRecipeDarkFraction != presentationDarkFraction || nativeRecipeTint != tint || nativeRecipeMultiplier != multiplier) {
            nativeRecipe = NavigationMaterialRecipe.resolve(presentationDarkFraction, materialPreset, materialTint = tint, alphaMultiplier = multiplier)
            nativeRecipeDarkFraction = presentationDarkFraction
            nativeRecipeDark = isDark
            nativeRecipeTint = tint
            nativeRecipeMultiplier = multiplier
        }
        return requireNotNull(nativeRecipe)
    }

    /** Keep the recorded source and native shader objects while its material frost changes. */
    internal fun updateBlurRadius(blurRadius: Dp) {
        if (sourceEffectRadius == blurRadius) return
        sourceEffectRadius = blurRadius
        sourceEffect = createSourceEffect(blurRadius)
        source.renderEffect = sourceEffect
    }

    fun invalidateRecording() {
        recordedViewport = null
    }

    fun release() {
        shader?.let { bindLiquidMenuField(it, null) }
        bindLiquidMenuField(highlight, null)
        innerShadows.release()
        dropShadow.release()
        nativeFilterScope.reset()
        atlasPasses?.release()
        atlasPasses = null
    }

    fun draw(
        scope: DrawScope,
        coordinates: LayoutCoordinates,
        viewport: Rect,
        origin: Offset,
        bounds: Rect,
        radius: Float,
        shape: Shape,
        tint: Color,
        highlightAlpha: Float,
        tintMultiplier: Float = 1.25f,
        refractionHeight: Dp = Dp.Unspecified,
        refractionAmount: Dp = Dp.Unspecified,
        navigationOutline: Boolean = true,
        navigationMaterial: Boolean = false,
        dropShadowAlpha: Float = 0f,
        isDark: Boolean = false,
        sampleScale: Offset = Offset(1f, 1f),
        cornerRadii: Offset? = null,
        lensBounds: Rect? = null,
        secondaryLensBounds: Rect? = null,
        secondaryLensRadii: Offset? = null,
        lensSmoothing: Float = 0f,
        menuField: LiquidMenuFieldTexture? = null,
        pressHighlight: GlassPressHighlight? = null,
        pressHighlightBounds: Rect? = null,
        outlinePath: Path? = null,
        materialTint: Color? = null,
        materialPaint: (DrawScope.() -> Unit)? = null,
        shadowLiftProgress: Float = 1f,
        innerShadowProgress: Float = 1f,
        materialEffect: RenderEffect? = null,
        innerShadowBurnProgress: Float? = null,
        sourceFilterMaterial: NavigationGlassMaterial? = null,
        sourceFilterProgress: Float? = null,
        pressTint: Color? = null,
        pressClear: Boolean? = null,
        pressIsDark: Boolean? = null,
        additionalOptics: Boolean = false,
        sourceNativeMaterial: GlassMaterialSource? = null,
        materialReferenceSize: Size = Size.Unspecified,
        materialScale: Float = 1f,
    ) = with(scope) {
        if (bounds.isEmpty || viewport.isEmpty) return@with
        // A retained control changes its contour without becoming a panel material.
        val referenceSize = materialReferenceSize.takeIf {
            it.width.isFinite() && it.height.isFinite() && it.minDimension > 0f
        }
        val materialExtent = (referenceSize ?: bounds.size).minDimension / scope.density
        val opticalScale = if (referenceSize != null)
            materialScale.takeIf { it.isFinite() && it > 0f } ?: 1f else 1f
        val customOptics = refractionHeight.value.isFinite() || refractionAmount.value.isFinite()
        val nativeBackgroundFilter = navigationMaterial && appearance.usesBackdrop
        val usesNativeMaterial = navigationMaterial || materialEffect != null
        if (sourceUsesNativeMaterial != usesNativeMaterial || sourceUsesNativeFilter != nativeBackgroundFilter) {
            sourceUsesNativeMaterial = usesNativeMaterial
            sourceUsesNativeFilter = nativeBackgroundFilter
            sourceEffect = createSourceEffect(sourceEffectRadius)
            recordedViewport = null
        }
        val recipe: NavigationMaterialRecipe? = if (navigationMaterial && materialPaint == null && appearance.usesBackdrop) {
            val resolved: NavigationMaterialRecipe = resolveRecipe(isDark, materialTint, tintMultiplier)
            if (referenceSize != null) resolved.forMinimumDimension(materialExtent) else resolved
        } else null
        val completeNativeMaterial = nativeBackgroundFilter && !NativeGlassAtlasBackingCopy &&
            atlasProducer != null && atlasBacking != null &&
            (sourceNativeMaterial != null || (recipe != null && materialEffect == null))
        // A player contour needs no menu/source-material blend. Use the same single
        // atlas/material program as GlassSurface, without packing raw/filtered copies.
        val directNativeMaterial = completeNativeMaterial && referenceSize != null &&
            !customOptics && !additionalOptics && sourceNativeMaterial == null &&
            sourceFilterProgress == null && lensBounds == null && secondaryLensBounds == null &&
            cornerRadii == null && menuField == null && pressHighlight == null
        val recordedLayoutDirection = layoutDirection
        val path = outlinePath ?: reusableOutlinePath.apply {
            rewind()
            when (val outline = shape.createOutline(bounds.size, layoutDirection, this@with)) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
            translate(bounds.topLeft)
        }
        val sourceOffset = origin - viewport.topLeft
        val effectScale = Offset(sampleScale.x * renderScale, sampleScale.y * renderScale)
        val effectOffset = sourceOffset * renderScale
        val effectOrigin = Offset(
            (sourceOffset.x + bounds.left * sampleScale.x) * renderScale,
            (sourceOffset.y + bounds.top * sampleScale.y) * renderScale,
        )
        val contourField = referenceSize != null || cornerRadii != null || secondaryLensBounds != null || menuField != null
        fun fieldAdapter(scale: Offset, offset: Offset, fieldOrigin: Offset): NativeGlassFilterField {
            val primary = lensBounds ?: bounds
            val secondary = secondaryLensBounds ?: primary
            if (directNativeMaterial) {
                return NativeGlassFilterField(NativeDefaultFieldShader) { program ->
                    program.setFloatUniform("size", bounds.width * scale.x, bounds.height * scale.y)
                    val corner = radius * minOf(scale.x, scale.y)
                    program.setFloatUniform("cornerRadii", corner, corner, corner, corner)
                }
            }
            return NativeGlassFilterField(MorphGlassFieldShader) { program ->
                program.setFloatUniform("nativeFieldOrigin", fieldOrigin.x, fieldOrigin.y)
                program.setFloatUniform("center", offset.x + primary.center.x * scale.x, offset.y + primary.center.y * scale.y)
                program.setFloatUniform("halfSize", primary.width * scale.x * .5f, primary.height * scale.y * .5f)
                program.setFloatUniform("cornerRadii", (cornerRadii?.x ?: radius) * scale.x, (cornerRadii?.y ?: radius) * scale.y)
                program.setFloatUniform("secondaryEnabled", if (secondaryLensBounds == null) 0f else 1f)
                program.setFloatUniform("secondaryCenter", offset.x + secondary.center.x * scale.x, offset.y + secondary.center.y * scale.y)
                program.setFloatUniform("secondaryHalfSize", secondary.width * scale.x * .5f, secondary.height * scale.y * .5f)
                program.setFloatUniform("secondaryRadii", (secondaryLensRadii?.x ?: radius) * scale.x, (secondaryLensRadii?.y ?: radius) * scale.y)
                program.setFloatUniform("smoothing", lensSmoothing * minOf(scale.x, scale.y))
                bindLiquidMenuField(program, menuField, scale, offset)
            }
        }
        val filterField: NativeGlassFilterField? =
            if (contourField) fieldAdapter(effectScale, effectOffset, effectOrigin) else null
        val edgeField: NativeGlassFilterField? =
            if (contourField) fieldAdapter(Offset(1f, 1f), Offset.Zero, bounds.topLeft) else null
        if (dropShadowAlpha > 0f && !completeNativeMaterial) {
            drawDropShadow(this, shape, bounds, dropShadowAlpha)
        }
        if (appearance.usesBackdrop && recordedViewport != viewport) {
            val bufferSize = IntSize(
                ceil(viewport.width * renderScale).toInt(),
                ceil(viewport.height * renderScale).toInt(),
            )
            // The source display list contains the entire page. Its logical size alone
            // does not clip that list: bound both offscreen passes to the capture band.
            source.clip = true
            refracted.clip = true
            source.renderEffect = sourceEffect
            val root = coordinates.findRootCoordinates()
            source.record(
                density = this@PlayerSheetGlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = bufferSize,
            ) {
                withTransform({
                    scale(renderScale, renderScale, Offset.Zero)
                    translate(-viewport.left, -viewport.top)
                }) {
                    // CanvasBackdrop also reads DrawScope.size. Record in root coordinates
                    // so its base fill and every LayerBackdrop cover the same capture band.
                    val previousSize = drawContext.size
                    drawContext.size = Size(root.size.width.toFloat(), root.size.height.toFloat())
                    try {
                        if (backdrop is ViewportBackdrop) {
                            with(backdrop) { drawViewport(this@PlayerSheetGlassMorphRenderer.density, root, viewport) }
                        } else {
                            with(backdrop) { drawBackdrop(this@PlayerSheetGlassMorphRenderer.density, root, null) }
                        }
                    } finally {
                        drawContext.size = previousSize
                    }
                }
            }
            refracted.record(
                density = this@PlayerSheetGlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = bufferSize,
            ) { drawLayer(source) }
            recordedViewport = viewport
        }
        val clearFraction = if (sourceFilterProgress == null) {
            if (materialPreset == NavigationGlassMaterial.Clear) 1f else 0f
        } else if (sourceFilterMaterial == NavigationGlassMaterial.Clear) 1f - sourceFilterProgress else 0f
        val geometry: NavigationGlassGeometryProfile = if (sourceFilterProgress == null)
            navigationGlassGeometryProfile(materialExtent, materialPreset)
        else navigationGlassMorphProfile(materialExtent, sourceFilterMaterial, materialPreset, sourceFilterProgress)
        val directCompletion: NativeGlassCompletion? = if (directNativeMaterial) {
            NativeGlassCompletion(requireNotNull(recipe), isDark, clearFraction,
                darkFraction = presentationDarkFraction, edgeAlpha = innerShadowProgress,
                shadow = navigationNativeShadowProfile(materialExtent, materialPreset),
                shadowAlpha = dropShadowAlpha,
                outputSize = Size(bounds.width * sampleScale.x, bounds.height * sampleScale.y),
                outputDensity = scope.density * minOf(sampleScale.x, sampleScale.y) * opticalScale,
                textureScale = renderScale, highlightAlpha = highlightAlpha.coerceIn(0f, 1f))
        } else null
        val lensHeight = if (refractionHeight.value.isFinite()) with(scope) { refractionHeight.toPx() } else geometry.innerHeight * scope.density
        val lensAmount = if (refractionAmount.value.isFinite()) with(scope) { refractionAmount.toPx() } else geometry.innerAmount * scope.density
        val nativeBackgroundEffect = if (nativeBackgroundFilter) {
            val transformScale = minOf(sampleScale.x, sampleScale.y)
            (nativeFilterScope as MorphNativeFilterScope).configure(this, shape,
                Size(bounds.width * sampleScale.x * renderScale, bounds.height * sampleScale.y * renderScale),
                scope.density * transformScale * renderScale * opticalScale)
            nativeFilterScope.colorManagedNativeGlass = atlasColorManagement()
            nativeFilterScope.nativeGlassHeadroom = atlasHeadroom()
            nativeFilterScope.apply {
                nativeNavigationGlassFilter(
                    profile = geometry,
                    isDark = isDark,
                    darkFraction = presentationDarkFraction,
                    blurRadius = with(scope) { sourceEffectRadius.toPx() } * transformScale * renderScale * opticalScale,
                    refractionEnabled = appearance.usesRefraction && (!customOptics || additionalOptics),
                    clear = sourceFilterProgress == null && materialPreset == NavigationGlassMaterial.Clear,
                    sourceOrigin = effectOrigin,
                    sourceSize = Size(viewport.width * renderScale, viewport.height * renderScale),
                    useAtlas = DefaultNativeGlassAtlas && atlasProducer != null && (!NativeGlassAtlasBackingCopy || atlasBacking != null),
                    field = filterField,
                    fillFraction = if (sourceFilterProgress != null && sourceFilterMaterial == NavigationGlassMaterial.Clear)
                        sourceFilterProgress else 1f,
                    outerOpacityMultiplier = if (sourceFilterProgress != null && sourceFilterMaterial == NavigationGlassMaterial.Clear)
                        sourceFilterProgress else 1f,
                    completion = directCompletion,
                )
            }
            nativeFilterScope.renderEffect?.asAndroidRenderEffect()
        } else null
        val mipAtlas: NativeGlassMipAtlas? =
            if (nativeBackgroundFilter) nativeFilterScope.materializedMipAtlas else null
        val atlasEffects = nativeFilterScope.materializedInputEffects.takeIf { nativeBackgroundFilter }.orEmpty()
        val passes: NativeGlassAtlasPasses? = atlasPasses
        val backing = atlasBacking
        if ((mipAtlas != null || atlasEffects.isNotEmpty()) && passes != null && (!NativeGlassAtlasBackingCopy || backing != null)) {
            val atlasSize = requireNotNull(nativeFilterScope.effectTextureSize)
            val producer = if (mipAtlas != null) passes.recordPyramid(mipAtlas,
                record = { layer, extent, draw ->
                    layer.record(density = this@PlayerSheetGlassMorphRenderer.density,
                        layoutDirection = recordedLayoutDirection, size = extent, block = draw)
                }, sourceBlock = { drawLayer(source) }) else {
                var previous = source
                for (pass in passes.layers(atlasEffects)) {
                    val input = previous
                    pass.record(density = this@PlayerSheetGlassMorphRenderer.density,
                        layoutDirection = recordedLayoutDirection, size = atlasSize) { drawLayer(input) }
                    previous = pass
                }
                previous
            }
            if (NativeGlassAtlasBackingCopy && backing != null) {
                backing.clip = true
                backing.compositingStrategy = CompositingStrategy.Offscreen
                backing.renderEffect = null
                backing.record(density = this@PlayerSheetGlassMorphRenderer.density,
                    layoutDirection = recordedLayoutDirection, size = atlasSize) { drawLayer(producer) }
                refracted.record(density = this@PlayerSheetGlassMorphRenderer.density,
                    layoutDirection = recordedLayoutDirection, size = atlasSize) { drawLayer(backing) }
            } else {
                val consumerSize = if (directNativeMaterial) {
                    val outputOrigin = nativeFilterScope.materialOutputOrigin
                    val outputSize = requireNotNull(nativeFilterScope.materialOutputSize)
                    reserveCompletionSize(viewport.size, atlasSize, outputSize, outputOrigin, sampleScale)
                } else atlasSize
                refracted.record(density = this@PlayerSheetGlassMorphRenderer.density,
                    layoutDirection = recordedLayoutDirection, size = consumerSize) { drawLayer(producer) }
            }
            atlasInputActive = true
        } else if (atlasInputActive) {
            refracted.record(density = this@PlayerSheetGlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = IntSize(ceil(viewport.width * renderScale).toInt(), ceil(viewport.height * renderScale).toInt())) {
                drawLayer(source)
            }
            atlasInputActive = false
        }
        if (directNativeMaterial) {
            refracted.renderEffect = nativeBackgroundEffect?.asComposeRenderEffect()
            val outputOrigin = nativeFilterScope.materialOutputOrigin
            withTransform({
                translate(bounds.left - outputOrigin.x / sampleScale.x, bounds.top - outputOrigin.y / sampleScale.y)
                scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
            }) {
                clipRect(0f, 0f, refracted.size.width.toFloat(), refracted.size.height.toFloat()) {
                    drawLayer(refracted)
                }
            }
            return@with
        }
        val refractionEffect = if (shader != null && (!nativeBackgroundFilter || customOptics)) {
            val opticalBounds = lensBounds ?: bounds
            val sampleBounds = Rect(
                sourceOffset.x + opticalBounds.left * sampleScale.x,
                sourceOffset.y + opticalBounds.top * sampleScale.y,
                sourceOffset.x + opticalBounds.right * sampleScale.x,
                sourceOffset.y + opticalBounds.bottom * sampleScale.y,
            )
            shader.setFloatUniform("center", sampleBounds.center.x * renderScale, sampleBounds.center.y * renderScale)
            shader.setFloatUniform("halfSize", sampleBounds.width * renderScale / 2f, sampleBounds.height * renderScale / 2f)
            shader.setFloatUniform("radius", radius * minOf(sampleScale.x, sampleScale.y) * renderScale)
            shader.setFloatUniform("ellipticalCorners", if (cornerRadii != null) 1f else 0f)
            shader.setFloatUniform("cornerRadii",
                (cornerRadii?.x ?: radius) * sampleScale.x * renderScale,
                (cornerRadii?.y ?: radius) * sampleScale.y * renderScale,
            )
            shader.setFloatUniform("secondaryEnabled", if (secondaryLensBounds != null) 1f else 0f)
            val secondary = secondaryLensBounds ?: opticalBounds
            shader.setFloatUniform("secondaryCenter",
                (sourceOffset.x + secondary.center.x * sampleScale.x) * renderScale,
                (sourceOffset.y + secondary.center.y * sampleScale.y) * renderScale,
            )
            shader.setFloatUniform("secondaryHalfSize", secondary.width * sampleScale.x * renderScale / 2f, secondary.height * sampleScale.y * renderScale / 2f)
            shader.setFloatUniform("secondaryRadii", (secondaryLensRadii?.x ?: radius) * sampleScale.x * renderScale, (secondaryLensRadii?.y ?: radius) * sampleScale.y * renderScale)
            shader.setFloatUniform("smoothing", lensSmoothing * minOf(sampleScale.x, sampleScale.y) * renderScale)
            bindLiquidMenuField(shader, menuField,
                coordinateScale = Offset(sampleScale.x * renderScale, sampleScale.y * renderScale),
                coordinateOffset = sourceOffset * renderScale,
            )
            shader.setFloatUniform("sampleExtent", ceil(viewport.width * renderScale), ceil(viewport.height * renderScale))
            shader.setFloatUniform("drawingBounds",
                (sourceOffset.x + bounds.left * sampleScale.x) * renderScale - 1f,
                (sourceOffset.y + bounds.top * sampleScale.y) * renderScale - 1f,
                (sourceOffset.x + bounds.right * sampleScale.x) * renderScale + 1f,
                (sourceOffset.y + bounds.bottom * sampleScale.y) * renderScale + 1f,
            )
            shader.setFloatUniform("height", lensHeight * renderScale)
            shader.setFloatUniform("amount", -lensAmount * renderScale)
            shader.setFloatUniform("outerHeight", if (customOptics) 0f else geometry.outerHeight * scope.density * renderScale)
            shader.setFloatUniform("outerAmount", if (customOptics) 0f else geometry.outerAmount * scope.density * renderScale)
            shader.setFloatUniform("refractionOpacity", .6f)
            shader.setFloatUniform("refractionEdgeWidth", scope.density * renderScale)
            shader.setFloatUniform("chromatic", if (customOptics) 1f else 0f)
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        } else null
        val liftProgress = shadowLiftProgress.coerceIn(0f, 1f)
        val opticalEffect = if (nativeBackgroundEffect != null && refractionEffect != null)
            RenderEffect.createChainEffect(refractionEffect, nativeBackgroundEffect) else nativeBackgroundEffect ?: refractionEffect
        val sampledEffect = if (!usesNativeMaterial && navigationMaterial && !isDark && shadowLiftEffect != null && liftProgress > 0f) {
            shadowLiftShader?.setFloatUniform("suppression", 1f - liftProgress)
            if (opticalEffect != null) RenderEffect.createChainEffect(shadowLiftEffect, opticalEffect)
            else shadowLiftEffect
        } else opticalEffect
        val highlightOpacity = highlightAlpha.coerceIn(0f, 1f)
        if (sampledHighlightAlpha != highlightOpacity) {
            sampledHighlight = Highlight(alpha = highlightOpacity, style = HighlightStyle.Default())
            sampledHighlightAlpha = highlightOpacity
        }
        val colorEffect = if (completeNativeMaterial) null else if (Build.VERSION.SDK_INT < 36 && nativeBackgroundFilter) {
            val edge = innerShadows.materialEffect(recipe ?: resolveRecipe(isDark, materialTint, tintMultiplier),
                alpha = if (materialEffect == null) 1f else 0f,
                minimumDimension = materialExtent,
                shape = shape, size = nativeFilterScope.size, density = nativeFilterScope,
                layoutDirection = layoutDirection, origin = effectOrigin, field = filterField,
                clearFraction = clearFraction, highlight = sampledHighlight,
                highlightClearFraction = clearFraction)
            if (materialEffect != null) RenderEffect.createChainEffect(edge, materialEffect) else edge
        } else materialEffect
        refracted.renderEffect = (if (colorEffect != null && sampledEffect != null)
            RenderEffect.createChainEffect(colorEffect, sampledEffect) else colorEffect ?: sampledEffect)?.asComposeRenderEffect()

        if (navigationOutline && !navigationMaterial) {
            val sideExpansion = 1.4.dp.toPx()
            withTransform({
                scale((bounds.width + sideExpansion) / bounds.width, 1f, bounds.center)
            }) {
                drawPath(path, Color(0xFF3F3F3F).copy(alpha = GlassBoxShadowAlpha))
            }
            drawPath(path, Color(0xFF6E6E6E).copy(alpha = GlassBoxShadowAlpha), style = Stroke(0.65.dp.toPx()))
        }
        val opticalHighlightBounds = lensBounds ?: bounds
        highlight.setFloatUniform("center", opticalHighlightBounds.center.x, opticalHighlightBounds.center.y)
        highlight.setFloatUniform("halfSize", opticalHighlightBounds.width / 2f, opticalHighlightBounds.height / 2f)
        highlight.setFloatUniform("radius", radius)
        highlight.setFloatUniform("ellipticalCorners", if (cornerRadii != null) 1f else 0f)
        highlight.setFloatUniform("cornerRadii", cornerRadii?.x ?: radius, cornerRadii?.y ?: radius)
        highlight.setFloatUniform("secondaryEnabled", if (secondaryLensBounds != null) 1f else 0f)
        val secondaryHighlight = secondaryLensBounds ?: opticalHighlightBounds
        highlight.setFloatUniform("secondaryCenter", secondaryHighlight.center.x, secondaryHighlight.center.y)
        highlight.setFloatUniform("secondaryHalfSize", secondaryHighlight.width / 2f, secondaryHighlight.height / 2f)
        highlight.setFloatUniform("secondaryRadii", secondaryLensRadii?.x ?: radius, secondaryLensRadii?.y ?: radius)
        highlight.setFloatUniform("smoothing", lensSmoothing)
        bindLiquidMenuField(highlight, menuField)
        highlight.setFloatUniform("opacity", highlightOpacity)
        highlight.setFloatUniform("angle", 0f)
        highlight.setFloatUniform("curvature", .75f)
        highlight.setFloatUniform("height", scope.density)
        // This rectangle anchors the light field; only the moving glass outline clips it.
        val lightBounds = pressHighlightBounds ?: bounds
        val glow = pressHighlight?.takeIf { it.glowProgress > 0f }
        val glowTint = pressTint ?: recipe?.tint
        val glowClear = pressClear ?: ((sourceFilterMaterial ?: materialPreset) == NavigationGlassMaterial.Clear)
        val glowDark = pressIsDark ?: isDark
        if (completeNativeMaterial) {
            fun Offset.sampled() = Offset(x * sampleScale.x, y * sampleScale.y)
            fun Size.sampled() = Size(width * sampleScale.x, height * sampleScale.y)
            // Materialize the explicit transition optics at their original texture raster.
            // Raw L0 remains in a separate region for the native exterior completion branch.
            val base = IntSize(ceil(viewport.width * renderScale).toInt(), ceil(viewport.height * renderScale).toInt())
            material.clip = true
            material.compositingStrategy = CompositingStrategy.Offscreen
            material.renderEffect = null
            material.record(this@PlayerSheetGlassMorphRenderer.density, recordedLayoutDirection, base) { drawLayer(refracted) }
            val progress = sourceFilterProgress?.coerceIn(0f, 1f) ?: 1f
            val sourceRecipe: NavigationMaterialRecipe? = sourceNativeMaterial?.recipe
            val sourceShadow: NativeGlassSourceShadow? = if (sourceRecipe != null && secondaryLensBounds != null && progress < 1f) {
                val originalExtent = sourceNativeMaterial.minimumDimension.takeIf { it.isFinite() }
                    ?: secondaryLensBounds.size.minDimension / scope.density
                NativeGlassSourceShadow(navigationNativeShadowProfile(originalExtent, sourceFilterMaterial),
                    Rect((secondaryLensBounds.topLeft - bounds.topLeft).sampled(), secondaryLensBounds.size.sampled()),
                    Offset((secondaryLensRadii?.x ?: radius) * sampleScale.x,
                        (secondaryLensRadii?.y ?: radius) * sampleScale.y), 1f - progress)
            } else null
            val targetRecipe: NavigationMaterialRecipe = recipe ?: resolveRecipe(isDark, materialTint, tintMultiplier)
            val completion: NativeGlassCompletion = NativeGlassCompletion(targetRecipe, isDark, clearFraction, darkFraction = presentationDarkFraction,
                materialAlpha = if (sourceRecipe != null) progress else 1f,
                edgeAlpha = innerShadowProgress,
                shadow = navigationNativeShadowProfile(materialExtent, materialPreset),
                shadowAlpha = dropShadowAlpha,
                sourceRecipe = sourceRecipe, sourceAlpha = if (sourceRecipe != null) 1f - progress else 0f,
                outputSize = Size(bounds.width * sampleScale.x, bounds.height * sampleScale.y),
                outputDensity = scope.density * minOf(sampleScale.x, sampleScale.y) * opticalScale,
                textureScale = renderScale, highlightAlpha = highlightOpacity, sourceShadow = sourceShadow)
            val completedEffect = nativeFilterScope.completeNativeFilteredGlass(base, null, isDark,
                materialPreset, 1f, origin = effectOrigin, field = filterField, completion = completion,
                filteredIsNativeRaw = true)
            val outputOrigin = nativeFilterScope.materialOutputOrigin
            val outputSize = requireNotNull(nativeFilterScope.materialOutputSize)
            val packedSize = IntSize(maxOf(base.width, outputSize.width), maxOf(base.height * 2, outputSize.height))
            val completed = requireNotNull(atlasBacking)
            completed.clip = true
            completed.compositingStrategy = CompositingStrategy.Offscreen
            completed.renderEffect = (if (Build.VERSION.SDK_INT < 36 && glow != null) {
                val sourceBounds = Rect(outputOrigin + (lightBounds.topLeft - bounds.topLeft).sampled(),
                    lightBounds.size.sampled())
                RenderEffect.createChainEffect(pressLight.effect(glow.glowProgress,
                    sourceBounds.topLeft + Offset(sourceBounds.width * glow.position.x, sourceBounds.height * glow.position.y),
                    sourceBounds, scope.density * minOf(sampleScale.x, sampleScale.y), glowDark, glowTint, glowClear,
                    glow.dissipationProgress), completedEffect)
            } else completedEffect).asComposeRenderEffect()
            completed.record(this@PlayerSheetGlassMorphRenderer.density, recordedLayoutDirection, packedSize) {
                clipRect(0f, 0f, base.width.toFloat(), base.height.toFloat()) { drawLayer(source) }
                withTransform({ translate(0f, base.height.toFloat()) }) {
                    clipRect(0f, 0f, base.width.toFloat(), base.height.toFloat()) { drawLayer(material) }
                }
            }
            withTransform({
                translate(bounds.left - outputOrigin.x / sampleScale.x, bounds.top - outputOrigin.y / sampleScale.y)
                scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
            }) {
                // Keep both sampled regions available while bounding the filter's otherwise
                // unbounded output; this is a consumer rectangle, never a shape clip.
                clipRect(0f, 0f, packedSize.width.toFloat(), packedSize.height.toFloat()) { drawLayer(completed) }
            }
            if (Build.VERSION.SDK_INT >= 36 && glow != null) {
                clipGlassShape(bounds, radius, shape, path) {
                    inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                        val sourceBounds = lightBounds.translate(-bounds.topLeft)
                        pressLight.draw(this, glow.glowProgress,
                            sourceBounds.topLeft + Offset(sourceBounds.width * glow.position.x, sourceBounds.height * glow.position.y),
                            sourceBounds = sourceBounds, clampPosition = pressHighlightBounds == null,
                            isDark = glowDark, tint = glowTint, clear = glowClear, dissipationProgress = glow.dissipationProgress)
                    }
                }
            }
            return@with
        }
        val useNavigationMaterial = navigationMaterial && appearance.usesBackdrop
        var materialOrigin = Offset.Zero
        if (useNavigationMaterial) {
            val nativeSize = size
            // The material has no filter of its own. Its guarded source/effect layers
            // keep the full optical capture; only the final compositing target is cropped.
            // Integer texel origins preserve the original raster and bilinear sample grid.
            materialOrigin = Offset(
                floor((sourceOffset.x + bounds.left) * renderScale) - 1f,
                floor((sourceOffset.y + bounds.top) * renderScale) - 1f,
            )
            // Reserve the stable endpoint host on the first frame, avoiding repeated
            // allocations while the opening droplet grows through the size buckets.
            val requiredWidth = maxOf(
                (ceil((sourceOffset.x + bounds.right) * renderScale) + 1f - materialOrigin.x).toInt(),
                ceil(nativeSize.width * renderScale).toInt() + 3,
            )
            val requiredHeight = maxOf(
                (ceil((sourceOffset.y + bounds.bottom) * renderScale) + 1f - materialOrigin.y).toInt(),
                ceil(nativeSize.height * renderScale).toInt() + 3,
            )
            materialWidth = maxOf(materialWidth, (requiredWidth + 63) / 64 * 64)
            materialHeight = maxOf(materialHeight, (requiredHeight + 63) / 64 * 64)
            val bufferSize = IntSize(materialWidth, materialHeight)
            material.clip = true
            material.compositingStrategy = CompositingStrategy.Offscreen
            material.record(
                density = this@PlayerSheetGlassMorphRenderer.density,
                layoutDirection = recordedLayoutDirection,
                size = bufferSize,
            ) {
                // Material pixels map to (local + sourceOffset) * renderScale - origin.
                withTransform({
                    translate(-materialOrigin.x, -materialOrigin.y)
                    scale(renderScale, renderScale, Offset.Zero)
                    translate(sourceOffset.x, sourceOffset.y)
                }) {
                    val previousSize = drawContext.size
                    drawContext.size = nativeSize
                    try {
                        withTransform({
                            scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
                            translate(-sourceOffset.x, -sourceOffset.y)
                            scale(1f / renderScale, 1f / renderScale, Offset.Zero)
                        }) { drawLayer(refracted) }
                        inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                            if (materialPaint != null) materialPaint()
                            else if (recipe == null) drawNavigationGlassMaterial(tint, isDark, tintMultiplier, materialPreset, materialTint)
                        }
                        if (recipe != null) {
                            innerShadows.drawMaterial(this, shape, recipe, bounds, isDark, alpha = innerShadowProgress,
                                field = edgeField, clearFraction = clearFraction)
                        } else innerShadows.drawEdges(this, shape, bounds, isDark, alpha = innerShadowProgress,
                            field = edgeField, clearFraction = clearFraction)
                    } finally {
                        drawContext.size = previousSize
                    }
                }
            }
            material.renderEffect = if (Build.VERSION.SDK_INT < 36 && glow != null) {
                val sourceBounds = Rect(
                    (lightBounds.topLeft + sourceOffset) * renderScale - materialOrigin,
                    lightBounds.size * renderScale,
                )
                pressLight.effect(
                    glow.glowProgress,
                    sourceBounds.topLeft + Offset(sourceBounds.width * glow.position.x, sourceBounds.height * glow.position.y),
                    sourceBounds, scope.density * renderScale, glowDark, glowTint, glowClear,
                    glow.dissipationProgress,
                ).asComposeRenderEffect()
            } else null
        } else {
            clipGlassShape(bounds, radius, shape, path) {
                if (appearance.usesBackdrop) {
                    withTransform({
                        scale(1f / sampleScale.x, 1f / sampleScale.y, Offset.Zero)
                        translate(-sourceOffset.x, -sourceOffset.y)
                        scale(1f / renderScale, 1f / renderScale, Offset.Zero)
                    }) { drawLayer(refracted) }
                }
                if (materialPaint != null) {
                    inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                        materialPaint()
                    }
                } else {
                    drawRect(
                        tint.copy(
                            alpha = if (appearance.reduceTransparency) 1f
                                else (tint.alpha * tintMultiplier).coerceIn(0f, 1f),
                        ),
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                    )
                }
            }
        }
        clipGlassShape(bounds, radius, shape, path) {
            if (useNavigationMaterial) {
                withTransform({
                    translate(
                        materialOrigin.x / renderScale - sourceOffset.x,
                        materialOrigin.y / renderScale - sourceOffset.y,
                    )
                    scale(1f / renderScale, 1f / renderScale, Offset.Zero)
                }) { drawLayer(material) }
            }
            if (Build.VERSION.SDK_INT >= 36 && highlightOpacity > 0f) {
                val canvas = drawContext.canvas.nativeCanvas
                val blender = highlightBlender
                if (canvas.isHardwareAccelerated && blender != null) {
                    blender.prepareHighlight(clearFraction)
                    highlightPaint.xfermode = blender.shader
                    canvas.drawPath(path.asAndroidPath(), highlightPaint)
                }
            }
            if (Build.VERSION.SDK_INT >= 36 && glow != null) {
                inset(bounds.left, bounds.top, size.width - bounds.right, size.height - bounds.bottom) {
                    val source = lightBounds.translate(-bounds.topLeft)
                    pressLight.draw(
                        this,
                        glow.glowProgress,
                        source.topLeft + Offset(
                            source.width * glow.position.x,
                            source.height * glow.position.y,
                        ),
                        sourceBounds = source,
                        clampPosition = pressHighlightBounds == null,
                        isDark = glowDark,
                        tint = glowTint,
                        clear = glowClear,
                        dissipationProgress = glow.dissipationProgress,
                    )
                }
            }
        }
    }

    private fun reserveCompletionSize(
        viewport: Size,
        atlas: IntSize,
        output: IntSize,
        origin: Offset,
        scale: Offset,
    ): IntSize {
        // Keep atlas inputs and the endpoint raster available without resizing for
        // each growing contour. Reset the reservation after window-size changes.
        if (completionViewportSize != viewport) {
            completionViewportSize = viewport
            completionWidth = 0
            completionHeight = 0
        }
        val width = maxOf(atlas.width, output.width, ceil(viewport.width * scale.x + origin.x * 2f).toInt())
        val height = maxOf(atlas.height, output.height, ceil(viewport.height * scale.y + origin.y * 2f).toInt())
        completionWidth = maxOf(completionWidth, (width + 63) / 64 * 64)
        completionHeight = maxOf(completionHeight, (height + 63) / 64 * 64)
        return IntSize(completionWidth, completionHeight)
    }
}

/** Reuse the standard native material graph over a morph's viewport-coordinate source. */
private class MorphNativeFilterScope : BackdropEffectScopeImpl() {
    override val supportsNativeGlassHardwareLinearSampling: Boolean get() = true
    override var shape: Shape = RectangleShape

    fun configure(scope: DrawScope, shape: Shape, size: Size, density: Float) {
        this.shape = shape
        this.size = size
        this.density = density
        fontScale = scope.fontScale
        layoutDirection = scope.layoutDirection
    }
}

private const val LiquidMorphFieldShader = NativeGlassSdfFunctions + """
uniform shader smoothedField;
uniform float smoothedFieldEnabled;
uniform float2 fieldCoordinateScale;
uniform float2 fieldCoordinateOffset;
uniform float fieldDistanceScale;
uniform float2 fieldGradientScale;
uniform float2 fieldExtent;
// QuartzCore supercircle_sdf_sampler (24A434, 0x1c39c8dd0).
// Oversized radii are intentional during overrounding; the native builder's
// curvature terms handle them before the two fields are combined.
float3 roundedFieldGradient(float2 point, float2 origin, float2 extent, float2 radii) {
    // Match GlassSurface's boundary normal, including along straight edges.
    return nativeGlassRoundedField(point - origin, extent, radii, 0.0);
}
float3 liquidAnalyticFieldGradient(float2 point) {
    float3 a = roundedFieldGradient(point, center, halfSize, cornerRadii);
    if (secondaryEnabled < 0.5) return a;
    float3 b = roundedFieldGradient(point, secondaryCenter, secondaryHalfSize, secondaryRadii);
    // QuartzCore SDFUnion, iOS 24A434, 0x1c39e27d0: only opposing
    // unit gradients widen the polynomial union between the two skins.
    float k = smoothing * clamp(0.5 - 0.5 * dot(a.yz, b.yz), 0.0, 1.0);
    if (k <= 0.000001) return a.x < b.x ? a : b;
    float h = clamp(0.5 + 0.5 * (a.x - b.x) / k, 0.0, 1.0);
    return float3(mix(a.x, b.x, h) - k * h * (1.0 - h), mix(a.yz, b.yz, h));
}
float3 liquidFieldGradient(float2 point) {
    if (smoothedFieldEnabled > 0.5) {
        float2 pixel = point * fieldCoordinateScale + fieldCoordinateOffset;
        // The Gaussian grid covers a narrow skin band. Clamping its exterior distance
        // would extend the native key strip across the remaining consumer rectangle.
        if (pixel.x >= 0.5 && pixel.y >= 0.5 &&
            pixel.x <= fieldExtent.x - 0.5 && pixel.y <= fieldExtent.y - 0.5) {
            float3 field = smoothedField.eval(pixel).rgb;
            return field * float3(fieldDistanceScale, fieldGradientScale);
        }
    }
    return liquidAnalyticFieldGradient(point);
}

"""

private const val MorphGlassFieldShader = """
uniform float2 nativeFieldOrigin;
uniform float2 center;
uniform float2 halfSize;
uniform float2 cornerRadii;
uniform float secondaryEnabled;
uniform float2 secondaryCenter;
uniform float2 secondaryHalfSize;
uniform float2 secondaryRadii;
uniform float smoothing;
$LiquidMorphFieldShader
float3 navigationGlassField(float2 point) { return liquidFieldGradient(point + nativeFieldOrigin); }
float navigationGlassShadowDistance(float2 point) { return liquidAnalyticFieldGradient(point + nativeFieldOrigin).x; }
"""

/** A contour's conservative capture bounds are not its physical shadow silhouette. */
private fun nativeMorphShadowField(shape: Shape): NativeGlassFilterField? {
    val contour = shape as? LiquidMenuContour ?: return null
    return NativeGlassFilterField(MorphGlassFieldShader) { shader ->
        val primary = contour.incoming
        val secondary = contour.outgoing ?: primary
        shader.setFloatUniform("nativeFieldOrigin", 0f, 0f)
        shader.setFloatUniform("center", primary.center.x, primary.center.y)
        shader.setFloatUniform("halfSize", primary.width * .5f, primary.height * .5f)
        shader.setFloatUniform("cornerRadii", contour.incomingRadii.x, contour.incomingRadii.y)
        shader.setFloatUniform("secondaryEnabled", if (contour.outgoing == null) 0f else 1f)
        shader.setFloatUniform("secondaryCenter", secondary.center.x, secondary.center.y)
        shader.setFloatUniform("secondaryHalfSize", secondary.width * .5f, secondary.height * .5f)
        shader.setFloatUniform("secondaryRadii", contour.outgoingRadii.x, contour.outgoingRadii.y)
        shader.setFloatUniform("smoothing", contour.smoothing)
        // Exterior shadows need an unbounded distance field beyond the skin's sampled band.
        bindLiquidMenuField(shader, null)
    }
}

private const val TransitionRefractionShader = NativeGlassRefractionFunctions + """
uniform shader content;
uniform float2 sampleExtent;
uniform float4 drawingBounds;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float2 cornerRadii;
uniform float ellipticalCorners;
uniform float secondaryEnabled;
uniform float2 secondaryCenter;
uniform float2 secondaryHalfSize;
uniform float2 secondaryRadii;
uniform float smoothing;
uniform float height;
uniform float amount;
uniform float outerHeight;
uniform float outerAmount;
uniform float refractionOpacity;
uniform float refractionEdgeWidth;
uniform float chromatic;

float2 safeNormal(float2 v) { return v / max(length(v), 0.0001); }

$LiquidMorphFieldShader

half4 sampleContent(float2 coord) {
    return content.eval(clamp(coord, float2(0.5), sampleExtent - float2(0.5)));
}
half4 nativeGlassSample(float2 coord, float blurRadius) { return sampleContent(coord); }
""" + NativeGlassSpectralFunctions + CustomGlassChromaticFunctions + """

half4 main(float2 coord) {
    if (ellipticalCorners > 0.5 && (coord.x < drawingBounds.x || coord.y < drawingBounds.y ||
        coord.x > drawingBounds.z || coord.y > drawingBounds.w)) return half4(0.0);
    if (height <= 0.0 || amount == 0.0) return sampleContent(coord);
    float2 p = coord - center;
    float3 liquid = nativeGlassRoundedField(p, halfSize, float2(radius), 0.0);
    if (ellipticalCorners > 0.5) {
        liquid = liquidFieldGradient(coord);
    }
    float sd = liquid.x;
    if (secondaryEnabled < 0.5 && (abs(p.x) > halfSize.x + 1.0 || abs(p.y) > halfSize.y + 1.0)) return half4(0.0);
    if (-sd >= max(height, outerHeight)) return sampleContent(coord);
    float d = nativeGlassDisplacement(sd, 1.0 / height, amount);
    float2 grad = liquid.yz;
    float2 refracted = coord + d * grad;
    half4 result = sampleContent(refracted);
    if (chromatic > 0.5) result = nativeGlassAberrate(refracted,
        customGlassChromaticVector(d * grad, p, halfSize), 0.0);
    if (outerHeight > 0.0 && outerAmount != 0.0 && refractionOpacity > 0.0) {
        float outerDistance = nativeGlassDisplacement(sd, 1.0 / outerHeight, outerAmount);
        half4 outer = sampleContent(coord + outerDistance * grad);
        result = mix(result, outer, half(nativeGlassRefractionWeight(sd, -refractionEdgeWidth, 0.0, refractionOpacity)));
    }
    return result;
}
"""

// The same native CASDF strip used by live surfaces, evaluated on the retained contour.
private const val TransitionHighlightShader = """
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float2 cornerRadii;
uniform float ellipticalCorners;
uniform float secondaryEnabled;
uniform float2 secondaryCenter;
uniform float2 secondaryHalfSize;
uniform float2 secondaryRadii;
uniform float smoothing;
uniform float opacity;
uniform float angle;
uniform float curvature;
uniform float height;
$LiquidMorphFieldShader
""" + NativeGlassEdgeFunctions + NativeCasdfHighlightMaskFunctions + """
half4 main(float2 coord) {
    float3 field = ellipticalCorners > .5 ? liquidFieldGradient(coord)
        : nativeGlassRoundedField(coord - center, halfSize, float2(radius), 0.0);
    float mask = nativeCasdfHighlightCoverage(field.x, field.yz, height, angle, curvature) * opacity;
    return half4(0.0, 0.0, 0.0, mask);
}
"""
