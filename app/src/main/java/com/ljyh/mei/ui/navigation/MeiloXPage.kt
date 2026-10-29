package com.ljyh.mei.ui.navigation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.core.*
import io.github.neoruaa.glasmio.glass.*
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.*
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

/** A mounted entry publishes chrome; the navigation host owns only its button surfaces. */
internal class MeiloXPageHeader {
    var spec by mutableStateOf(GlasmioHeaderSpec(""))
    var viewport by mutableStateOf<BottomNavigationViewport?>(null)
    var mounted = 0
}

internal class MeiloXPageHeaders {
    private val entries = mutableMapOf<Any, MeiloXPageHeader>()
    private var retained = emptySet<Any>()
    fun entry(key: Any): MeiloXPageHeader = entries.getOrPut(key) { MeiloXPageHeader() }
    fun retain(keys: Set<Any>) {
        retained = keys
        prune()
    }
    fun unmount(header: MeiloXPageHeader) { header.mounted--; prune() }
    private fun prune() { entries.entries.removeAll { (key, value) -> key !in retained && value.mounted == 0 } }
}

private val LocalMeiloXPageHeader = staticCompositionLocalOf<MeiloXPageHeader?> { null }

@Composable
internal fun registerMeiloXNavigationViewport(listState: LazyListState) {
    val viewport = remember(listState) {
        object : BottomNavigationViewport {
            override val rangePx: Float get() = listState.scrollIndicatorState?.let {
                (it.contentSize - it.viewportSize).coerceAtLeast(0).toFloat()
            } ?: 0f
            override val distanceToStartPx: Float get() =
                if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat()
                else Float.POSITIVE_INFINITY
            override val atStart: Boolean get() = !listState.canScrollBackward
            override val atEnd: Boolean get() = !listState.canScrollForward
            override val isScrolling: Boolean get() = listState.isScrollInProgress
        }
    }
    registerMeiloXNavigationViewport(viewport)
}

@Composable
internal fun registerMeiloXNavigationViewport(gridState: LazyGridState) {
    val viewport = remember(gridState) {
        object : BottomNavigationViewport {
            override val rangePx: Float get() = gridState.scrollIndicatorState?.let {
                (it.contentSize - it.viewportSize).coerceAtLeast(0).toFloat()
            } ?: 0f
            override val distanceToStartPx: Float get() =
                if (gridState.firstVisibleItemIndex == 0) gridState.firstVisibleItemScrollOffset.toFloat()
                else Float.POSITIVE_INFINITY
            override val atStart: Boolean get() = !gridState.canScrollBackward
            override val atEnd: Boolean get() = !gridState.canScrollForward
            override val isScrolling: Boolean get() = gridState.isScrollInProgress
        }
    }
    registerMeiloXNavigationViewport(viewport)
}

@Composable
private fun registerMeiloXNavigationViewport(viewport: BottomNavigationViewport) {
    val header = LocalMeiloXPageHeader.current
    DisposableEffect(header, viewport) {
        header?.viewport = viewport
        onDispose {
            if (header != null && header.viewport === viewport) header.viewport = null
        }
    }
}

@Composable
internal fun MeiloXPageEntry(
    key: MeiRoute,
    headers: MeiloXPageHeaders,
    content: @Composable () -> Unit,
) {
    // Glasmio's clock publishes NavEntry.contentKey, which this app sets to the route string.
    val header = remember(headers, key.route) { headers.entry(key.route) }
    DisposableEffect(header) {
        header.mounted++
        onDispose { headers.unmount(header) }
    }
    CompositionLocalProvider(LocalMeiloXPageHeader provides header) {
        Box(Modifier.fillMaxSize().testTag("MeiloXPage:${key.route}")) {
            content()
            GlasmioPageTitle(key.route, Modifier.testTag("MeiloXPageTitle:${key.route}"))
        }
    }
}

/** Capture page typography/colors without replacing the shared host's backdrop or menu context. */
@Composable
fun meiloxHeaderAction(
    key: Any,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    visualKey: Any = key,
    menuAnchor: GlasmioMenuAnchorState? = null,
    content: @Composable () -> Unit,
): GlasmioHeaderAction {
    val colors = LocalGlassColors.current
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    return GlasmioHeaderAction(key, visualKey, contentDescription, onClick, enabled, menuAnchor) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
            CompositionLocalProvider(
                LocalGlassColors provides colors,
                LocalGlassContentColor provides colors.content,
                LocalContentColor provides colors.content,
            ) { content() }
        }
    }
}

