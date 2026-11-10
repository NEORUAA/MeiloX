package com.ljyh.mei.ui.screen.playlist.component

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.source.api.TrackRef
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val LocateButtonIdleMillis = 3_000L
private const val LocateLayoutTimeoutMillis = 10_000L

@Composable
internal fun PlaylistLocateButton(
    listState: LazyListState,
    track: TrackRef,
    leadingItems: Int,
    modifier: Modifier = Modifier,
    available: Boolean = true,
    findTrackIndex: suspend () -> Int?,
) {
    val context = LocalContext.current
    var visible by remember(listState) { mutableStateOf(true) }
    var locating by remember(listState, track, leadingItems) { mutableStateOf(false) }
    val resolveIndex by rememberUpdatedState(findTrackIndex)

    LaunchedEffect(listState, available) {
        if (!available) return@LaunchedEffect
        visible = true
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                visible = true
            } else {
                delay(LocateButtonIdleMillis)
                visible = false
            }
        }
    }

    LaunchedEffect(locating, listState, track, leadingItems) {
        if (!locating) return@LaunchedEffect
        try {
            val index = resolveIndex()?.plus(leadingItems)
            val layout = index?.let { targetIndex ->
                // The source can publish the row before the next lazy list layout.
                withTimeoutOrNull(LocateLayoutTimeoutMillis) {
                    snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > targetIndex }
                }
            }
            if (index == null || layout == null) {
                Toast.makeText(context, R.string.playlist_locate_track_failed, Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
            val usableHeight = (layout.viewportEndOffset - layout.afterContentPadding).coerceAtLeast(0)
            listState.animateScrollToItem(index, scrollOffset = -usableHeight / 3)
        } finally {
            locating = false
        }
    }

    if (available && visible || locating) {
        GlasmioIconButton(
            onClick = { locating = true },
            modifier = modifier,
            enabled = !locating,
        ) {
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                SfIcon("scope", stringResource(R.string.playlist_locate_current_track))
            }
        }
    }
}
