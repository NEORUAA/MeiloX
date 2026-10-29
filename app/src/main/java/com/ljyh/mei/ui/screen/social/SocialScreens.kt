package com.ljyh.mei.ui.screen.social

import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberCanvasBackdrop
import com.kyant.shapes.Capsule
import androidx.compose.foundation.layout.ime
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.model.melox.ShareResourceKind
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.playback.queue.ListQueue
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.glass.GlassSurfaceStyle
import io.github.neoruaa.glasmio.glass.rememberSharedGlassBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.LocalGlassDimensions
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel = hiltViewModel(),
    officialMessages: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val currentUserId by rememberPreference(UserIdKey, "")
    val currentUser = currentUserId.toLongOrNull() ?: 0L
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    LifecycleResumeEffect(officialMessages) {
        viewModel.load(officialMessages)
        onPauseOrDispose {}
    }

    MeiloXPinnedListPage(
        title = stringResource(if (officialMessages) R.string.official_messages else R.string.private_messages),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            val composeAction = if (!officialMessages) listOf(meiloxHeaderAction(
                "compose", stringResource(R.string.message_start), { Screen.MessageContacts.navigate(navController) },
            ) { SfIcon("person.crop.circle.badge.plus", null) }) else emptyList()
            composeAction + meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, null)
            }
        },
    ) {
        if (state.isLoading && state.conversations.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        privateConversationItems(
            conversations = state.conversations,
            currentUserId = currentUser,
            officialMessages = officialMessages,
            onOfficialGroupClick = { Screen.OfficialMessages.navigate(navController) },
            onConversationClick = { userId ->
                Screen.PrivateConversation.navigate(navController) {
                    addPath(userId.toString())
                }
            },
        )
        if (officialMessages && !state.isLoading && state.error == null && state.conversations.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.official_messages_empty),
                    Modifier.padding(16.dp),
                    color = LocalGlassColors.current.secondaryContent,
                )
            }
        }
        if (state.hasMore) {
            item(key = "load-more-conversations") {
                if (state.error == null) {
                    LaunchedEffect(state.conversations.size) { viewModel.loadMore() }
                }
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    } else {
                        GlasmioButton(onClick = viewModel::loadMore) {
                            Text(stringResource(R.string.message_load_more))
                        }
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.privateConversationItems(
    conversations: List<PrivateConversation>,
    currentUserId: Long,
    onConversationClick: (Long) -> Unit,
    onOfficialGroupClick: () -> Unit = {},
    officialMessages: Boolean = false,
) {
    groupedLazyItems(
        items = conversations.sortedByDescending(PrivateConversation::isOfficialGroup),
        key = { "private-conversation-${it.id}" },
        contentType = "private-conversation",
        firstItemTopPadding = 10.dp,
    ) { conversation, index ->
        val participant = conversation.participant(currentUserId)
        if (officialMessages) {
            OfficialConversationRow(conversation, participant) { onConversationClick(participant.id) }
            return@groupedLazyItems
        }
        GlasmioListRow(
            title = if (conversation.isOfficialGroup) {
                stringResource(R.string.official_messages)
            } else participant.displayName,
            subtitle = conversation.summary,
            leading = {
                if (conversation.isOfficialGroup) {
                    OfficialMessagesAvatar(participant.avatarUrl)
                } else {
                    AsyncImage(
                        model = participant.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(50)),
                    )
                }
            },
            trailing = if (conversation.unreadCount > 0) {
                {
                    if (conversation.isOfficialGroup) {
                        Box(Modifier.size(8.dp).background(LocalGlassColors.current.destructive, CircleShape))
                    } else Box(
                        Modifier
                            .size(24.dp)
                            .background(LocalGlassColors.current.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            conversation.unreadCount.coerceAtMost(99).toString(),
                            style = IosTypography.caption,
                            color = Color.White,
                        )
                    }
                }
            } else {
                null
            },
            showTopSeparator = index > 0,
            onClick = {
                if (conversation.isOfficialGroup) onOfficialGroupClick() else onConversationClick(participant.id)
            },
        )
    }
}

@Composable
private fun OfficialConversationRow(
    conversation: PrivateConversation,
    participant: MessageContact,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            AsyncImage(
                model = participant.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape),
            )
            participant.avatarBadgeUrl?.let { badgeUrl ->
                AsyncImage(
                    model = badgeUrl,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).align(Alignment.BottomEnd),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    participant.displayName,
                    Modifier.weight(1f),
                    style = IosTypography.body,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.lastMessageTime > 0) {
                    Text(
                        formatConversationTime(conversation.lastMessageTime),
                        style = IosTypography.caption,
                        color = colors.tertiaryContent,
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.summary,
                    Modifier.weight(1f),
                    style = IosTypography.subheadline,
                    color = colors.secondaryContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.unreadCount > 0) {
                    Box(Modifier.size(8.dp).background(colors.destructive, CircleShape))
                }
            }
        }
    }
}

