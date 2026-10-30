package com.ljyh.mei.ui.screen.social

import com.ljyh.mei.ui.component.sheet.MeiloXBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.glasmioGroupedListBackground
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.model.melox.ShareResourceKind
import com.ljyh.mei.data.repository.MeloXRepository
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioHeaderScrollEdge
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private enum class NeteaseShareMode { Menu, PrivateMessage, Timeline }

data class NeteaseShareUiState(
    val contacts: List<MessageContact> = emptyList(),
    val isLoadingContacts: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class NeteaseShareViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NeteaseShareUiState())
    val state = _state.asStateFlow()

    fun loadContacts() {
        if (_state.value.contacts.isNotEmpty() || _state.value.isLoadingContacts) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingContacts = true, error = null)
            runCatching {
                val profile = repository.accountProfile()
                repository.messageContacts(profile.id)
            }.onSuccess {
                _state.value = _state.value.copy(contacts = it, isLoadingContacts = false)
            }.onFailure {
                _state.value = _state.value.copy(isLoadingContacts = false, error = it.message)
            }
        }
    }

    fun sendPrivate(
        resource: ShareResource,
        recipients: Set<Long>,
        message: String,
        onSent: () -> Unit,
    ) {
        if (recipients.isEmpty() || _state.value.isSending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSending = true, error = null)
            runCatching { repository.sendPrivateResource(resource, recipients.toList(), message.trim()) }
                .onSuccess {
                    _state.value = _state.value.copy(isSending = false)
                    onSent()
                }
                .onFailure { _state.value = _state.value.copy(isSending = false, error = it.message) }
        }
    }

    fun shareTimeline(resource: ShareResource, message: String, onSent: () -> Unit) {
        if (_state.value.isSending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSending = true, error = null)
            runCatching { repository.shareToTimeline(resource, message.trim()) }
                .onSuccess {
                    _state.value = _state.value.copy(isSending = false)
                    onSent()
                }
                .onFailure { _state.value = _state.value.copy(isSending = false, error = it.message) }
        }
    }
}

