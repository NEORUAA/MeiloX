package com.ljyh.mei.ui.screen.comment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.comment.component.CommentItem
import com.ljyh.mei.ui.screen.comment.component.CommentSortAction
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.asPaddingValues
import timber.log.Timber

@Composable
fun CommentScreen(
    songId: String,
    viewModel: CommentViewModel = hiltViewModel()
) {
    val navController = LocalNavController.current
    val sortType by viewModel.sortType.collectAsState()
    val total by viewModel.total.collectAsState()
    val expandedCommentId by viewModel.expandedCommentId.collectAsState()
    val expandedFloorCount by viewModel.expandedFloorCount.collectAsState()
    val floorComments by viewModel.floorComments.collectAsState()

    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()
    val listState = key(songId, sortType) { rememberLazyListState() }
    val blurDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
    val topBarBlurProgress by remember(listState, blurDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / blurDistancePx).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(songId) {
        viewModel.setSongId(songId)
    }

    val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    IosPinnedPage(
        title = if (total > 0) stringResource(R.string.comment_title_count, total)
        else stringResource(R.string.comment_title),
        bottomPadding = bottomPadding,
        // Keep the compact title visible while only the scroll-under blur fades in.
        topBarBlurProgress = if (pagingItems.loadState.refresh is LoadState.NotLoading &&
            pagingItems.itemCount > 0
        ) topBarBlurProgress else 0f,
        onNavigateBack = { navController.popBackStack() },
        actions = {
            if (expandedCommentId != null &&
                expandedFloorCount > 10 &&
                floorComments is com.ljyh.mei.data.network.Resource.Success
            ) {
                GlassIconButton(
                    onClick = {
                        expandedCommentId?.let { viewModel.toggleFloorComments(it, expandedFloorCount) }
                    },
                ) {
                    SfIcon(
                        SfSymbol.ChevronBack,
                        "收起回复",
                        modifier = Modifier.rotate(90f),
                        mirrored = true,
                    )
                }
            }
            CommentSortAction(sortType) { viewModel.setSortType(it) }
        },
    ) { paddingValues ->
        when (val refreshState = pagingItems.loadState.refresh) {
            is LoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is LoadState.Error -> {
                Timber.tag("CommentScreen").d(refreshState.error)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.load_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            is LoadState.NotLoading -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding()
                    )
                ) {
                    items(
                        count = pagingItems.itemCount,
                        key = { index -> pagingItems.peek(index)?.commentId ?: index }
                    ) { index ->
                        val comment = pagingItems[index] ?: return@items
                        val isExpanded = expandedCommentId == comment.commentId

                        CommentItem(
                            comment = comment,
                            isExpanded = isExpanded,
                            floorComments = if (isExpanded) floorComments
                            else com.ljyh.mei.data.network.Resource.Loading,
                            onToggleFloor = { commentId, count ->
                                viewModel.toggleFloorComments(commentId, count)
                            }
                        )
                    }

                    if (pagingItems.loadState.append is LoadState.Loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
