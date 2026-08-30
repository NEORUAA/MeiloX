package com.ljyh.mei.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.RectangleShape
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val IosModalSheetShape = ContinuousRoundedRectangle(
    topStart = 34.dp,
    topEnd = 34.dp,
    bottomStart = 58.dp,
    bottomEnd = 58.dp,
)

private val LocalIosPopupMenuInteractive = staticCompositionLocalOf { true }

/** Overflow room around the menu so spring overshoot, velocity deformation, blur bleed and the
 *  drop shadow can draw past the resting bounds without being clipped by the popup host. The
 *  visible menu is offset back onto the anchor edge, so its position and physics are unchanged. */
private val PopupMenuOvershootMarginStart = 64.dp
private val PopupMenuOvershootMarginVertical = 32.dp
private val PopupMenuWidth = 238.dp

/** The stable shell is larger than the resting menu so the spring overshoot (up to 1.12x)
 *  fits inside the shell's constraints and actually renders. */
private const val PopupMenuOvershootScale = 1.15f

private class IosPopupPositionProvider(
    private val targetMenuHeightPx: Int,
    private val forceBelowAnchor: Boolean,
    private val visualHostWidthPx: Int,
    private val visualHostHeightPx: Int,
    private val anchorOvershootVerticalPx: Int,
    private val onDirectionResolved: (opensAbove: Boolean) -> Unit,
) : PopupPositionProvider {
    private var opensAbove: Boolean? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val resolvedDirection = if (forceBelowAnchor) {
            false
        } else {
            opensAbove ?: run {
                val roomBelow = windowSize.height - anchorBounds.bottom
                val roomAbove = anchorBounds.top
                (roomBelow < targetMenuHeightPx && roomAbove > roomBelow).also {
                    opensAbove = it
                    onDirectionResolved(it)
                }
            }
        }
        // Use the intended visual host dimensions instead of popupContentSize. On narrow
        // devices Android may constrain the expanded Popup content to the window width; using
        // that constrained size would make the visible menu drift toward the right.
        val x = (anchorBounds.right - visualHostWidthPx)
            .coerceIn(0, (windowSize.width - visualHostWidthPx).coerceAtLeast(0))
        // The forced-below variant is used by message bubbles. Its popup host contains the
        // vertical overshoot margin plus the 15% spring shell, so positioning the host at
        // `anchorBounds.bottom` would leave the menu beside the bubble. Offset the host by the
        // exact distance from its top edge to the resting menu top instead.
        val menuTopInHost = (
            visualHostHeightPx - targetMenuHeightPx
        ).coerceAtLeast(0)
        val visualY = if (forceBelowAnchor) {
            anchorBounds.bottom - menuTopInHost
        } else if (resolvedDirection) {
            anchorBounds.bottom - visualHostHeightPx
        } else {
            anchorBounds.top
        }.coerceIn(0, (windowSize.height - visualHostHeightPx).coerceAtLeast(0))
        val y = if (forceBelowAnchor || resolvedDirection) {
            visualY
        } else {
            visualY - anchorOvershootVerticalPx
        }
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
                        else colors.segmentedControlBackground,
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

/** Compact toolbar used inside modal sheets. Sheet actions intentionally do not sample the
 * content behind the sheet; they use the same grouped-list tint/alpha with the shared highlight
 * and drag physics instead. */
@Composable
fun IosSheetTopToolbar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    IosTopToolbar(
        title = title,
        modifier = modifier.offset(y = (-5).dp),
        actions = actions,
    )
}

