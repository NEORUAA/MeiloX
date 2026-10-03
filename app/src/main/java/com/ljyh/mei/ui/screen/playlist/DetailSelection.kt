package com.ljyh.mei.ui.screen.playlist

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.ui.glass.*
import com.ljyh.mei.ui.local.LocalSelectionToolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Stable
class DetailSelection {
    var active by mutableStateOf(false)
    var ids by mutableStateOf(emptySet<String>())
    var generation = 0
        private set
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun start() { generation++; ids = emptySet(); active = true }
    fun finish() { generation++; active = false; ids = emptySet() }
}

/** Selection is independent of loaded rows so paging cannot discard selected items. */
@Composable
fun rememberDetailSelection(key: Any): DetailSelection = remember(key) { DetailSelection() }

@Composable
fun DetailSelectionToolbar(
    selection: DetailSelection,
    onSelectAll: suspend () -> Set<String>,
    onDownload: () -> Unit,
) {
    val toolbar = LocalSelectionToolbar.current
    val selectAll by rememberUpdatedState(onSelectAll)
    val download by rememberUpdatedState(onDownload)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    BackHandler(selection.active) { selection.finish() }
    DisposableEffect(selection.active, toolbar) {
        if (selection.active) toolbar.content.value = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassIconButton(
                    style = GlassSurfaceStyle.Navigation,
                    enabled = !preparing,
                    onClick = {
                        scope.launch {
                            preparing = true
                            val generation = selection.generation
                            try {
                                val ids = selectAll()
                                if (selection.active && selection.generation == generation) selection.ids =
                                    if (ids.isNotEmpty() && selection.ids.containsAll(ids)) selection.ids - ids
                                    else selection.ids + ids
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.load_failed, Toast.LENGTH_SHORT).show()
                            } finally { preparing = false }
                        }
                    },
                ) {
                    SfIcon("checkmark.circle", stringResource(R.string.album_select_all), tint = LocalGlassColors.current.accent)
                }
                GlassIconButton(
                    style = GlassSurfaceStyle.Navigation,
                    enabled = selection.ids.isNotEmpty() && !preparing,
                    onClick = { download() },
                ) {
                    SfIcon(SfSymbol.Download, stringResource(R.string.album_download_selected), tint = LocalGlassColors.current.accent)
                }
            }
        }
        onDispose { toolbar.content.value = null }
    }
}

@Composable
fun detailMenuItems(
    downloadTitle: String,
    subscriptionTitle: String,
    subscribed: Boolean,
    onDownload: (MusicQuality) -> Unit,
    onSelect: () -> Unit,
    onSubscribe: () -> Unit,
    onRefresh: () -> Unit,
): List<IosCascadingMenuItem> {
    val qualityTitles = listOf(
        R.string.track_quality_standard, R.string.track_quality_high,
        R.string.track_quality_lossless, R.string.track_quality_hires,
        R.string.track_quality_surround, R.string.track_quality_spatial, R.string.track_quality_master,
    ).map { stringResource(it) }
    return listOf(
        IosCascadingMenuItem(downloadTitle, "arrow.down.circle", children = MusicQuality.entries.mapIndexed { index, quality ->
            IosCascadingMenuItem(qualityTitles[index], onClick = { onDownload(quality) })
        }),
        IosCascadingMenuItem(stringResource(R.string.album_multi_select), "checklist", onClick = onSelect),
        IosCascadingMenuItem(subscriptionTitle, if (subscribed) "checkmark" else "plus", separatorBefore = true, onClick = onSubscribe),
        IosCascadingMenuItem(stringResource(R.string.album_refresh), "arrow.clockwise", onClick = onRefresh),
    )
}
