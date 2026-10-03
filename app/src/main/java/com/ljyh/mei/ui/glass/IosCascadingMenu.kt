package com.ljyh.mei.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.rememberCoroutineScope
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/** Data adapter for the shared popup components; it does not own another menu style. */
data class IosCascadingMenuItem(
    val title: String,
    val systemName: String? = null,
    val destructive: Boolean = false,
    val separatorBefore: Boolean = false,
    val children: List<IosCascadingMenuItem> = emptyList(),
    val onClick: () -> Unit = {},
)

private object ScreenMenuPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset.Zero
}

/** Uses the settings popup lifecycle, glass surface, and interactive menu rows unchanged. */
@Composable
fun IosCascadingMenu(
    anchorBounds: Rect?,
    items: List<IosCascadingMenuItem>,
    title: String,
    expandedDescription: String,
    collapsedDescription: String,
    onDismiss: () -> Unit,
    backdrop: Backdrop = LocalBlurBackdrop.current,
    headerActions: List<IosCascadingMenuItem> = emptyList(),
    onTriggerAlphaChanged: ((Float) -> Unit)? = null,
    useAccentIcons: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var childExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val child = remember { Animatable(0f) }
    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    var selectedBounds by remember { mutableStateOf<Rect?>(null) }
    val colors = LocalGlassColors.current
    val headerRows = if (headerActions.isEmpty()) 0 else 2
    val textMeasurer = rememberTextMeasurer()
    val longestActionWidth = headerActions.maxOfOrNull {
        textMeasurer.measure(it.title, style = IosTypography.subheadline, maxLines = 1).size.width
    } ?: 0
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val longestRowWidth = items.maxOfOrNull {
        textMeasurer.measure(it.title, style = IosTypography.body, maxLines = 1).size.width
    } ?: 0
    val menuWidth = if (headerActions.isEmpty()) {
        maxOf(238.dp, with(density) { longestRowWidth.toDp() } + 104.dp)
            .coerceAtMost((windowWidth - 24.dp).coerceAtLeast(1.dp))
    } else {
        // Include both cells' inner padding and the shared menu's outer content padding.
        val requiredWidth = (with(density) { longestActionWidth.toDp() } + 32.dp) * headerActions.size + 20.dp
        maxOf(280.dp, requiredWidth + 4.dp).coerceAtMost((windowWidth - 24.dp).coerceAtLeast(1.dp))
    }

    LaunchedEffect(Unit) { started = true; open = true }
    LaunchedEffect(childExpanded) {
        child.animateTo(if (childExpanded) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 240f))
        if (!childExpanded) {
            selectedIndex = null
            selectedBounds = null
        }
    }

    fun close(action: (() -> Unit)? = null) {
        pendingAction = action
        childExpanded = false
        open = false
    }

    IosPopupMenu(
        expanded = open,
        onExpandedChange = { if (!it) close() },
        itemCount = items.size + headerRows,
        heightOverride = 20.dp + 44.dp * (items.size + headerRows) + 12.dp * items.count { it.separatorBefore },
        menuWidth = menuWidth,
        backdrop = backdrop,
        externalAnchorBounds = anchorBounds,
        keepAnchorVisible = true,
        menuScale = 1f - 0.04f * child.value.coerceIn(0f, 1f),
        onMenuBoundsChanged = { menuBounds = it },
        onMenuAlphaChanged = { onTriggerAlphaChanged?.invoke(1f - it) },
        onClosed = {
            if (started && !open) {
                onDismiss()
                pendingAction?.invoke()
            }
        },
        anchor = { Spacer(Modifier.size(1.dp)) },
    ) { childBackdrop, _ ->
        if (headerActions.isNotEmpty()) {
            Column(Modifier.graphicsLayer {
                alpha = 1f - 0.58f * child.value.coerceIn(0f, 1f)
            }.then(if (selectedIndex != null) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                Row(Modifier.fillMaxWidth().height(76.dp)) {
                    headerActions.forEach { action ->
                        MenuHeaderAction(
                            action = action,
                            iconTint = if (action.destructive) colors.destructive else if (useAccentIcons) colors.accent else colors.content,
                            enabled = open && selectedIndex == null,
                            modifier = Modifier.weight(1f),
                            onClick = { close(action.onClick) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.separator)
                }
            }
        }
        items.forEachIndexed { index, item ->
            if (item.separatorBefore) {
                Box(Modifier.fillMaxWidth().height(12.dp).graphicsLayer {
                    alpha = 1f - 0.58f * child.value.coerceIn(0f, 1f)
                }, contentAlignment = Alignment.Center) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.separator)
                }
            }
            IosMenuItem(
                title = item.title,
                systemName = item.systemName,
                destructive = item.destructive,
                backdrop = childBackdrop,
                iconTint = if (item.destructive) colors.destructive else if (useAccentIcons) colors.accent else colors.content,
                enabled = selectedIndex == null,
                modifier = Modifier.graphicsLayer {
                    alpha = if (selectedIndex == index) 0f else 1f - 0.58f * child.value.coerceIn(0f, 1f)
                }.then(if (selectedIndex != null) Modifier.semantics { hideFromAccessibility() } else Modifier),
                trailing = if (item.children.isEmpty()) null else {
                    { MenuChevron(0f, collapsedDescription) }
                },
                onClick = {
                    if (item.children.isEmpty()) close(item.onClick)
                    else {
                        menuBounds?.let { bounds ->
                            // Use the resting menu layout and the shared row metrics, never
                            // the pressed row's transformed screen coordinates.
                            val inset = with(density) { 10.dp.toPx() }
                            val rowHeight = with(density) { 44.dp.toPx() }
                            val top = bounds.top + inset + rowHeight * (index + headerRows) +
                                with(density) { 12.dp.toPx() } * items.take(index + 1).count { it.separatorBefore }
                            selectedBounds = Rect(
                                bounds.left + inset, top, bounds.right - inset, top + rowHeight,
                            )
                            selectedIndex = index
                            childExpanded = true
                        }
                    }
                },
            )
        }
    }
    val selected = selectedIndex?.let(items::getOrNull)
    val bounds = selectedBounds
    if (selected != null && bounds != null) {
        Popup(
            popupPositionProvider = ScreenMenuPosition,
            onDismissRequest = { childExpanded = false },
            properties = PopupProperties(focusable = open, clippingEnabled = false),
        ) {
            var windowOrigin by remember { mutableStateOf<Offset?>(null) }
            BoxWithConstraints(
                Modifier.fillMaxSize().onGloballyPositioned {
                    windowOrigin = it.localToScreen(Offset.Zero)
                }.semantics { paneTitle = title },
            ) {
                val origin = windowOrigin ?: return@BoxWithConstraints
                val density = LocalDensity.current
                val p = child.value.coerceIn(0f, 1f)
                val topInset = with(density) {
                    androidx.compose.foundation.layout.WindowInsets.safeDrawing.getTop(this).toDp()
                } + 12.dp
                val bottomInset = with(density) {
                    androidx.compose.foundation.layout.WindowInsets.safeDrawing.getBottom(this).toDp()
                } + 12.dp
                val targetHeight = minOf(20.dp + 44.dp * (selected.children.size + 1) + 12.dp, maxHeight - topInset - bottomInset)
                val rowTop = with(density) { (bounds.top - origin.y).toDp() } - 10.dp
                val targetTop = rowTop.coerceIn(topInset, (maxHeight - bottomInset - targetHeight).coerceAtLeast(topInset))
                val top = rowTop + (targetTop - rowTop) * p
                val left = with(density) { (bounds.left - origin.x).toDp() } - 10.dp
                Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { childExpanded = false })
                IosContextMenu(
                    visible = true,
                    modifier = Modifier.offset(left, top),
                    backdrop = backdrop,
                    compact = true,
                    menuWidth = menuWidth,
                    heightOverride = 64.dp + (targetHeight - 64.dp) * p,
                    shadowAlpha = p,
                    itemCount = selected.children.size + 1,
                ) { childBackdrop ->
                    IosMenuItem(
                        title = selected.title,
                        systemName = selected.systemName,
                        iconTint = if (selected.destructive) colors.destructive else if (useAccentIcons) colors.accent else colors.content,
                        destructive = selected.destructive,
                        fontWeight = FontWeight((400 + 200 * p).roundToInt()),
                        backdrop = childBackdrop,
                        enabled = open,
                        trailing = { MenuChevron(90f * p, if (childExpanded) expandedDescription else collapsedDescription) },
                        onClick = { childExpanded = !childExpanded },
                    )
                    Column(Modifier.weight(1f).graphicsLayer {
                        alpha = p
                        translationY = -12.dp.toPx() * (1f - p)
                    }.verticalScroll(rememberScrollState())) {
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = colors.separator)
                        selected.children.forEach { item ->
                            IosMenuItem(
                                title = item.title,
                                backdrop = childBackdrop,
                                enabled = open && childExpanded && p > 0.95f,
                                onClick = { close(item.onClick) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuChevron(rotation: Float, state: String) {
    SfIcon(
        "chevron.right", null, size = 14.dp, tint = LocalGlassColors.current.content,
        modifier = Modifier.semantics { stateDescription = state }.graphicsLayer { rotationZ = rotation },
    )
}

/** The active shape is inset from the 34dp menu radius by its 10dp content padding. */
@Composable
private fun MenuHeaderAction(
    action: IosCascadingMenuItem,
    iconTint: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val shape = ContinuousRoundedRectangle(34.dp - 10.dp)
    Column(
        modifier.height(76.dp).clip(shape)
            .background(Color.Black.copy(alpha = 0.15f * highlight.pressProgress), shape)
            .then(if (enabled) Modifier.clickable(
                interactionSource = null, indication = null, role = Role.Button, onClick = onClick,
            ).then(highlight.gestureModifier) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        action.systemName?.let { SfIcon(it, null, size = 24.dp, tint = iconTint) }
        Spacer(Modifier.height(6.dp))
        Text(action.title, style = IosTypography.subheadline, color = colors.content,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
