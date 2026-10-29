package com.ljyh.mei.ui.component.player.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.R
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.PrivateFmMode
import com.ljyh.mei.playback.PrivateFmScene
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.glasmioGroupedListSeparator
import kotlinx.coroutines.CancellationException

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PrivateFmModeSelector(
    connection: PlayerConnection,
    backdrop: Backdrop,
    onChooseScene: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by connection.privateFmMode.collectAsStateWithLifecycle()
    val scene by connection.privateFmScene.collectAsStateWithLifecycle()
    val busy by connection.service.queueManager.privateFmBusy.collectAsStateWithLifecycle()
    val currentChooseScene by rememberUpdatedState(onChooseScene)
    val layoutDirection = LocalLayoutDirection.current
    val items = listOf(
        PrivateFmMode.DEFAULT to stringResource(R.string.private_fm_default_mode),
        PrivateFmMode.FAMILIAR to stringResource(R.string.private_fm_familiar_mode),
        PrivateFmMode.EXPLORE to stringResource(R.string.private_fm_explore_mode),
        PrivateFmMode.SCENE to (scene?.title ?: stringResource(R.string.private_fm_scene_mode)),
    )
    val sceneIndex = items.indexOfFirst { it.first == PrivateFmMode.SCENE }

    Column(modifier) {
        GlasmioSegmentedControl(
            items = items,
            selected = mode,
            onSelected = { selected ->
                if (selected == PrivateFmMode.SCENE) currentChooseScene()
                else connection.setPrivateFmMode(selected)
            },
            modifier = Modifier.fillMaxWidth().pointerInput(mode, layoutDirection, items.size, sceneIndex) {
                // The selected indicator owns its drag surface; retain a tap to choose another scene.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val logicalX = if (layoutDirection == LayoutDirection.Ltr) down.position.x
                        else size.width - down.position.x
                    val index = (logicalX / (size.width.toFloat() / items.size).coerceAtLeast(1f))
                        .toInt().coerceIn(0, items.lastIndex)
                    if (mode == PrivateFmMode.SCENE && index == sceneIndex) {
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                            currentChooseScene()
                        }
                    }
                }
            },
            backdrop = backdrop,
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PrivateFmSceneSheet(connection: PlayerConnection, onDismissRequest: () -> Unit) {
    val mode by connection.privateFmMode.collectAsStateWithLifecycle()
    val selectedScene by connection.privateFmScene.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scenes by remember { mutableStateOf<List<PrivateFmScene>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val dismissal = rememberMeiloXSheetDismissal(onDismissRequest)
    val title = stringResource(R.string.private_fm_scene_mode)

    LaunchedEffect(connection, reload) {
        loading = true
        error = null
        try {
            scenes = connection.loadPrivateFmScenes()
            check(scenes.isNotEmpty()) { context.getString(R.string.private_fm_no_scenes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }

    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = title,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(title, actions = {
                GlasmioSheetToolbarButton({ dismissal.requestDismiss() }) {
                    SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (loading) item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error?.let { message -> item {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)
                    GlasmioButton({ reload++ }) { Text(stringResource(R.string.retry)) }
                }
            } }
            if (scenes.isNotEmpty()) item(key = "private-fm-scenes") {
                GlasmioGroupedList(Modifier.selectableGroup()) {
                    scenes.forEachIndexed { index, option ->
                        key(option.code) {
                            PrivateFmSceneRow(
                                scene = option,
                                selected = mode == PrivateFmMode.SCENE && selectedScene?.code == option.code,
                                showSeparator = index > 0,
                                onClick = {
                                    connection.setPrivateFmScene(option)
                                    dismissal.requestDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateFmSceneRow(
    scene: PrivateFmScene,
    selected: Boolean,
    showSeparator: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .glasmioGroupedListSeparator(colors.separator, horizontalInset = 20.dp, visible = showSeparator)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(scene.title, modifier = Modifier.weight(1f), style = IosTypography.body,
            color = colors.content, softWrap = true)
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) SfIcon("checkmark", null, size = 17.dp, tint = colors.accent)
        }
    }
}
