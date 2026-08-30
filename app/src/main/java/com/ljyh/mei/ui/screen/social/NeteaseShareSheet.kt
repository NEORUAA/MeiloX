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
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSurface
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
                .onSuccess { onSent() }
                .onFailure { _state.value = _state.value.copy(isSending = false, error = it.message) }
        }
    }

    fun shareTimeline(resource: ShareResource, message: String, onSent: () -> Unit) {
        if (_state.value.isSending) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSending = true, error = null)
            runCatching { repository.shareToTimeline(resource, message.trim()) }
                .onSuccess { onSent() }
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
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mode != NeteaseShareMode.Menu) {
                        GlassIconButton(onClick = { mode = NeteaseShareMode.Menu }) {
                            SfIcon(SfSymbol.ChevronBack, stringResource(R.string.navigation_back), mirrored = true)
                        }
                    }
                    Text(
                        stringResource(
                            when (mode) {
                                NeteaseShareMode.Menu -> R.string.share_song
                                NeteaseShareMode.PrivateMessage -> R.string.netease_private_message
                                NeteaseShareMode.Timeline -> R.string.netease_timeline_share
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    GlassIconButton(onClick = onDismiss) {
                        SfIcon(SfSymbol.Close, stringResource(R.string.cancel))
                    }
                }
                ShareResourcePreview(resource)
                when (mode) {
                    NeteaseShareMode.Menu -> {
                        ShareModeButton("paperplane", R.string.netease_private_message) {
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
                                append("\nhttps://music.163.com/song?id=").append(metadata.id)
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
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(state.contacts, key = MessageContact::id) { contact ->
                                    ContactSelectionRow(
                                        contact = contact,
                                        selected = contact.id in selectedContactIds,
                                        onClick = {
                                            selectedContactIds = if (contact.id in selectedContactIds) {
                                                selectedContactIds - contact.id
                                            } else selectedContactIds + contact.id
                                        },
                                    )
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
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ShareModeButton(systemName: String, labelRes: Int, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        SfIcon(systemName, null, size = 21.dp)
        Text(stringResource(labelRes), modifier = Modifier.weight(1f).padding(start = 12.dp))
        SfIcon("chevron.right", null, size = 15.dp, tint = LocalGlassColors.current.separator)
    }
}

@Composable
private fun ShareResourcePreview(resource: ShareResource) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = resource.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(ContinuousRoundedRectangle(12.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(resource.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    resource.subtitle.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stringResource(R.string.share_kind_song), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ShareMessageField(value: String, onValueChange: (String) -> Unit, placeholderRes: Int) {
    GlassSurface(Modifier.fillMaxWidth(), shape = ContinuousRoundedRectangle(18.dp)) {
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
private fun ContactSelectionRow(contact: MessageContact, selected: Boolean, onClick: () -> Unit) {
    GlassButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        emphasis = if (selected) GlassEmphasis.Prominent else GlassEmphasis.Regular,
    ) {
        AsyncImage(
            model = contact.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(ContinuousRoundedRectangle(50)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            contact.signature?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        SfIcon(if (selected) "checkmark.circle.fill" else "circle", null, size = 20.dp)
    }
}
