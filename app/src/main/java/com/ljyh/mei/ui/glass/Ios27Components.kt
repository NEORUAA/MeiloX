package com.ljyh.mei.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.launch

val IosModalSheetShape = ContinuousRoundedRectangle(
    topStart = 34.dp,
    topEnd = 34.dp,
    bottomStart = 58.dp,
    bottomEnd = 58.dp,
)

private val LocalIosPopupMenuInteractive = staticCompositionLocalOf { true }

/** Overflow room on the menu's growth sides so spring overshoot, velocity deformation,
 *  blur bleed and the drop shadow can draw past the resting bounds without being clipped
 *  by the popup window. The anchor-side corner stays flush with the window corner, so
 *  positioning math and the pinned trigger copy are unaffected. */
private val PopupMenuOvershootMarginStart = 64.dp
private val PopupMenuOvershootMarginVertical = 32.dp

/** The stable shell is larger than the resting menu so the spring overshoot (up to 1.12x)
 *  fits inside the shell's constraints and actually renders. */
private const val PopupMenuOvershootScale = 1.15f

private class IosPopupPositionProvider(
    private val targetMenuHeightPx: Int,
    private val onDirectionResolved: (opensAbove: Boolean) -> Unit,
) : PopupPositionProvider {
    private var opensAbove: Boolean? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val resolvedDirection = opensAbove ?: run {
            val roomBelow = windowSize.height - anchorBounds.bottom
            val roomAbove = anchorBounds.top
            (roomBelow < targetMenuHeightPx && roomAbove > roomBelow).also {
                opensAbove = it
                onDirectionResolved(it)
            }
        }
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = if (resolvedDirection) {
            anchorBounds.bottom - popupContentSize.height
        } else {
            anchorBounds.top
        }.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/** Scrollable iOS capsule tabs for sets that cannot fit a segmented control. */
@Composable
fun <T> IosScrollableTabRow(
    items: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.first.toString() }) { (value, label) ->
            val isSelected = value == selected
            Text(
                text = label,
                color = if (isSelected) Color.White else colors.content,
                style = IosTypography.subheadline,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(
                        if (isSelected) colors.prominentContainer.copy(alpha = 1f)
                        else colors.elevatedBackground,
                        Capsule(),
                    )
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                    ) { onSelected(value) }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            )
        }
    }
}

enum class IosTopBarStyle { Default, CompactLargeTitle, LargeTitle, TwoLine, TwoLineLeading }

enum class IosBottomToolbarStyle { Text, Symbols, Search }

private val IosTopToolbarActionGap = 8.dp