private fun formatConversationTime(timestamp: Long): String {
    val time = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val sameYear = time.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val pattern = when {
        sameYear && time.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
        sameYear -> "M/d"
        else -> "yyyy/M/d"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

@Composable
fun MessageContactsScreen(viewModel: MessageContactsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, state.contacts) {
        val value = query.trim()
        if (value.isEmpty()) state.contacts else state.contacts.filter {
            it.displayName.contains(value, ignoreCase = true) || it.nickname.contains(value, ignoreCase = true)
        }
    }

    MeiloXPinnedListPage(
        title = stringResource(R.string.message_start),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            listOf(meiloxHeaderAction("refresh", stringResource(R.string.refresh), viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, null)
            })
        },
    ) {
        item {
            GlassSurface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = ContinuousRoundedRectangle(50)) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SfIcon(SfSymbol.Search, null, size = 18.dp)
                            Box(Modifier.weight(1f).padding(start = 9.dp)) {
                                if (query.isEmpty()) {
                                    Text(
                                        stringResource(R.string.message_search_contacts),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        }
                    },
                )
            }
        }
        if (state.isLoading && state.contacts.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (!state.isLoading && state.error == null && filtered.isEmpty()) {
            item { Text(stringResource(R.string.share_no_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        groupedLazyItems(
            items = filtered,
            key = { "message-contact-${it.id}" },
            contentType = "message-contact",
            firstItemTopPadding = 10.dp,
        ) { contact, index ->
            GlasmioListRow(
                title = contact.displayName,
                subtitle = contact.signature?.takeIf(String::isNotBlank),
                leading = {
                    AsyncImage(
                        model = contact.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(50)),
                    )
                },
                showTopSeparator = index > 0,
                onClick = {
                    Screen.PrivateConversation.navigate(navController) {
                        addPath(contact.id.toString())
                    }
                },
            )
        }
    }
}

@Composable
fun ConversationScreen(userId: Long, viewModel: ConversationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current
    val currentUserId by rememberPreference(UserIdKey, "")
    val currentUser = currentUserId.toLongOrNull() ?: 0L
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    var draft by remember { mutableStateOf("") }
    val defaultComposerHeight = LocalGlassDimensions.current.controlHeight
    var composerHeight by remember(defaultComposerHeight) { mutableStateOf(defaultComposerHeight) }
    val density = LocalDensity.current
    val messageListState = rememberLazyListState()
    var hasPositionedToLatest by remember(userId) { mutableStateOf(false) }
    val messagesWithTime = remember(state.messages) {
        val shownTimeLabels = mutableSetOf<String>()
        state.messages.map { message ->
            val timeLabel = formatPrivateMessageTime(message.time)
            val label = timeLabel.takeIf(shownTimeLabels::add)
            message to label
        }
    }
    LaunchedEffect(userId) { viewModel.load(userId) }
    val send = { viewModel.send(draft) { draft = "" } }
    val participantName = remember(state.messages, currentUser) {
        state.messages.asSequence()
            .mapNotNull { message ->
                if (message.fromUser?.id == currentUser) message.toUser else message.fromUser
            }
            .firstOrNull()
            ?.displayName
    }
    val keyboardBottom = with(density) { androidx.compose.foundation.layout.WindowInsets.ime.getBottom(this).toDp() }
    val bottomPadding = maxOf(insets.calculateBottomPadding(), keyboardBottom)
    val conversationBackdrop = rememberLayerBackdrop()
    val pageBackground = LocalGlassColors.current.groupedBackground
    // Use the floating search's canvas-plus-live-page source without recording the composer.
    val baseBackdrop = rememberCanvasBackdrop { drawRect(pageBackground) }
    val composerBackdrop = rememberSharedGlassBackdrop(baseBackdrop, conversationBackdrop)

    LaunchedEffect(
        userId,
        state.isLoading,
        state.messages.size,
        state.messages.lastOrNull()?.id,
        composerHeight,
    ) {
        if (state.isLoading || state.messages.isEmpty()) return@LaunchedEffect
        val lastMessageIndex = state.messages.lastIndex
        if (!hasPositionedToLatest) {
            messageListState.scrollToItem(lastMessageIndex)
            hasPositionedToLatest = true
        } else {
            messageListState.animateScrollToItem(lastMessageIndex)
        }
    }

    val blurDistancePx = with(density) { 56.dp.toPx() }
    val topBarBlurProgress by remember(messageListState, blurDistancePx) {
        derivedStateOf {
            if (messageListState.firstVisibleItemIndex > 0) 1f
            else (messageListState.firstVisibleItemScrollOffset / blurDistancePx).coerceIn(0f, 1f)
        }
    }
    Box(Modifier.fillMaxSize()) {
        MeiloXPinnedPage(
            title = participantName ?: stringResource(R.string.private_conversation),
            backgroundColor = pageBackground,
            topBarBlurProgress = topBarBlurProgress,
            bottomPadding = bottomPadding,
            onNavigateBack = navController::navigateUp,
        ) { contentPadding ->
            LazyColumn(
                state = messageListState,
                modifier = Modifier.fillMaxSize()
                    .layerBackdrop(conversationBackdrop)
                    .trackBackdropPosition(conversationBackdrop),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = contentPadding.calculateTopPadding() + 12.dp,
                    end = 16.dp,
                    // Keep the list layer full-screen while preserving the page's bottom safe
                    // area and the measured floating composer height for the final message.
                    bottom = contentPadding.calculateBottomPadding() + composerHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messagesWithTime, key = { it.first.id }) { (message, timeLabel) ->
                    val outgoing = message.fromUser?.id == currentUser
                    IosMessageBubble(
                        outgoing = outgoing,
                        senderLabel = message.fromUser?.displayName,
                        timeLabel = timeLabel,
                        modifier = Modifier.fillMaxWidth(),
                        onCopy = {
                            setClipboard(
                                context,
                                message.payload.summary,
                                context.getString(R.string.message_copy_content),
                            )
                        },
                    ) { openMenu ->
                        message.payload.resource?.let { resource ->
                            MessageResourceCard(resource, outgoing, onLongClick = openMenu) {
                                when (resource.kind) {
                                    ShareResourceKind.Song -> {
                                        val item = resource.toMediaMetadata().toMediaItem()
                                        playerConnection?.playQueue(
                                            ListQueue("private-message", resource.title, listOf(item.mediaId to item)),
                                        )
                                    }
                                    ShareResourceKind.Playlist -> Screen.PlayList.navigate(navController) {
                                        addPath(resource.id.toString())
                                    }
                                    ShareResourceKind.Album -> Screen.Album.navigate(navController) {
                                        addPath(resource.id.toString())
                                    }
                                }
                            }
                        }
                        if (message.payload.text.isNotBlank()) {
                            Text(
                                message.payload.text,
                                style = IosTypography.subheadline.copy(
                                    color = if (outgoing || LocalGlassColors.current.isDark && !outgoing) Color.White else Color.Black,
                                    fontSize = 15.sp,
                                    lineHeight = 18.sp,
                                ),
                                modifier = Modifier.padding(top = if (message.payload.resource == null) 0.dp else 8.dp),
                            )
                        }
                    }
                }
            }
        }
        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomPadding + 64.dp),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = bottomPadding + 8.dp)
                .onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSurface(
                modifier = Modifier.weight(1f),
                shape = Capsule(),
                style = GlassSurfaceStyle.Navigation,
                backdrop = composerBackdrop,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text(
                                stringResource(R.string.message_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
            GlasmioButton(
                onClick = send,
                enabled = draft.isNotBlank() && !state.isSending,
                emphasis = GlassEmphasis.Prominent,
                style = GlassSurfaceStyle.Navigation,
                backdrop = composerBackdrop,
            ) {
                Text(stringResource(R.string.send))
            }
        }
    }
}

private data class MessageContextAnchor(
    val windowBounds: Rect,
    val screenBounds: Rect,
    val viewportBounds: Rect,
)

/** The body fill and lifted shadow share one union, including the small tail. */
private class MessageBubbleShape(private val outgoing: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            // These coordinates mirror the 24 x 14 dp Figma tail assets. The tail is
            // anchored below the body, rather than being folded into its side edge.
            val tailWidth = 24.dp.toPx()
            val tailHeight = 14.dp.toPx()
            val bodyHeight = (size.height - tailHeight).coerceAtLeast(0f)
            val originX = if (outgoing) size.width - tailWidth else 0f
            val originY = (bodyHeight - 5.dp.toPx()).coerceAtLeast(0f)
            val scale = tailWidth / 24f
            val radius = 20.dp.toPx().coerceAtMost(bodyHeight / 2f)
            val bodyOutline = ContinuousRoundedRectangle(radius).createOutline(
                size = Size(size.width, bodyHeight),
                layoutDirection = layoutDirection,
                density = this,
            )
            val body = Path().apply {
                when (bodyOutline) {
                    is Outline.Rectangle -> addRect(bodyOutline.rect)
                    is Outline.Rounded -> addRoundRect(bodyOutline.roundRect)
                    is Outline.Generic -> addPath(bodyOutline.path)
                }
            }
            val mapX: (Float) -> Float = if (outgoing) {
                { value -> originX + (24f - value) * scale }
            } else {
                { value -> originX + value * scale }
            }
            val tail = Path().apply {
                moveTo(mapX(23.1516f), originY + 5.94366f * scale)
                cubicTo(
                    mapX(17.9097f),
                    originY + 10.1542f * scale,
                    mapX(14.248f),
                    originY + 11.6466f * scale,
                    mapX(8.40167f),
                    originY + 13.3277f * scale,
                )
                cubicTo(
                    mapX(7.79598f),
                    originY + 13.5019f * scale,
                    mapX(7.34855f),
                    originY + 12.7141f * scale,
                    mapX(7.76663f),
                    originY + 12.2425f * scale,
                )
                cubicTo(
                    mapX(10.0935f),
                    originY + 9.61793f * scale,
                    mapX(10.809f),
                    originY + 6.8757f * scale,
                    mapX(8.26052f),
                    originY + 1.29483f * scale,
                )
                cubicTo(
                    mapX(8.03929f),
                    originY + 0.810356f * scale,
                    mapX(8.49546f),
                    originY + 0.275628f * scale,
                    mapX(9.0026f),
                    originY + 0.438333f * scale,
                )
                lineTo(mapX(22.9593f), originY + 4.91612f * scale)
                cubicTo(
                    mapX(23.4101f),
                    originY + 5.06075f * scale,
                    mapX(23.5207f),
                    originY + 5.64718f * scale,
                    mapX(23.1516f),
                    originY + 5.94366f * scale,
                )
                close()
            }
            Outline.Generic(Path.combine(PathOperation.Union, body, tail))
        }
}

