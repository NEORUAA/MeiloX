package com.ljyh.mei.ui.screen.source

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.ui.screen.playlist.component.PlaylistLocateButton

@Composable
internal fun SourceTrackLocateButton(
    viewModel: SourceContentViewModel,
    request: SourceContentRequest,
    state: SourceContentState,
    track: TrackRef,
    listState: LazyListState,
    leadingItems: Int,
    modifier: Modifier = Modifier,
) {
    val revision by viewModel.contentRevision.collectAsState()
    val loaded = remember(state.items, track) { state.items.any { it.ref == track } }
    val canCheckIndex = !loaded && state.hasMore && !state.refreshing
    key(viewModel, request, state.providerVersion, state.accountRevision, state.refreshing, revision, track) {
        var canSearchUnloaded by remember { mutableStateOf(false) }
        LaunchedEffect(canCheckIndex) {
            if (canCheckIndex) {
                // When the source omits a complete index, the button can still search the available pages.
                canSearchUnloaded = viewModel.indexedTrackMembership(request, track) != false
            }
        }
        PlaylistLocateButton(
            listState, track, leadingItems, modifier,
            available = !state.refreshing && (loaded || canCheckIndex && canSearchUnloaded),
        ) { viewModel.locateTrack(request, track) }
    }
}
