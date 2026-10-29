package com.ljyh.mei.ui.screen.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import com.kyant.shapes.Capsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.network.Resource
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.rememberSharedGlassBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlassDimensions
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.comment.component.CommentItem
import com.ljyh.mei.ui.screen.comment.component.CommentSortAction
import com.ljyh.mei.utils.rememberPreference

@Composable
fun CommentScreen(songId: String, viewModel: CommentViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val currentUserId by rememberPreference(UserIdKey, "")
    val cookie by rememberPreference(CookieKey, "")
    val signedInUserId = currentUserId.toLongOrNull()?.takeIf { it > 0 && cookie.isNotBlank() }
    val sortType by viewModel.sortType.collectAsState()
    val total by viewModel.total.collectAsState()
    val composer by viewModel.composer.collectAsState()
    val likes by viewModel.likes.collectAsState()
    val pendingLikes by viewModel.pendingLikes.collectAsState()
    val expandedCommentId by viewModel.expandedCommentId.collectAsState()
    val floorComments by viewModel.floorComments.collectAsState()
    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()
    val listState = key(songId, sortType) { rememberLazyListState() }
    val focusRequester = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val backdrop = rememberLayerBackdrop()
    val background = LocalGlassColors.current.groupedBackground
    // Match floating search: composite the untouched page canvas with the live foreground.
    // The input and send button stay outside their own optical source.
    val baseBackdrop = rememberCanvasBackdrop { drawRect(background) }
    val composerBackdrop = rememberSharedGlassBackdrop(baseBackdrop, backdrop)
    val defaultComposerHeight = LocalGlassDimensions.current.controlHeight
    var composerHeight by remember(defaultComposerHeight) { mutableStateOf(defaultComposerHeight) }
    val blurDistancePx = with(density) { 56.dp.toPx() }
    val topBarBlurProgress by remember(listState, blurDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / blurDistancePx).coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(songId, signedInUserId, cookie) { viewModel.setContext(songId, signedInUserId, cookie) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CommentEvent.Published -> {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    snackbar.showSnackbar(context.getString(R.string.comment_published))
                }
                is CommentEvent.LikeFailed -> snackbar.showSnackbar(context.getString(R.string.comment_action_failed, event.message))
            }
        }
    }
    LaunchedEffect(composer.replyTarget) {
        if (composer.replyTarget != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val keyboardBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val bottomPadding = maxOf(insets.calculateBottomPadding(), keyboardBottom)
    val signIn = { Screen.NeteaseLogin.navigate(navController) }

    val sortAction = CommentSortAction(sortType, viewModel::setSortType)
    Box(Modifier.fillMaxSize()) {
        MeiloXPinnedPage(
            title = if (total > 0) stringResource(R.string.comment_title_count, total) else stringResource(R.string.comment_title),
            bottomPadding = bottomPadding,
            topBarBlurProgress = if (pagingItems.itemCount > 0) topBarBlurProgress else 0f,
            onNavigateBack = { navController.popBackStack() },
            actions = { listOf(sortAction) },
            backgroundColor = background,
        ) { paddingValues ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).trackBackdropPosition(backdrop),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + 8.dp,
                    bottom = paddingValues.calculateBottomPadding() + composerHeight + 16.dp,
                ),
            ) {
                items(count = pagingItems.itemCount, key = { index -> pagingItems.peek(index)?.commentId ?: index }) { index ->
                    val comment = pagingItems[index] ?: return@items
                    val isExpanded = expandedCommentId == comment.commentId
                    CommentItem(
                        comment = comment,
                        isExpanded = isExpanded,
                        floorComments = if (isExpanded) floorComments else Resource.Loading,
                        likes = likes,
                        pendingLikes = pendingLikes,
                        onToggleFloor = viewModel::toggleFloorComments,
                        onRetryFloor = viewModel::retryFloorComments,
                        onReply = { target -> if (signedInUserId == null) signIn() else viewModel.replyTo(target) },
                        onLike = { id, liked, count ->
                            if (signedInUserId == null) signIn() else viewModel.toggleLike(id, liked, count)
                        },
                    )
                }
                if (pagingItems.loadState.refresh is LoadState.Loading || pagingItems.loadState.append is LoadState.Loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (pagingItems.loadState.refresh is LoadState.Error || pagingItems.loadState.append is LoadState.Error) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.load_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = pagingItems::retry) { Text(stringResource(R.string.comment_retry)) }
                        }
                    }
                } else if (pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0) {
                    item {
                        Text(
                            stringResource(R.string.comment_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp)
                .padding(bottom = bottomPadding + 8.dp)
                .onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (signedInUserId == null) {
                    GlassSurface(
                        modifier = Modifier.weight(1f),
                        shape = Capsule(),
                        style = GlassSurfaceStyle.Navigation,
                        backdrop = composerBackdrop,
                    ) {
                        Text(
                            stringResource(R.string.comment_login_hint),
                            style = IosTypography.body,
                            color = LocalGlassColors.current.secondaryContent,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        )
                    }
                    GlasmioButton(
                        onClick = signIn,
                        emphasis = GlassEmphasis.Prominent,
                        style = GlassSurfaceStyle.Navigation,
                        backdrop = composerBackdrop,
                    ) {
                        Text(stringResource(R.string.comment_sign_in))
                    }
                } else {
                    GlassSurface(
                        modifier = Modifier.weight(1f),
                        shape = ContinuousRoundedRectangle(24.dp),
                        style = GlassSurfaceStyle.Navigation,
                        backdrop = composerBackdrop,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            composer.replyTarget?.let { target ->
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.comment_replying_to, target.nickname) + " · " + target.content,
                                        modifier = Modifier.weight(1f),
                                        style = IosTypography.caption,
                                        color = LocalGlassColors.current.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        Modifier.size(44.dp).clickable(enabled = !composer.isSending, role = Role.Button, onClick = viewModel::cancelReply),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        SfIcon(SfSymbol.Close, stringResource(R.string.comment_cancel_reply), size = 16.dp, tint = LocalGlassColors.current.secondaryContent)
                                    }
                                }
                            }
                            val placeholder = stringResource(R.string.comment_placeholder)
                            BasicTextField(
                                value = composer.draft,
                                onValueChange = viewModel::updateDraft,
                                readOnly = composer.isSending,
                                minLines = 1,
                                maxLines = 4,
                                textStyle = IosTypography.body.copy(color = LocalGlassColors.current.content),
                                cursorBrush = SolidColor(LocalGlassColors.current.accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                                    .padding(horizontal = 16.dp, vertical = 13.dp)
                                    .semantics { contentDescription = placeholder },
                                decorationBox = { inner ->
                                    if (composer.draft.isEmpty()) Text(placeholder, style = IosTypography.body, color = LocalGlassColors.current.secondaryContent)
                                    inner()
                                },
                            )
                        }
                    }
                    GlasmioButton(
                        onClick = viewModel::publish,
                        enabled = composer.canSend,
                        emphasis = GlassEmphasis.Prominent,
                        style = GlassSurfaceStyle.Navigation,
                        backdrop = composerBackdrop,
                    ) {
                        if (composer.isSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.send))
                    }
                }
            }
            composer.error?.let { message ->
                Text(stringResource(R.string.comment_send_failed, message), color = MaterialTheme.colorScheme.error, style = IosTypography.caption, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + composerHeight + 16.dp))
    }
}
