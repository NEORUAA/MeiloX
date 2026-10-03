package com.ljyh.mei.ui.screen.social

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.IosGroupedList
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosSheetTopToolbar
import com.ljyh.mei.ui.glass.IosSheetTopToolbarButton
import com.ljyh.mei.ui.glass.IosModalSheet
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
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

    IosModalSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            IosSheetTopToolbar(
                title = stringResource(
                    when (mode) {
                        NeteaseShareMode.Menu -> R.string.share_song
                        NeteaseShareMode.PrivateMessage -> R.string.netease_private_message
                        NeteaseShareMode.Timeline -> R.string.netease_timeline_share
                    },
                ),
                actions = {
                    if (mode != NeteaseShareMode.Menu) {
                        IosSheetTopToolbarButton(onClick = { mode = NeteaseShareMode.Menu }) {
                            SfIcon(SfSymbol.ChevronBack, stringResource(R.string.navigation_back), mirrored = true)
                        }
                    }
                    IosSheetTopToolbarButton(onClick = onDismiss) {
                        SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                    }
                },
            )
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ShareResourcePreview(resource)
                when (mode) {
                    NeteaseShareMode.Menu -> IosGroupedList {
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
                    NeteaseShareMode.PrivateMessage -> {
                        ShareMessageField(message, { message = it }, R.string.share_message_optional)
                        Text(
                            stringResource(R.string.share_recipients_count, selectedContactIds.size),
                            fontWeight = FontWeight.SemiBold,
                        )
                        when {
                            state.isLoadingContacts -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                            state.contacts.isEmpty() && state.error == null -> Text(stringResource(R.string.share_no_contacts))
                            else -> IosGroupedList {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp),
                                ) {
                                items(state.contacts, key = MessageContact::id) { contact ->
                                    ContactSelectionRow(
                                        contact = contact,
                                        selected = contact.id in selectedContactIds,
                                        showTopSeparator = contact.id != state.contacts.firstOrNull()?.id,
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
                        GlassButton(
                            onClick = {
                                viewModel.sendPrivate(resource, selectedContactIds, message, onDismiss)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedContactIds.isNotEmpty() && !state.isSending,
                            emphasis = GlassEmphasis.Prominent,
                        ) { Text(stringResource(R.string.send)) }
                    }
                    NeteaseShareMode.Timeline -> {
                        ShareMessageField(message, { message = it }, R.string.share_say_something)
                        GlassButton(
                            onClick = { viewModel.shareTimeline(resource, message, onDismiss) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isSending,
                            emphasis = GlassEmphasis.Prominent,
                        ) { Text(stringResource(R.string.publish)) }
                    }
                }
                state.error?.let { Text(it, color = LocalGlassColors.current.destructive) }
            }
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
    IosListRow(
        title = stringResource(labelRes),
        systemName = systemName,
        showTopSeparator = showTopSeparator,
        onClick = onClick,
    )
}

@Composable
private fun ShareResourcePreview(resource: ShareResource) {
    IosGroupedList {
        IosListRow(
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
    IosGroupedList {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            minLines = 2,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(stringResource(placeholderRes), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    IosListRow(
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