@Composable
fun IosSheetTopToolbarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val groupedListBackground = colors.elevatedBackground.copy(
        alpha = LocalGroupedListBackgroundAlpha.current.coerceIn(0f, 1f),
    )
    GlassIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        navigationSurfaceColor = groupedListBackground,
        navigationSurfaceAlphaMultiplier = 1f,
        sampleBackdrop = false,
        content = content,
    )
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = LocalGlassColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .drawBehind {
                val inset = 16.dp.toPx()
                drawLine(
                    colors.separator,
                    androidx.compose.ui.geometry.Offset(inset, 0f),
                    androidx.compose.ui.geometry.Offset(size.width - inset, 0f),
                    1.dp.toPx(),
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value,
            onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            textStyle = IosTypography.body.copy(color = colors.content),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = IosTypography.body, color = colors.tertiaryContent)
                inner()
            },
        )
        trailing?.let { trailingContent ->
            CompositionLocalProvider(LocalGroupedListIconColor provides colors.content) {
                trailingContent()
            }
        }
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
        trailing?.let { trailingContent ->
            CompositionLocalProvider(LocalGroupedListIconColor provides colors.content) {
                trailingContent()
            }
        }
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
    val colors = LocalGlassColors.current
    val fill = if (colors.isDark) Color.White.copy(alpha = 0.12f) else Color(0x14747480)
    Row(modifier.background(fill, Capsule()).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 32.dp).clickable(enabled = value > range.first) { onValueChange(value - 1) }, contentAlignment = Alignment.Center) {
            SfIcon("minus", null, size = 17.dp, tint = colors.content)
        }
        Box(Modifier.width(1.dp).height(22.dp).background(colors.separator))
        Box(Modifier.size(width = 46.dp, height = 32.dp).clickable(enabled = value < range.last) { onValueChange(value + 1) }, contentAlignment = Alignment.Center) {
            SfIcon("plus", null, size = 17.dp, tint = colors.content)
        }
    }
}