/** The five top-toolbar variants from Figma node 5661:41970. */
@Composable
fun IosTopToolbar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: IosTopBarStyle = IosTopBarStyle.Default,
    collapseProgress: Float = 1f,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val progress = collapseProgress.coerceIn(0f, 1f)
    CompositionLocalProvider(
        LocalGlassSurfaceBrightness provides 1f,
        LocalGlassSurfaceStyle provides GlassSurfaceStyle.Navigation,
        LocalContentColor provides LocalGlassColors.current.content,
    ) {
        when (style) {
            IosTopBarStyle.LargeTitle -> Column(modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { navigation?.invoke() }
                    Row(horizontalArrangement = Arrangement.spacedBy(IosTopToolbarActionGap), content = actions)
                }
                Column(Modifier.offset(y = (-10).dp)) {
                    Text(title, style = IosTypography.largeTitle, color = LocalGlassColors.current.content)
                    subtitle?.let { Text(it, style = IosTypography.subheadline, color = LocalGlassColors.current.secondaryContent) }
                }
            }
            IosTopBarStyle.CompactLargeTitle -> Row(
                modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = IosTypography.largeTitle,
                    color = LocalGlassColors.current.content,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(IosTopToolbarActionGap), content = actions)
            }
            else -> {
                var leadingWidth by remember { mutableIntStateOf(0) }
                var trailingWidth by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current
                val leadingPad = with(density) { leadingWidth.toDp() }
                val trailingPad = with(density) { trailingWidth.toDp() }
                val centeredTitleSidePadding = maxOf(leadingPad, trailingPad) +
                    if (leadingWidth > 0 || trailingWidth > 0) IosTopToolbarActionGap else 0.dp
                Box(modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp)) {
                    Row(
                        Modifier.align(Alignment.CenterStart).onSizeChanged { leadingWidth = it.width },
                        verticalAlignment = Alignment.CenterVertically,
                    ) { navigation?.invoke() }
                    Column(
                        Modifier
                            .align(if (style == IosTopBarStyle.TwoLineLeading) Alignment.CenterStart else Alignment.Center)
                            .fillMaxWidth()
                            .padding(
                                // Centered styles stay optically centered: reserve the wider
                                // side on both edges, plus the same gap used between actions.
                                // The leading style just clears the sides.
                                start = if (style == IosTopBarStyle.TwoLineLeading) leadingPad
                                else centeredTitleSidePadding,
                                end = if (style == IosTopBarStyle.TwoLineLeading) trailingPad
                                else centeredTitleSidePadding,
                            )
                            .graphicsLayer {
                                alpha = progress
                                val scale = 0.92f + 0.08f * progress
                                scaleX = scale
                                scaleY = scale
                            }
                            .blur(8.dp * (1f - progress)),
                        horizontalAlignment = if (style == IosTopBarStyle.TwoLineLeading) Alignment.Start else Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title,
                            style = if (subtitle == null) IosTypography.headline
                            else IosTypography.subheadline.copy(fontWeight = FontWeight.SemiBold),
                            color = LocalGlassColors.current.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let {
                            Text(
                                it,
                                style = IosTypography.caption,
                                color = LocalGlassColors.current.secondaryContent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        Modifier.align(Alignment.CenterEnd).onSizeChanged { trailingWidth = it.width },
                        horizontalArrangement = Arrangement.spacedBy(IosTopToolbarActionGap),
                        content = actions,
                    )
                }
            }
        }
    }
}

/** Figma node 2517:14528. Floating bottom toolbar variants share the liquid capsule. */
@Composable
fun IosBottomToolbar(
    modifier: Modifier = Modifier,
    style: IosBottomToolbarStyle = IosBottomToolbarStyle.Symbols,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.height(if (style == IosBottomToolbarStyle.Search) 64.dp else 56.dp),
        backdrop = backdrop,
        shape = Capsule(),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
fun IosBottomSearchToolbar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onCancel: () -> Unit,
    @Suppress("UNUSED_PARAMETER") cancelLabel: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: GlassSurfaceStyle = GlassSurfaceStyle.Navigation,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val colors = LocalGlassColors.current
    val backdrop = LocalGlassBackdrop.current
    Row(
        modifier.height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassSurface(
            modifier = Modifier.weight(1f).height(56.dp),
            backdrop = backdrop,
            shape = Capsule(),
            style = style,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SfIcon("magnifyingglass", null, size = 21.dp, tint = colors.secondaryContent)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = IosTypography.body.copy(color = colors.content),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query.text) }),
                    decorationBox = { inner ->
                        if (query.text.isEmpty()) {
                            Text(placeholder, style = IosTypography.body, color = colors.tertiaryContent)
                        }
                        inner()
                    },
                )
                if (query.text.isNotEmpty()) {
                    Box(
                        Modifier.size(32.dp).clickable { onQueryChange(TextFieldValue()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        SfIcon("xmark.circle", null, size = 18.dp, tint = colors.secondaryContent)
                    }
                }
            }
        }
        GlassIconButton(
            onClick = onCancel,
            backdrop = backdrop,
            style = style,
            modifier = Modifier.size(56.dp),
        ) {
            SfIcon("xmark", null, size = 20.dp, tint = colors.content)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** Figma node 5661:33949: 52dp table text field with iOS separators. */
@Composable
fun IosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = LocalGlassColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .drawBehind { drawLine(colors.separator, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value,
            onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            textStyle = IosTypography.body.copy(color = colors.content),
            singleLine = true,
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = IosTypography.body, color = colors.tertiaryContent)
                inner()
            },
        )
        trailing?.invoke()
    }
}