/** iMessage-style bubble with a small trailing tail kept inside its composable bounds. */
@Composable
internal fun IosMessageBubble(
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    senderLabel: String? = null,
    timeLabel: String? = null,
    onCopy: (() -> Unit)? = null,
    content: @Composable (openMenu: (() -> Unit)?) -> Unit,
) {
    val colors = LocalGlassColors.current
    val bubbleColor = if (outgoing) {
        Color(0xFF269DFD)
    } else if (colors.isDark) {
        Color(0xFF262629)
    } else {
        Color(0xFFE2E2E3)
    }
    val contentColor = if (outgoing || colors.isDark && !outgoing) Color.White else Color.Black
    val bubbleShape = remember(outgoing) { MessageBubbleShape(outgoing) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    val bubbleScale by animateFloatAsState(
        targetValue = if (contextMenuExpanded) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 260f),
        label = "messageBubbleScale",
    )
    val bubbleLayer = rememberGraphicsLayer()
    var bubbleAnchor by remember { mutableStateOf<MessageContextAnchor?>(null) }
    var menuAnchor by remember { mutableStateOf<MessageContextAnchor?>(null) }
    var previewPresented by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Column(
        modifier = modifier,
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        timeLabel?.let {
            Text(
                it,
                style = IosTypography.caption.copy(
                    color = colors.secondaryContent,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        senderLabel?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = IosTypography.caption.copy(
                    color = colors.secondaryContent,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                ),
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (outgoing) Alignment.TopEnd else Alignment.TopStart,
        ) {
            @Composable
            fun BubbleBody(interactionModifier: Modifier = Modifier, openMenu: (() -> Unit)? = null) {
                Box(
                    modifier = Modifier
                        .widthIn(max = maxWidth * 0.80f)
                        .then(interactionModifier)
                        .onGloballyPositioned { coordinates ->
                            val size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                            val root = coordinates.findRootCoordinates()
                            val anchor = MessageContextAnchor(
                                windowBounds = Rect(coordinates.localToWindow(Offset.Zero), size),
                                screenBounds = Rect(coordinates.localToScreen(Offset.Zero), size),
                                viewportBounds = Rect(
                                    root.localToScreen(Offset.Zero),
                                    Size(root.size.width.toFloat(), root.size.height.toFloat()),
                                ),
                            )
                            bubbleAnchor = anchor
                            // New messages can auto-scroll or resize this row while a menu is open.
                            // Dismiss instead of separating its frozen preview and menu placement.
                            if (contextMenuExpanded && menuAnchor != anchor) contextMenuExpanded = false
                        }
                        .drawWithContent {
                            // Retain only the real bubble pixels. The popup replays this GPU layer,
                            // preserving transparent corners and the tail without sampling the page.
                            if (!previewPresented) {
                                bubbleLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(bubbleLayer)
                            }
                        }
                        .background(bubbleColor, bubbleShape)
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                        .padding(bottom = 14.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Column(content = { content(openMenu) })
                    }
                }
            }
            if (onCopy == null) {
                BubbleBody()
            } else {
                GlasmioMenu(
                    expanded = contextMenuExpanded,
                    onExpandedChange = { expanded ->
                        if (!expanded) {
                            contextMenuExpanded = false
                        } else {
                            val anchor = bubbleAnchor
                            // A reopening during collapse must retain the same source lease.
                            if (anchor != null && (!previewPresented || menuAnchor == anchor)) {
                                menuAnchor = anchor
                                contextMenuExpanded = true
                            }
                        }
                    },
                    itemCount = 1,
                    keepAnchorVisible = true,
                    forceBelowAnchor = true,
                    fullScreen = true,
                    morphFromAnchor = false,
                    // A message is a lifted preview, not the source of the menu surface morph.
                    externalAnchorBounds = menuAnchor?.windowBounds ?: bubbleAnchor?.windowBounds ?: Rect.Zero,
                    overlayContent = { progress ->
                        DisposableEffect(Unit) {
                            previewPresented = true
                            onDispose { previewPresented = false }
                        }
                        var popupScreenOrigin by remember { mutableStateOf(Offset.Zero) }
                        Box(
                            Modifier.fillMaxSize().zIndex(-2f)
                                .testTag("messageContextScrim")
                                .onGloballyPositioned { popupScreenOrigin = it.localToScreen(Offset.Zero) }
                                .drawBehind {
                                    drawRect(Color.Black.copy(alpha = (if (colors.isDark) 0.20f else 0.08f) * progress()))
                                },
                        )
                        menuAnchor?.screenBounds?.let { bounds ->
                            Box(
                                Modifier.zIndex(-1f)
                                    .absoluteOffset {
                                        IntOffset(
                                            (bounds.left - popupScreenOrigin.x).roundToInt(),
                                            (bounds.top - popupScreenOrigin.y).roundToInt(),
                                        )
                                    }
                                    .size(
                                        width = with(density) { bounds.width.toDp() },
                                        height = with(density) { bounds.height.toDp() },
                                    )
                                    .testTag("messageContextPreview")
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(if (outgoing) 1f else 0f, 0.5f)
                                        scaleX = bubbleScale
                                        scaleY = bubbleScale
                                    }
                                    .dropShadow(bubbleShape) {
                                        radius = 24.dp.toPx()
                                        offset = Offset(0f, 8.dp.toPx())
                                        color = Color.Black
                                        alpha = (if (colors.isDark) 0.48f else 0.28f) * progress()
                                    }
                                    .drawWithContent { drawLayer(bubbleLayer) },
                            )
                        }
                    },
                    anchor = { openMenu ->
                        BubbleBody(
                            Modifier.combinedClickable(
                                interactionSource = null,
                                indication = null,
                                onClick = {},
                                onLongClick = openMenu,
                            ),
                            openMenu = openMenu,
                        )
                    },
                ) { childBackdrop, close ->
                    GlasmioMenuItem(
                        title = stringResource(R.string.message_copy_content),
                        systemName = "document.on.clipboard",
                        backdrop = childBackdrop,
                        onClick = {
                            onCopy()
                            close()
                        },
                    )
                }
            }
        }
    }
}

private fun formatPrivateMessageTime(timestamp: Long): String {
    val timestampMillis = if (timestamp in 1 until 100_000_000_000L) {
        timestamp * 1000L
    } else {
        timestamp
    }
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))
}

@Composable
internal fun MessageResourceCard(
    resource: ShareResource,
    outgoing: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            interactionSource = null,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = resource.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(ContinuousRoundedRectangle(10.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
            Text(resource.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                resource.subtitle.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (outgoing) Color.White.copy(alpha = .76f)
                else LocalGlassColors.current.secondaryContent,
                maxLines = 1,
            )
        }
        SfIcon(
            "chevron.right",
            null,
            size = 15.dp,
            tint = if (outgoing) Color.White else LocalGlassColors.current.content,
        )
    }
}

private fun ShareResource.toMediaMetadata() = MediaMetadata(
    id = id,
    title = title,
    coverUrl = artworkUrl.orEmpty(),
    artists = subtitle.orEmpty().split(" / ").filter(String::isNotBlank).map {
        MediaMetadata.Artist(it.hashCode().toLong(), it)
    },
    duration = 0,
    album = MediaMetadata.Album(0, ""),
)
