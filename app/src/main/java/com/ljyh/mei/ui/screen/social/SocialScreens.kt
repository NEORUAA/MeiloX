package com.ljyh.mei.ui.screen.social

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSurface
import com.ljyh.mei.ui.glass.GlassSurfaceStyle
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosMenuItem
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.IosPopupMenu
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.LocalGlassDimensions
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsScreen(viewModel: ConversationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val currentUserId by rememberPreference(UserIdKey, "")
    val currentUser = currentUserId.toLongOrNull() ?: 0L
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    IosPinnedListPage(
        title = stringResource(R.string.private_messages),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            GlassIconButton(onClick = { Screen.MessageContacts.navigate(navController) }) {
                SfIcon("person.crop.circle.badge.plus", stringResource(R.string.message_start))
            }
            GlassIconButton(viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        if (state.isLoading && state.conversations.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.conversations.isNotEmpty()) {
            item(key = "private-conversation-group") {
                IosGroupedList {
                    state.conversations.forEachIndexed { index, conversation ->
                        val participant = conversation.participant(currentUser)
                        IosListRow(
                            title = participant.displayName,
                            subtitle = conversation.summary,
                            leading = {
                                AsyncImage(
                                    model = participant.avatarUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(50)),
                                )
                            },
                            trailing = if (conversation.unreadCount > 0) {
                                {
                                    Box(
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
                                Screen.PrivateConversation.navigate(navController) {
                                    addPath(participant.id.toString())
                                }
                            },
                        )
                    }
                }
            }
        }
    }
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

    IosPinnedListPage(
        title = stringResource(R.string.message_start),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            GlassIconButton(viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        item {
            GlassSurface(Modifier.fillMaxWidth(), shape = ContinuousRoundedRectangle(50)) {
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
        if (filtered.isNotEmpty()) {
            item(key = "message-contact-group") {
                IosGroupedList {
                    filtered.forEachIndexed { index, contact ->
                        IosListRow(
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
    var contextMenuMessageId by remember { mutableStateOf<Long?>(null) }
    var contextMenuBounds by remember { mutableStateOf<Rect?>(null) }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (contextMenuMessageId == null) {
            0f
        } else if (LocalGlassColors.current.isDark) {
            0.20f
        } else {
            0.08f
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 360f),
        label = "messageContextMenuScrim",
    )
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
    val bottomPadding = insets.calculateBottomPadding()

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

    IosPinnedPage(
        title = participantName ?: stringResource(R.string.private_conversation),
        bottomPadding = bottomPadding,
        onNavigateBack = navController::navigateUp,
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = messageListState,
                modifier = Modifier.fillMaxSize(),
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
                        onContextMenuVisibilityChanged = { visible ->
                            if (visible) {
                                contextMenuMessageId = message.id
                            } else if (contextMenuMessageId == message.id) {
                                contextMenuMessageId = null
                                contextMenuBounds = null
                            }
                        },
                        onContextMenuBoundsChanged = { bounds ->
                            if (contextMenuMessageId == message.id) {
                                contextMenuBounds = bounds
                            }
                        },
                    ) {
                        message.payload.resource?.let { resource ->
                            MessageResourceCard(resource, outgoing) {
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
                                    letterSpacing = 0.6.sp,
                                ),
                                modifier = Modifier.padding(top = if (message.payload.resource == null) 0.dp else 8.dp),
                            )
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
                    .padding(horizontal = 12.dp)
                    .padding(bottom = bottomPadding + 8.dp)
                    .onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSurface(
                    modifier = Modifier.weight(1f),
                    shape = ContinuousRoundedRectangle(50),
                    style = GlassSurfaceStyle.Navigation,
                    navigationSurfaceColor = Color.Transparent,
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
                GlassButton(
                    onClick = send,
                    enabled = draft.isNotBlank() && !state.isSending,
                    emphasis = GlassEmphasis.Prominent,
                    style = GlassSurfaceStyle.Navigation,
                ) {
                    Text(stringResource(R.string.send))
                }
            }
            if (scrimAlpha > 0.001f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = scrimAlpha))
                            contextMenuBounds?.let { bounds ->
                                drawRoundRect(
                                    color = Color.Transparent,
                                    topLeft = Offset(bounds.left, bounds.top),
                                    size = Size(bounds.width, bounds.height),
                                    blendMode = BlendMode.Clear,
                                )
                            }
                        },
                )
            }
        }
    }
}

/** iMessage-style bubble with a small trailing tail kept inside its composable bounds. */
@Composable
private fun IosMessageBubble(
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    senderLabel: String? = null,
    timeLabel: String? = null,
    onCopy: (() -> Unit)? = null,
    onContextMenuVisibilityChanged: (Boolean) -> Unit = {},
    onContextMenuBoundsChanged: (Rect?) -> Unit = {},
    content: @Composable () -> Unit,
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
    var contextMenuExpanded by remember { mutableStateOf(false) }
    val bubbleScale by animateFloatAsState(
        targetValue = if (contextMenuExpanded) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 260f),
        label = "messageBubbleScale",
    )
    LaunchedEffect(contextMenuExpanded) {
        onContextMenuVisibilityChanged(contextMenuExpanded)
        if (!contextMenuExpanded) onContextMenuBoundsChanged(null)
    }
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
                    letterSpacing = 1.sp,
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
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
            )
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (outgoing) Alignment.TopEnd else Alignment.TopStart,
        ) {
            @Composable
            fun BubbleBody(interactionModifier: Modifier = Modifier) {
                Box(
                    modifier = Modifier
                        // Keep the scale layer outside the drawing and padding modifiers so the
                        // continuous background and tail deform together with the text.
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(if (outgoing) 1f else 0f, 0.5f)
                            scaleX = bubbleScale
                            scaleY = bubbleScale
                        }
                        .widthIn(max = maxWidth * 0.80f)
                        .drawBehind {
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
                        drawContext.canvas.drawOutline(
                            bodyOutline,
                            Paint().apply { color = bubbleColor },
                        )
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
                        drawPath(tail, bubbleColor)
                        }
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                        .padding(bottom = 14.dp)
                        .onGloballyPositioned { coordinates ->
                            if (contextMenuExpanded) {
                                onContextMenuBoundsChanged(coordinates.boundsInRoot())
                            }
                        }
                        .then(interactionModifier),
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Column(content = { content() })
                    }
                }
            }
            if (onCopy == null) {
                BubbleBody()
            } else {
                IosPopupMenu(
                    expanded = contextMenuExpanded,
                    onExpandedChange = { contextMenuExpanded = it },
                    itemCount = 1,
                    keepAnchorVisible = true,
                    forceBelowAnchor = true,
                    anchor = { openMenu ->
                        BubbleBody(
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = openMenu,
                            ),
                        )
                    },
                ) { childBackdrop, close ->
                    IosMenuItem(
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
private fun MessageResourceCard(resource: ShareResource, outgoing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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

private fun PrivateConversation.participant(currentUserId: Long): MessageContact =
    fromUser?.takeIf { it.id != currentUserId }
        ?: toUser?.takeIf { it.id != currentUserId }
        ?: fromUser
        ?: toUser
        ?: MessageContact(0, "NetEase user", null, null, null)