/** Figma node 781:15374 grouped-list container. Use ordinary rows inside for GPU efficiency. */
@Composable
fun IosGroupedList(
    modifier: Modifier = Modifier,
    // Sheets and alert surfaces already provide the glass; their lists must render
    // unframed, otherwise the opaque card reads as a second corner radius inside the sheet.
    framed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val background = colors.elevatedBackground.copy(
        alpha = LocalGroupedListBackgroundAlpha.current.coerceIn(0f, 1f),
    )
    val shape = ContinuousRoundedRectangle(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (framed) {
                    Modifier
                        .clip(shape)
                        .background(background, shape)
                        .drawWithContent {
                            drawContent()
                            // Hide only the first merged row's inset separator. Clipping the
                            // container prevents the cover leaking across the top corners.
                            val inset = 16.dp.toPx()
                            drawRect(
                                color = background,
                                topLeft = androidx.compose.ui.geometry.Offset(inset, 0f),
                                size = Size((size.width - inset * 2f).coerceAtLeast(0f), 1.dp.toPx()),
                            )
                        }
                } else {
                    Modifier
                },
            )
            // Rows own their horizontal insets. This avoids the accidental double inset
            // produced by secondary-page cards while preserving the Settings geometry.
            .padding(horizontal = 0.dp),
    ) {
        CompositionLocalProvider(
            LocalMergedGlassCards provides true,
            LocalGroupedListIconColor provides colors.accent,
            LocalContentColor provides colors.content,
        ) { content() }
    }
}

@Composable
fun IosListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detail: String? = null,
    systemName: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showTopSeparator: Boolean = true,
) {
    val colors = LocalGlassColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(if (subtitle == null) 52.dp else 62.dp)
            .drawBehind {
                if (showTopSeparator) {
                    drawLine(
                        colors.separator,
                        androidx.compose.ui.geometry.Offset(16.dp.toPx(), 0f),
                        androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), 0f),
                        1.dp.toPx(),
                    )
                }
            }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        systemName?.let {
            SfIcon(it, null, size = 23.dp, tint = colors.accent)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = IosTypography.body,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = IosTypography.subheadline,
                    color = colors.secondaryContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        detail?.let { Text(it, style = IosTypography.subheadline, color = colors.secondaryContent) }
        trailing?.invoke()
        if (onClick != null && trailing == null) {
            Spacer(Modifier.width(8.dp)); SfIcon("chevron.forward", null, size = 12.dp, tint = colors.separator)
        }
    }
}

/** Figma node 5661:43368 stepper. */
@Composable
fun IosStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
) {
    val fill = if (LocalGlassColors.current.isDark) Color.White.copy(alpha = 0.12f) else Color(0x14747480)
    Row(modifier.background(fill, Capsule()).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 32.dp).clickable(enabled = value > range.first) { onValueChange(value - 1) }, contentAlignment = Alignment.Center) {
            SfIcon("minus", null, size = 17.dp)
        }
        Box(Modifier.width(1.dp).height(22.dp).background(LocalGlassColors.current.separator))
        Box(Modifier.size(width = 46.dp, height = 32.dp).clickable(enabled = value < range.last) { onValueChange(value + 1) }, contentAlignment = Alignment.Center) {
            SfIcon("plus", null, size = 17.dp)
        }
    }
}