/** Figma nodes 770:21901 and 754:62668. */
@Composable
fun IosContextMenu(
    visible: Boolean,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalBlurBackdrop.current,
    animationProgress: Float = 1f,
    animationVelocity: Float = 0f,
    transformOriginProgress: Float = animationProgress,
    menuAlpha: Float = animationProgress,
    contentAlpha: Float = animationProgress,
    shadowAlpha: Float = animationProgress,
    opensAbove: Boolean = false,
    itemCount: Int = 1,
    content: @Composable ColumnScope.(LayerBackdrop) -> Unit,
) {
    if (!visible) return
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val progress = animationProgress.coerceIn(0f, 1f)
    val normalizedVelocity = (animationVelocity / 18f).coerceIn(-1f, 1f)
    val pulse = max(
        sin(PI.toFloat() * progress),
        abs(normalizedVelocity) * 0.65f,
    ).coerceIn(0f, 1f)
    val menuWidth = PopupMenuWidth
    val menuHeight = 20.dp + 44.dp * itemCount
    val radius = 34.dp
    val transitionScale = 0.24f + 0.76f * animationProgress
    val startOrigin = TransformOrigin(1f, if (opensAbove) 1f else 0f)
    val originProgress = transformOriginProgress.coerceIn(0f, 1f)
    val transitionOrigin = TransformOrigin(
        pivotFractionX = startOrigin.pivotFractionX +
            (0.5f - startOrigin.pivotFractionX) * originProgress,
        pivotFractionY = startOrigin.pivotFractionY +
            (0.5f - startOrigin.pivotFractionY) * originProgress,
    )
    val childBackdrop = rememberLayerBackdrop()
    // Context menus render in a separate Popup window. Map their sampling coordinates back
    // to the source window so the menu refracts the content physically behind its position.
    val samplingBackdrop = rememberCrossWindowBackdrop(backdrop)
    val isLight = !LocalGlassColors.current.isDark
    val elevatedBackground = LocalGlassColors.current.elevatedBackground
    val menuLayerBlock: GraphicsLayerScope.() -> Unit = {
        transformOrigin = startOrigin
        applyGlassDragScale(
            pressProgress = interactiveHighlight.pressProgress,
            offset = interactiveHighlight.offset,
        )
        val dragScaleX = scaleX
        val dragScaleY = scaleY
        val dragTranslationX = translationX
        val dragTranslationY = translationY
        val startPivotX = startOrigin.pivotFractionX * size.width
        val startPivotY = startOrigin.pivotFractionY * size.height
        val transitionPivotX = transitionOrigin.pivotFractionX * size.width
        val transitionPivotY = transitionOrigin.pivotFractionY * size.height

        // Fold the nested transition and drag transforms into the layer passed to drawBackdrop.
        // LayerBackdrop applies the inverse of this complete transform while sampling, so the
        // page-sized source remains stationary as the visible menu grows, collapses, or deforms.
        transformOrigin = transitionOrigin
        scaleX = transitionScale * dragScaleX
        scaleY = transitionScale * dragScaleY
        translationX = transitionScale * (
            dragTranslationX + (1f - dragScaleX) * (startPivotX - transitionPivotX)
        )
        translationY = transitionScale * (
            dragTranslationY + (1f - dragScaleY) * (startPivotY - transitionPivotY)
        )
    }
    // Stable full-size host: keep overshoot margins on both the growth and anchor sides in the
    // measured Popup bounds. The original visual host is nested inside it, so this only enlarges
    // the RenderEffect backing area and does not move the visible menu.
    val popupHostWidth = PopupMenuOvershootMarginStart * 2 + menuWidth * PopupMenuOvershootScale
    val popupHostHeight = PopupMenuOvershootMarginVertical * 2 + menuHeight * PopupMenuOvershootScale
    Box(
        modifier
            // Keep the transition RenderEffect on the stable Popup host.  A blur modifier
            // creates a bounded graphics layer even with Unbounded edge treatment; placing it
            // on the spring-sized menu would therefore expose that menu-sized rectangle while
            // the menu is below its resting scale.
            .blur(
                (8.dp * (1f - progress)).coerceAtLeast(0.dp),
                BlurredEdgeTreatment.Unbounded,
            )
            .graphicsLayer { alpha = menuAlpha.coerceIn(0f, 1f) }
            .size(width = popupHostWidth, height = popupHostHeight),
    ) {
        Box(
            Modifier
                .align(if (opensAbove) Alignment.TopStart else Alignment.BottomStart)
                .size(
                    width = PopupMenuOvershootMarginStart + menuWidth * PopupMenuOvershootScale,
                    height = PopupMenuOvershootMarginVertical + menuHeight * PopupMenuOvershootScale,
                ),
        ) {
            Box(
                Modifier
                    .align(if (opensAbove) Alignment.BottomEnd else Alignment.TopEnd)
                    .size(width = menuWidth, height = menuHeight)
                    .navigationGlassBoxShadow(
                        shape = { RoundedRectangle(radius) },
                        alpha = GlassBoxShadowAlpha * shadowAlpha.coerceIn(0f, 1f),
                        layerBlock = menuLayerBlock,
                    ),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = samplingBackdrop,
                            exportedBackdrop = childBackdrop,
                            shape = { RoundedRectangle(radius) },
                            effects = {
                                blur(if (isLight) 16.dp.toPx() else 12.dp.toPx())
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = progress * (0.46f + 0.18f * pulse))
                            },
                            shadow = {
                                Shadow(
                                    radius = 32.dp,
                                    offset = androidx.compose.ui.unit.DpOffset(0.dp, 10.dp),
                                    color = Color.Black,
                                    alpha = 0.4f * GlassBoxShadowAlpha *
                                        shadowAlpha.coerceIn(0f, 1f),
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
                        .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) },
                ) {
                    content(childBackdrop)
                }
            }
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
    keepAnchorVisible: Boolean = false,
    forceBelowAnchor: Boolean = false,
    anchor: @Composable (onClick: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(LayerBackdrop, close: () -> Unit) -> Unit,
) {
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    var popupAlive by remember { mutableStateOf(expanded) }
    var opensAbove by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    val transformOriginProgress = remember { Animatable(if (expanded) 1f else 0f) }
    val menuAlpha = remember { Animatable(if (expanded) 1f else 0f) }
    val shadowAlpha = remember { Animatable(if (expanded) 1f else 0f) }
    var contentAlpha by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }

    LaunchedEffect(Unit) {
        var previousFraction = progress.value
        snapshotFlow { progress.value }
            .collect { currentFraction ->
                val isEntering = currentFraction >= previousFraction
                previousFraction = currentFraction
                contentAlpha = if (isEntering) {
                    0.2f + 0.8f * currentFraction
                } else if (currentFraction > 0.5f) {
                    1f
                } else {
                    currentFraction * 2f
                }
            }
    }

    LaunchedEffect(Unit) {
        var previousFraction = progress.value
        var shadowVisible = progress.value >= 0.78f
        var animationJob: Job? = null
        snapshotFlow { progress.value }
            .collect { currentFraction ->
                val isEntering = currentFraction >= previousFraction
                previousFraction = currentFraction
                val newVisible = if (isEntering) {
                    currentFraction >= 0.78f
                } else {
                    currentFraction >= 0.99f
                }
                if (newVisible != shadowVisible) {
                    shadowVisible = newVisible
                    animationJob?.cancel()
                    animationJob = launch {
                        if (newVisible) {
                            shadowAlpha.animateTo(1f, tween(200))
                        } else if (shadowAlpha.value >= 1f) {
                            shadowAlpha.animateTo(0f, tween(50))
                        } else {
                            shadowAlpha.snapTo(0f)
                        }
                    }
                }
            }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            popupAlive = true
            coroutineScope {
                launch {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = 240f,
                            visibilityThreshold = 0.0001f,
                        ),
                    )
                }
                launch {
                    transformOriginProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = 500f,
                            visibilityThreshold = 0.0001f,
                        ),
                    )
                }
                launch { menuAlpha.animateTo(1f, tween(120)) }
            }
        } else {
            coroutineScope {
                launch {
                    progress.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = 400f,
                            visibilityThreshold = 0.0001f,
                        ),
                    )
                }
                launch {
                    transformOriginProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 450,
                            easing = CubicBezierEasing(0f, 0f, 0f, 1f),
                        ),
                    )
                }
                launch { menuAlpha.animateTo(0f, tween(400)) }
            }
            progress.snapTo(0f)
            transformOriginProgress.snapTo(0f)
            menuAlpha.snapTo(0f)
            popupAlive = false
        }
    }

    Box(modifier.onSizeChanged { anchorSize = it }) {
        Box(
            Modifier
                .graphicsLayer {
                    alpha = if (keepAnchorVisible) {
                        1f
                    } else {
                        1f - menuAlpha.value.coerceIn(0f, 1f)
                    }
                },
        ) {
            anchor { onExpandedChange(!expanded) }
        }
        if (popupAlive && anchorSize != IntSize.Zero) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val targetMenuHeightPx = with(density) { (20.dp + 44.dp * itemCount).roundToPx() }
            val visualHostWidthPx = with(density) {
                (PopupMenuOvershootMarginStart + PopupMenuWidth * PopupMenuOvershootScale).roundToPx()
            }
            val visualHostHeightPx = with(density) {
                (
                    PopupMenuOvershootMarginVertical +
                        (20.dp + 44.dp * itemCount) * PopupMenuOvershootScale
                ).roundToPx()
            }
            val positionProvider = remember(
                anchorSize,
                targetMenuHeightPx,
                forceBelowAnchor,
                visualHostWidthPx,
                visualHostHeightPx,
            ) {
                IosPopupPositionProvider(
                    targetMenuHeightPx = targetMenuHeightPx,
                    forceBelowAnchor = forceBelowAnchor,
                    visualHostWidthPx = visualHostWidthPx,
                    visualHostHeightPx = visualHostHeightPx,
                    anchorOvershootVerticalPx = with(density) {
                        PopupMenuOvershootMarginVertical.roundToPx()
                    },
                ) { resolved ->
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
                    clippingEnabled = false,
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
                            transformOriginProgress = transformOriginProgress.value,
                            menuAlpha = menuAlpha.value,
                            contentAlpha = contentAlpha,
                            shadowAlpha = shadowAlpha.value,
                            opensAbove = opensAbove,
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
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
                    weight = FontWeight.Bold
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
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
        val alertWidth = (maxWidth * 0.8f).coerceIn(280.dp, 380.dp)
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets.statusBars },
    content: @Composable ColumnScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        modifier = modifier.graphicsLayer {
            clip = false
            applyGlassDragScale(
                pressProgress = interactiveHighlight.pressProgress,
                offset = interactiveHighlight.offset,
            )
        },
        containerColor = Color.Transparent,
        contentColor = LocalGlassColors.current.content,
        // Keep the host unclipped so drag-scale overshoot reaches the outer outline;
        // IosSheetSurface owns the visible rounded shape.
        shape = RectangleShape,
        dragHandle = null,
        contentWindowInsets = contentWindowInsets,
    ) {
        IosSheetSurface(
            modifier = Modifier.fillMaxWidth(),
            backdrop = backdrop,
            shape = IosModalSheetShape,
            interactiveHighlight = interactiveHighlight,
            applyDragScale = false,
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
    shape: Shape = RoundedRectangle(38.dp),
    interactiveHighlight: InteractiveHighlight? = null,
    applyDragScale: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val isLight = !colors.isDark
    val animationScope = rememberCoroutineScope()
    val activeInteractiveHighlight = interactiveHighlight ?: remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val sheetLayerBlock: (GraphicsLayerScope.() -> Unit)? = if (applyDragScale) {
        {
            applyGlassDragScale(
                pressProgress = activeInteractiveHighlight.pressProgress,
                offset = activeInteractiveHighlight.offset,
            )
        }
    } else {
        null
    }
    // Sheet surfaces render inside dialog windows (ModalBottomSheet); the shared backdrop
    // lives in the app window, so sample it with window-space coordinates. The library's
    // localPositionOf mapping cannot cross compose owners and silently sampled nothing.
    val samplingBackdrop = rememberCrossWindowBackdrop(backdrop)
    Box(
        modifier
            .fillMaxWidth()
                .navigationGlassBoxShadow(
                    shape = { shape },
                    alpha = GlassBoxShadowAlpha,
                    layerBlock = sheetLayerBlock,
                )
                .drawBackdrop(
                    backdrop = samplingBackdrop,
                    shape = { shape },
                    effects = {
                        blur(if (isLight) 16.dp.toPx() else 12.dp.toPx())
                    },
                    highlight = { Highlight.Default.copy(alpha = if (isLight) 0.58f else 0.38f) },
                    shadow = { Shadow(radius = 48.dp, alpha = 0.25f) },
                    innerShadow = { InnerShadow(radius = 8.dp, alpha = 0.12f) },
                    layerBlock = sheetLayerBlock,
                    onDrawSurface = {
                        drawRect(
                            colors.elevatedBackground.copy(alpha = if (isLight) 0.72f else 0.54f),
                        )
                        drawRect(Color.White.copy(alpha = if (isLight) 0.12f else 0.04f))
                    },
                )
                .then(activeInteractiveHighlight.modifier)
                .then(activeInteractiveHighlight.gestureModifier),
            content = {
                CompositionLocalProvider(
                    LocalContentColor provides colors.content,
                    LocalGlassContentColor provides colors.content,
                    LocalGroupedListBackgroundAlpha provides SheetGroupedListBackgroundAlpha,
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
    backdrop: Backdrop = LocalBlurBackdrop.current,
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