@Composable
fun MeiloXPinnedPage(
    title: String,
    bottomPadding: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    navigateBackContentDescription: String = stringResource(R.string.navigation_back),
    actions: @Composable () -> List<GlasmioHeaderAction> = { emptyList() },
    actionsStyle: GlasmioToolbarActionStyle = GlasmioToolbarActionStyle.Merged,
    actionsSeparationProgress: Float? = null,
    collapseProgress: Float = 1f,
    backgroundColor: Color? = null,
    topBarBlurProgress: Float = 0f,
    backgroundBrush: Brush? = null,
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val header = LocalMeiloXPageHeader.current
    val colors = LocalGlassColors.current
    val currentCollapse = rememberUpdatedState(collapseProgress)
    val currentEdge = rememberUpdatedState(topBarBlurProgress)
    val scrollState = remember {
        GlasmioHeaderScrollState({ currentCollapse.value }, { currentEdge.value })
    }
    val leading = onNavigateBack?.let { navigateBack ->
        listOf(GlasmioHeaderGroup("navigation", listOf(meiloxHeaderAction(
            key = "back",
            contentDescription = navigateBackContentDescription,
            onClick = navigateBack,
        ) { SfIcon(SfSymbol.ChevronBack, null, mirrored = true) })))
    }.orEmpty()
    val trailingActions = actions()
    val spec = GlasmioHeaderSpec(
        title = title,
        subtitle = subtitle,
        leading = leading,
        trailing = if (trailingActions.isEmpty()) emptyList() else listOf(
            GlasmioHeaderGroup("actions", trailingActions, actionsStyle),
        ),
        scrollState = scrollState,
        colors = colors,
    )
    SideEffect {
        if (header != null) {
            header.spec = spec
        }
    }
    if (header == null) {
        // Standalone previews and isolated component tests keep a local toolbar.
        GlasmioPinnedPage(
            title = title, subtitle = subtitle, bottomPadding = bottomPadding, modifier = modifier,
            onNavigateBack = onNavigateBack, navigateBackContentDescription = navigateBackContentDescription,
            actions = {
                trailingActions.forEach { action ->
                    key(action.key) {
                        GlasmioIconButton(action.onClick, enabled = action.enabled,
                            modifier = action.menuAnchor?.let { Modifier.glasmioMenuAnchor(it) } ?: Modifier,
                        ) { action.content() }
                    }
                }
            },
            actionsStyle = actionsStyle, actionsSeparationProgress = actionsSeparationProgress,
            collapseProgress = collapseProgress, backgroundColor = backgroundColor,
            topBarBlurProgress = topBarBlurProgress, backgroundBrush = backgroundBrush,
            background = background, content = content,
        )
        return
    }
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val padding = PaddingValues(
        start = IosPageHorizontalPadding, top = status + 72.dp,
        end = IosPageHorizontalPadding, bottom = bottomPadding + 24.dp,
    )
    GlasmioPageBody(
        contentPadding = padding, modifier = modifier,
        backgroundColor = backgroundColor ?: colors.groupedBackground,
        backgroundBrush = backgroundBrush, background = background,
        topBarBlurProgress = topBarBlurProgress, content = content,
    )
}

/** The same page chrome remains available while a retained route reconnects to its service. */
@Composable
fun MeiloXLoadingPage(title: String, onNavigateBack: (() -> Unit)? = null) {
    MeiloXPinnedPage(title = title, onNavigateBack = onNavigateBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = LocalGlassColors.current.accent)
        }
    }
}

@Composable
fun MeiloXPinnedListPage(
    title: String,
    bottomPadding: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    horizontalContentPadding: Dp = IosPageHorizontalPadding,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    listState: LazyListState = rememberLazyListState(),
    showsLargeTitle: Boolean = true,
    collapseEnterBreakpoint: Dp = GlasmioHeaderCollapseDefaults.EnterBreakpoint,
    collapseExitBreakpoint: Dp = GlasmioHeaderCollapseDefaults.ExitBreakpoint,
    collapseAnimationSpec: AnimationSpec<Float> = GlasmioHeaderCollapseDefaults.Spring,
    largeTitleHorizontalPadding: Dp = 4.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    onNavigateBack: (() -> Unit)? = null,
    navigateBackContentDescription: String = stringResource(R.string.navigation_back),
    actions: @Composable () -> List<GlasmioHeaderAction> = { emptyList() },
    actionsStyle: GlasmioToolbarActionStyle = GlasmioToolbarActionStyle.Merged,
    actionsSeparationProgress: Float? = null,
    backgroundColor: Color? = null,
    backgroundBrush: Brush? = null,
    bottomPaddingValues: PaddingValues? = null,
    content: LazyListScope.() -> Unit,
) {
    registerMeiloXNavigationViewport(listState)
    val animatedCollapse = rememberGlasmioHeaderCollapse(listState,
        collapseEnterBreakpoint, collapseExitBreakpoint, collapseAnimationSpec)
    val collapse = if (showsLargeTitle) animatedCollapse else 1f
    MeiloXPinnedPage(
        title = title, subtitle = subtitle, bottomPadding = bottomPadding, modifier = modifier,
        onNavigateBack = onNavigateBack, navigateBackContentDescription = navigateBackContentDescription,
        actions = actions, actionsStyle = actionsStyle, actionsSeparationProgress = actionsSeparationProgress,
        collapseProgress = collapse, topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState, collapseEnterBreakpoint),
        backgroundColor = backgroundColor, backgroundBrush = backgroundBrush,
    ) { padding ->
        val listPadding = remember(padding, horizontalContentPadding, bottomPaddingValues) {
            object : PaddingValues by padding {
                override fun calculateLeftPadding(layoutDirection: LayoutDirection) = horizontalContentPadding
                override fun calculateRightPadding(layoutDirection: LayoutDirection) = horizontalContentPadding
                override fun calculateBottomPadding() = padding.calculateBottomPadding() +
                    (bottomPaddingValues?.calculateBottomPadding() ?: 0.dp)
            }
        }
        key(listState) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = listPadding, verticalArrangement = verticalArrangement,
            ) {
                if (showsLargeTitle) item(key = "ios-large-title:$title") {
                    Column(Modifier.fillMaxWidth().graphicsLayer {
                        alpha = 1f - collapse
                        scaleX = 1f - .04f * collapse
                        scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                    }.blur(6.dp * collapse).offset(y = (-10).dp)
                        .padding(horizontal = largeTitleHorizontalPadding, vertical = 6.dp),
                    ) {
                        Text(title, style = IosTypography.largeTitle, fontWeight = FontWeight.Bold,
                            color = LocalGlassColors.current.content)
                        subtitle?.let { Text(it, style = IosTypography.subheadline,
                            color = LocalGlassColors.current.secondaryContent, modifier = Modifier.padding(top = 3.dp)) }
                    }
                }
                content()
            }
        }
    }
}