/** Figma nodes 770:21901 and 754:62668. */
@Composable
fun IosContextMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    animationProgress: Float = 1f,
    animationVelocity: Float = 0f,
    opensAbove: Boolean = false,
    collapsedSize: IntSize = IntSize(44, 44),
    itemCount: Int = 1,
    content: @Composable ColumnScope.(LayerBackdrop) -> Unit,
) {
    if (!visible) return
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val geometryProgress = animationProgress.coerceIn(-0.04f, 1.06f)
    val progress = animationProgress.coerceIn(0f, 1f)
    val normalizedVelocity = (animationVelocity / 18f).coerceIn(-1f, 1f)
    val pulse = max(
        sin(PI.toFloat() * progress),
        abs(normalizedVelocity) * 0.65f,
    ).coerceIn(0f, 1f)
    val collapsedWidth = with(density) { collapsedSize.width.toDp() }
    val collapsedHeight = with(density) { collapsedSize.height.toDp() }
    val menuWidth = 238.dp
    val menuHeight = 20.dp + 44.dp * itemCount
    val width = lerpDp(collapsedWidth, menuWidth, geometryProgress)
    val height = lerpDp(collapsedHeight, menuHeight, geometryProgress)
    val radius = 34.dp
    // Content follows both directions continuously. Delaying it until 34% made the rows
    // disappear near the beginning of close while the glass shell was still visibly shrinking.
    val contentProgress = progress
    val childBackdrop = rememberLayerBackdrop()
    // Context menus render in a separate Popup window. Map their sampling coordinates back
    // to the source window so the menu refracts the content physically behind its position.
    val samplingBackdrop = rememberCrossWindowBackdrop(backdrop)
    val elevatedBackground = LocalGlassColors.current.elevatedBackground
    val menuLayerBlock: GraphicsLayerScope.() -> Unit = {
        transformOrigin = TransformOrigin(1f, if (opensAbove) 1f else 0f)
        applyGlassDragScale(
            pressProgress = interactiveHighlight.pressProgress,
            offset = interactiveHighlight.offset,
        )
    }
    // Stable full-size shell: the hosting Popup window never resizes or moves during the
    // animation, so a sibling pinned to a corner of this box (the trigger copy) cannot
    // wobble with the spring physics or be clipped by an undersized window.
    Box(
        modifier
            .padding(
                start = PopupMenuOvershootMarginStart,
                top = if (opensAbove) PopupMenuOvershootMarginVertical else 0.dp,
                bottom = if (opensAbove) 0.dp else PopupMenuOvershootMarginVertical,
            )
            .size(
                width = menuWidth * PopupMenuOvershootScale,
                height = menuHeight * PopupMenuOvershootScale,
            ),
    ) {
        Column(
            Modifier
                .align(if (opensAbove) Alignment.BottomEnd else Alignment.TopEnd)
                // Whole-menu transition: alpha 0->1 and blur->0 while growing, reversed while
                // shrinking. The tail reaches alpha 0 before the window is removed, so the
                // collapse never pops out of existence.
                // Unbounded: the default Rectangle edge treatment clips at the node bounds,
                // which cut the elastic deformation flat during the animation.
                .blur(
                    (10.dp * (1f - progress)).coerceAtLeast(0.dp),
                    BlurredEdgeTreatment.Unbounded,
                )
                .graphicsLayer { alpha = progress }
                .size(width = width, height = height)
                .navigationGlassBoxShadow(
                    shape = { RoundedRectangle(radius) },
                    alpha = GlassBoxShadowAlpha,
                    layerBlock = menuLayerBlock,
                )
                .drawBackdrop(
                    backdrop = samplingBackdrop,
                    exportedBackdrop = childBackdrop,
                    shape = { RoundedRectangle(radius) },
                    effects = {
                        vibrancy()
                        blur(lerp(3.dp.toPx(), 16.dp.toPx(), progress))
                        lens(
                            refractionHeight = lerp(10.dp.toPx(), 18.dp.toPx(), progress) +
                                2.dp.toPx() * pulse,
                            refractionAmount = lerp(16.dp.toPx(), 26.dp.toPx(), progress) +
                                4.dp.toPx() * pulse,
                            depthEffect = pulse > 0.01f,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = progress * (0.46f + 0.18f * pulse))
                    },
                    shadow = {
                        Shadow(
                            radius = 15.dp,
                            offset = androidx.compose.ui.unit.DpOffset(0.dp, 8.dp),
                            color = Color.Black,
                            alpha = 0.02f * GlassBoxShadowAlpha,
                        )
                    },
                    innerShadow = { InnerShadow(radius = 8.dp, alpha = 0.10f * progress) },
                    layerBlock = menuLayerBlock,
                    onDrawSurface = {
                        drawRect(elevatedBackground.copy(alpha = 0.70f * progress))
                    },
                )
                .then(interactiveHighlight.modifier)
                .then(interactiveHighlight.gestureModifier)
                .clip(ContinuousRoundedRectangle(radius))
                .padding(10.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, if (opensAbove) 1f else 0f)
                    val contentScale = 0.92f + 0.08f * contentProgress
                    scaleX = contentScale
                    scaleY = contentScale
                },
        ) {
            if (contentProgress > 0.001f) content(childBackdrop)
        }
    }
}