@Composable
fun NeteaseShareSheet(
    metadata: MediaMetadata,
    onDismiss: () -> Unit,
    viewModel: NeteaseShareViewModel = hiltViewModel(),
) {
    val resource = remember(metadata) {
        ShareResource(
            kind = ShareResourceKind.Song,
            id = metadata.id,
            title = metadata.title,
            subtitle = metadata.artists.joinToString(" / ") { it.name },
            artworkUrl = metadata.coverUrl,
        )
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var mode by remember { mutableStateOf(NeteaseShareMode.Menu) }
    var message by remember { mutableStateOf("") }
    var selectedContactIds by remember { mutableStateOf(emptySet<Long>()) }

    LaunchedEffect(mode) {
        if (mode == NeteaseShareMode.PrivateMessage) viewModel.loadContacts()
    }

    val listState = rememberLazyListState()
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val sheetTitle = stringResource(when (mode) {
        NeteaseShareMode.Menu -> R.string.share_song
        NeteaseShareMode.PrivateMessage -> R.string.netease_private_message
        NeteaseShareMode.Timeline -> R.string.netease_timeline_share
    })
    LaunchedEffect(mode) { listState.scrollToItem(0) }
    MeiloXBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = sheetTitle,
        topBarBlurProgress = rememberGlasmioHeaderScrollEdge(listState),
        topBar = {
            GlasmioSheetTopToolbar(
                title = sheetTitle,
                navigation = if (mode != NeteaseShareMode.Menu) ({
                    GlasmioSheetToolbarButton(onClick = { mode = NeteaseShareMode.Menu }) {
                        SfIcon(SfSymbol.ChevronBack, stringResource(R.string.navigation_back), mirrored = true)
                    }
                }) else null,
                actions = {
                    GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                        SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = if (mode == NeteaseShareMode.Menu) padding.calculateBottomPadding() + 16.dp else 16.dp,
            ),
        ) {
            item(key = "resource") { Box(Modifier.padding(bottom = 16.dp)) { ShareResourcePreview(resource) } }
            when (mode) {
                NeteaseShareMode.Menu -> item(key = "share-modes") {
                    GlasmioGroupedList {
                        ShareModeButton("paperplane", R.string.netease_private_message, showTopSeparator = false) {
                            mode = NeteaseShareMode.PrivateMessage
                        }
                        ShareModeButton("arrowshape.turn.up.right", R.string.netease_timeline_share) {
                            mode = NeteaseShareMode.Timeline
                        }
                        ShareModeButton("square.and.arrow.up", R.string.system_share) {
                            val artists = metadata.artists.joinToString(" / ") { it.name }
                            val text = buildString {
                                append(metadata.title)
                                if (artists.isNotBlank()) append(" — ").append(artists)
                                if (!metadata.isLocal) append("\nhttps://music.163.com/song?id=").append(metadata.id)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, metadata.title)
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    },
                                    context.getString(R.string.system_share),
                                ),
                            )
                        }
                    }
                }
                NeteaseShareMode.PrivateMessage -> {
                    item(key = "message") {
                        Box(Modifier.padding(bottom = 16.dp)) {
                            ShareMessageField(message, { message = it }, R.string.share_message_optional)
                        }
                    }
                    item(key = "recipients") {
                        Text(stringResource(R.string.share_recipients_count, selectedContactIds.size),
                            modifier = Modifier.padding(bottom = 12.dp), fontWeight = FontWeight.SemiBold)
                    }
                    when {
                        state.isLoadingContacts -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        state.contacts.isEmpty() && state.error == null -> item(key = "empty") {
                            Text(stringResource(R.string.share_no_contacts))
                        }
                        else -> itemsIndexed(state.contacts, key = { _, contact -> "contact:${contact.id}" }) { index, contact ->
                            val shape = ContinuousRoundedRectangle(
                                topStart = if (index == 0) 26.dp else 0.dp,
                                topEnd = if (index == 0) 26.dp else 0.dp,
                                bottomStart = if (index == state.contacts.lastIndex) 26.dp else 0.dp,
                                bottomEnd = if (index == state.contacts.lastIndex) 26.dp else 0.dp,
                            )
                            Box(Modifier.glasmioGroupedListBackground(shape)) {
                                ContactSelectionRow(
                                    contact = contact,
                                    selected = contact.id in selectedContactIds,
                                    showTopSeparator = index > 0,
                                    onClick = {
                                        selectedContactIds = if (contact.id in selectedContactIds) {
                                            selectedContactIds - contact.id
                                        } else selectedContactIds + contact.id
                                    },
                                )
                            }
                        }
                    }
                }
                NeteaseShareMode.Timeline -> item(key = "message") {
                    ShareMessageField(message, { message = it }, R.string.share_say_something)
                }
            }
            state.error?.let { error -> item(key = "error") {
                Text(error, modifier = Modifier.padding(top = 12.dp), color = LocalGlassColors.current.destructive)
            } }
        }
        if (mode != NeteaseShareMode.Menu) Box(
            Modifier.fillMaxWidth().padding(bottom = padding.calculateBottomPadding()).padding(16.dp),
        ) {
            GlasmioButton(
                onClick = {
                    if (mode == NeteaseShareMode.PrivateMessage) {
                        viewModel.sendPrivate(resource, selectedContactIds, message) { dismissal.requestDismiss() }
                    } else viewModel.shareTimeline(resource, message) { dismissal.requestDismiss() }
                },
                modifier = Modifier.fillMaxWidth(),
                size = GlasmioButtonSize.Large,
                enabled = !state.isSending && (mode != NeteaseShareMode.PrivateMessage || selectedContactIds.isNotEmpty()),
                emphasis = GlassEmphasis.Prominent,
            ) { Text(stringResource(if (mode == NeteaseShareMode.PrivateMessage) R.string.send else R.string.publish)) }
        }
    }
}

@Composable
private fun ShareModeButton(
    systemName: String,
    labelRes: Int,
    showTopSeparator: Boolean = true,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = stringResource(labelRes),
        systemName = systemName,
        showTopSeparator = showTopSeparator,
        onClick = onClick,
    )
}

@Composable
private fun ShareResourcePreview(resource: ShareResource) {
    GlasmioGroupedList {
        GlasmioListRow(
            title = resource.title,
            subtitle = resource.subtitle,
            showTopSeparator = false,
            leading = {
                AsyncImage(
                    model = resource.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(9.dp)),
                )
            },
        )
    }
}

@Composable
private fun ShareMessageField(value: String, onValueChange: (String) -> Unit, placeholderRes: Int) {
    GlasmioGroupedList {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            minLines = 2,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalGlassColors.current.content),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(stringResource(placeholderRes), color = LocalGlassColors.current.secondaryContent)
                }
                inner()
            },
        )
    }
}

@Composable
private fun ContactSelectionRow(
    contact: MessageContact,
    selected: Boolean,
    showTopSeparator: Boolean,
    onClick: () -> Unit,
) {
    GlasmioListRow(
        title = contact.displayName,
        showTopSeparator = showTopSeparator,
        subtitle = contact.signature?.takeIf(String::isNotBlank),
        onClick = onClick,
        leading = {
            AsyncImage(
                model = contact.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(ContinuousRoundedRectangle(50)),
            )
        },
        trailing = {
            SfIcon(if (selected) "checkmark.circle.fill" else "circle", null, size = 20.dp,
                tint = if (selected) LocalGlassColors.current.accent else LocalGlassColors.current.secondaryContent)
        },
    )
}