/**
 * Anchored iOS popup whose refractive surface grows out of the trigger itself.
 * One Popup window covers the whole expand/open/collapse lifecycle and `progress` is the
 * single source of truth: the spring always continues from the current value and velocity,
 * so toggling at any moment reverses the motion in place instead of swapping to a separate
 * closing copy (which could lose the collapse animation entirely). While collapsing the
 * window is non-focusable and its rows are non-interactive, so it cannot be dismissed twice.
 */
@Composable
fun IosPopupMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    itemCount: Int,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    anchor: @Composable (onClick: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(LayerBackdrop, close: () -> Unit) -> Unit,
) {
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    var popupAlive by remember { mutableStateOf(expanded) }
    var opensAbove by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            popupAlive = true
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = 260f,
                    visibilityThreshold = 0.001f,
                ),
            )
        } else {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.74f,
                    stiffness = 280f,
                    visibilityThreshold = 0.001f,
                ),
            )
            popupAlive = false
        }
    }

    Box(modifier.onSizeChanged { anchorSize = it }) {
        Box(
            Modifier
                .graphicsLayer { alpha = if (expanded) 0f else 1f },
        ) {
            anchor { onExpandedChange(!expanded) }
        }
        if (popupAlive && anchorSize != IntSize.Zero) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val targetMenuHeightPx = with(density) { (20.dp + 44.dp * itemCount).roundToPx() }
            val positionProvider = remember(anchorSize, targetMenuHeightPx) {
                IosPopupPositionProvider(targetMenuHeightPx) { resolved ->
                    opensAbove = resolved
                }
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(
                    focusable = expanded,
                    dismissOnBackPress = expanded,
                    dismissOnClickOutside = expanded,
                ),
            ) {
                // The overshoot margin is invisible window area; treat taps there like
                // outside taps (dismiss) instead of letting them vanish into the window.
                Box(
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                    ) { onExpandedChange(false) },
                ) {
                    CompositionLocalProvider(LocalIosPopupMenuInteractive provides expanded) {
                        IosContextMenu(
                            visible = true,
                            backdrop = backdrop,
                            animationProgress = progress.value,
                            animationVelocity = progress.velocity,
                            opensAbove = opensAbove,
                            collapsedSize = anchorSize,
                            itemCount = itemCount,
                        ) { childBackdrop ->
                            content(childBackdrop) { onExpandedChange(false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun <T> IosPopupButton(
    selected: T,
    items: List<T>,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    var expanded by remember { mutableStateOf(false) }
    IosPopupMenu(
        expanded = expanded,
        onExpandedChange = { if (enabled || !it) expanded = it },
        itemCount = items.size,
        modifier = modifier,
        backdrop = backdrop,
        anchor = { openMenu ->
            Row(
                Modifier
                    .clickable(
                        enabled = enabled,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = openMenu,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label(selected),
                    style = IosTypography.body,
                    color = if (enabled) LocalGlassColors.current.accent
                    else LocalGlassColors.current.secondaryContent,
                )
                SfIcon(
                    "chevron.up.chevron.down",
                    contentDescription = null,
                    size = 15.dp,
                    tint = if (enabled) LocalGlassColors.current.accent
                    else LocalGlassColors.current.secondaryContent,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        },
    ) { childBackdrop, close ->
        items.forEach { item ->
            IosMenuItem(
                title = label(item),
                onClick = {
                    onSelected(item)
                    close()
                },
                systemName = if (item == selected) "checkmark" else null,
                backdrop = childBackdrop,
            )
        }
    }
}

@Composable
fun IosMenuItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    systemName: String? = null,
    destructive: Boolean = false,
    backdrop: Backdrop = LocalGlassBackdrop.current,
) {
    val scope = rememberCoroutineScope()
    val interactive = LocalIosPopupMenuInteractive.current
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(
                color = Color.Black.copy(alpha = 0.15f * highlight.pressProgress),
                shape = Capsule(),
            )
            .then(
                if (interactive) {
                    Modifier
                        .clickable(interactionSource = null, indication = null, onClick = onClick)
                        .then(highlight.gestureModifier)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed-width gutter keeps every title's left edge aligned, whether or not the
        // row shows a checkmark (iOS context-menu alignment).
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (systemName != null) {
                SfIcon(systemName, null, size = 20.dp, tint = if (destructive) LocalGlassColors.current.destructive else LocalGlassColors.current.content)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = IosTypography.body, color = if (destructive) LocalGlassColors.current.destructive else LocalGlassColors.current.content)
    }
}

enum class IosAlertButtonLayout {
    SideBySide,
    Stacked,
}

enum class IosAlertButtonRole {
    Default,
    Cancel,
    Destructive,
}

data class IosAlertButtonSpec(
    val label: String,
    val onClick: () -> Unit,
    val role: IosAlertButtonRole = IosAlertButtonRole.Default,
    val enabled: Boolean = true,
)

/** Figma node 65:57149. The alert samples the screen behind its dialog window. */
@Composable
fun IosAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalAlertBackdrop.current,
    buttonLayout: IosAlertButtonLayout = IosAlertButtonLayout.SideBySide,
    buttons: List<IosAlertButtonSpec> = emptyList(),
    properties: DialogProperties = DialogProperties(
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        usePlatformDefaultWidth = false,
    ),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalGlassColors.current
    val dimAmount = if (colors.isDark) 0.2f else 0.08f
    val fullScreenProperties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
        windowTitle = properties.windowTitle,
        windowType = properties.windowType,
        windowToken = properties.windowToken,
    )
    Dialog(onDismissRequest = onDismissRequest, properties = fullScreenProperties) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAmount))
                    .then(
                        if (properties.dismissOnClickOutside) {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onDismissRequest,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
            IosAlertSurface(
                modifier = modifier.align(Alignment.Center),
                backdrop = backdrop,
                title = title,
                message = message,
                dimAmount = 0f,
            ) {
                content()
                if (buttons.isNotEmpty()) {
                    when (buttonLayout) {
                        IosAlertButtonLayout.SideBySide -> {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                buttons.forEach { button ->
                                    IosAlertButton(
                                        text = button.label,
                                        onClick = button.onClick,
                                        modifier = Modifier.weight(1f),
                                        role = button.role,
                                        enabled = button.enabled,
                                    )
                                }
                            }
                        }

                        IosAlertButtonLayout.Stacked -> {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                buttons.forEach { button ->
                                    IosAlertButton(
                                        text = button.label,
                                        onClick = button.onClick,
                                        modifier = Modifier.fillMaxWidth(),
                                        role = button.role,
                                        enabled = button.enabled,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Figma node 65:57149 alert surface, reusable by list and form alerts. */
@Composable
fun IosAlertSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalAlertBackdrop.current,
    title: String,
    message: String? = null,
    dimAmount: Float? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = LocalGlassColors.current
    val light = !colors.isDark
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        dialogWindow?.setDimAmount((dimAmount ?: if (light) 0.01f else 0.08f).coerceIn(0f, 1f))
    }
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val samplingBackdrop = rememberCrossWindowBackdrop(backdrop)
    val alertShape = ContinuousRoundedRectangle(34.dp)
    val alertBackgroundAlpha = if (light) 0.72f else 0.64f
    val alertLayerBlock: GraphicsLayerScope.() -> Unit = {
        applyGlassDragScale(
            pressProgress = interactiveHighlight.pressProgress,
            offset = interactiveHighlight.offset,
        )
    }
    BoxWithConstraints(modifier) {
        val alertWidth = (maxWidth * 0.8f).coerceIn(300.dp, 400.dp)
        Column(
            Modifier
                .width(alertWidth)
                .navigationGlassBoxShadow(
                    shape = { alertShape },
                    alpha = GlassBoxShadowAlpha,
                    layerBlock = alertLayerBlock,
                )
                .drawBackdrop(
                    backdrop = samplingBackdrop,
                    shape = { alertShape },
                    effects = {
                        blur(if (light) 16.dp.toPx() else 8.dp.toPx())
                    },
                    highlight = { Highlight.Plain },
                    layerBlock = alertLayerBlock,
                    onDrawSurface = {
                        // Keep the Figma background-blend stack over the sampled screen;
                        // do not add an opaque elevated surface on top of the backdrop.
                        if (light) {
                            drawRect(
                                Color.White.copy(alpha = 0.70f * alertBackgroundAlpha),
                                blendMode = BlendMode.Lighten,
                            )
                            drawRect(
                                Color(0x1ABFBFBF).copy(alpha = 0.10f * alertBackgroundAlpha),
                                blendMode = BlendMode.Darken,
                            )
                        } else {
                            drawRect(
                                Color(0xB31A1A1A).copy(alpha = 0.70f * alertBackgroundAlpha),
                                blendMode = BlendMode.Luminosity,
                            )
                            drawRect(
                                Color(0xE61A1A1A).copy(alpha = 0.90f * alertBackgroundAlpha),
                                blendMode = BlendMode.Luminosity,
                            )
                            drawRect(
                                Color(0xFF1A1A1A).copy(alpha = alertBackgroundAlpha),
                                blendMode = BlendMode.Lighten,
                            )
                        }
                    },
                )
                .then(interactiveHighlight.modifier)
                .then(interactiveHighlight.gestureModifier)
                .padding(14.dp),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides colors.content,
                LocalGlassContentColor provides colors.content,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        title,
                        style = IosTypography.headline,
                        color = colors.content,
                    )
                    message?.let {
                        Text(
                            it,
                            style = IosTypography.body,
                            color = colors.content,
                        )
                    }
                }
                content()
            }
        }
    }
}

/** The standard 48dp action used by [IosAlertDialog]. */
@Composable
fun IosAlertButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: IosAlertButtonRole = IosAlertButtonRole.Default,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit = {
        Text(
            text,
            style = IosTypography.headline,
            color = when (role) {
                IosAlertButtonRole.Default -> Color.White
                IosAlertButtonRole.Cancel -> LocalGlassColors.current.content
                IosAlertButtonRole.Destructive -> LocalGlassColors.current.destructive
            },
        )
    },
) {
    val colors = LocalGlassColors.current
    val baseFill = when (role) {
        IosAlertButtonRole.Default -> Color(0xFF0088FF)
        IosAlertButtonRole.Cancel,
        IosAlertButtonRole.Destructive -> if (colors.isDark) {
            Color(0x52787880)
        } else {
            Color(0x28787880)
        }
    }
    val fill = baseFill.copy(alpha = baseFill.alpha * if (enabled) 1f else 0.45f)

    Row(
        modifier
            .height(48.dp)
            .clip(Capsule())
            .background(fill)
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The grouped field backing used by form alerts in the referenced Figma component. */
@Composable
fun IosAlertFieldGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedRectangle(26.dp))
            .background(
                if (colors.isDark) Color.White.copy(alpha = 0.16f) else Color(0x28787880),
            )
            .padding(bottom = 19.dp),
        content = content,
    )
}

@Composable
fun IosAlertTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val colors = LocalGlassColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        textStyle = IosTypography.body.copy(color = colors.content),
        singleLine = singleLine,
        maxLines = maxLines,
        decorationBox = { innerTextField ->
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = IosTypography.body,
                        color = colors.tertiaryContent,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
fun IosModalOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!visible) return
    Box(modifier.fillMaxSize().background(Color(0x3B29293A)), contentAlignment = Alignment.Center, content = content)
}

/** Figma node 10525:1632. The glass shell reaches behind system navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets.statusBars },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = LocalGlassColors.current.content,
        shape = IosModalSheetShape,
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        IosSheetSurface(
            modifier = Modifier.fillMaxWidth(),
            backdrop = backdrop,
            shape = IosModalSheetShape,
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding(),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(width = 58.dp, height = 4.dp)
                            .background(LocalGlassColors.current.tertiaryContent.copy(alpha = 0.55f), Capsule()),
                    )
                }
                content()
            }
        }
    }
}

/** Figma node 770:21908. The shell is glass; rows inside remain ordinary grouped content. */
@Composable
fun IosSheetSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    shape: Shape = RoundedRectangle(38.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val isLight = !colors.isDark
    // Sheet surfaces render inside dialog windows (ModalBottomSheet); the shared backdrop
    // lives in the app window, so sample it with window-space coordinates. The library's
    // localPositionOf mapping cannot cross compose owners and silently sampled nothing.
    val samplingBackdrop = rememberCrossWindowBackdrop(backdrop)
    Box(
        modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = samplingBackdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(16.dp.toPx())
                    lens(20.dp.toPx(), 34.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight.Default.copy(alpha = if (isLight) 0.58f else 0.38f) },
                shadow = { Shadow(radius = 48.dp, alpha = 0.25f) },
                innerShadow = { InnerShadow(radius = 8.dp, alpha = 0.12f) },
                onDrawSurface = {
                    drawRect(
                        colors.elevatedBackground.copy(alpha = if (isLight) 0.72f else 0.54f),
                    )
                    drawRect(Color.White.copy(alpha = if (isLight) 0.12f else 0.04f))
                },
            ),
        content = {
            CompositionLocalProvider(
                LocalContentColor provides colors.content,
                LocalGlassContentColor provides colors.content,
            ) {
                content()
            }
        },
    )
}

/** Figma node 754:62559: title, optional message, and a vertically grouped action list. */
@Composable
fun IosActionSheet(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    backdrop: Backdrop = LocalGlassBackdrop.current,
    shape: Shape = IosModalSheetShape,
    showHandle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    IosSheetSurface(modifier, backdrop, shape) {
        IosActionSheetContent(
            title = title,
            message = message,
            showHandle = showHandle,
            content = content,
        )
    }
}

@Composable
fun IosActionSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    showHandle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    CompositionLocalProvider(LocalContentColor provides colors.content) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(
                top = if (showHandle) 4.dp else 18.dp,
                bottom = 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showHandle) {
                Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.TopCenter) {
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 4.dp)
                            .background(LocalGlassColors.current.tertiaryContent.copy(alpha = 0.55f), Capsule()),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(title, style = IosTypography.headline, color = colors.content)
                message?.let {
                    Text(
                        it,
                        style = IosTypography.subheadline,
                        color = LocalGlassColors.current.secondaryContent,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            IosGroupedList(content = content)
        }
    }
}
