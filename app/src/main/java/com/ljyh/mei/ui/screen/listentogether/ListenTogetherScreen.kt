package com.ljyh.mei.ui.screen.listentogether

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.playback.ListenTogetherStore
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSurface
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets

@Composable
fun ListenTogetherScreen(store: ListenTogetherStore = viewModel<ListenTogetherStoreHolder>().store) {
    val state by store.state.collectAsState()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val initialInvitation = remember(navController) {
        navController.previousBackStackEntry?.savedStateHandle?.remove<String>("listen_invitation").orEmpty()
    }
    var invitationText by remember(initialInvitation) { mutableStateOf(initialInvitation) }

    IosPinnedListPage(
        title = stringResource(R.string.listen_together),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        actions = {
            GlassIconButton(store::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.room?.let { room ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.listen_room_id, room.id), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.listen_members, room.users.size))
                        room.users.forEach { Text("• ${it.nickname}") }
                        state.invitationUrl?.let { invitationUrl ->
                            GlassButton(
                                onClick = {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.listen_invitation_subject))
                                                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.listen_invitation_message, invitationUrl))
                                            },
                                            context.getString(R.string.listen_invite),
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SfIcon("square.and.arrow.up", null, size = 18.dp)
                                Text(stringResource(R.string.listen_invite), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        GlassButton(onClick = store::end, emphasis = GlassEmphasis.Prominent) {
                            Text(stringResource(R.string.listen_end))
                        }
                    }
                }
            }
        } ?: run {
            item {
                GlassButton(onClick = store::create, modifier = Modifier.fillMaxWidth(), emphasis = GlassEmphasis.Prominent) {
                    Text(stringResource(R.string.listen_create))
                }
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ListenTextField(
                            invitationText,
                            { invitationText = it },
                            stringResource(R.string.listen_invitation_placeholder),
                        )
                        GlassButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                invitationText = clipboard.primaryClip
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SfIcon("document.on.clipboard", null, size = 18.dp)
                            Text(stringResource(R.string.listen_paste_invitation), modifier = Modifier.padding(start = 8.dp))
                        }
                        GlassButton(
                            onClick = { store.joinInvitation(invitationText) },
                            enabled = invitationText.isNotBlank(),
                            emphasis = GlassEmphasis.Prominent,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.listen_join)) }
                        Text(
                            stringResource(R.string.listen_invitation_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

class ListenTogetherStoreHolder @javax.inject.Inject constructor(
    val store: ListenTogetherStore,
) : androidx.lifecycle.ViewModel()

@Composable
private fun ListenTextField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = ContinuousRoundedRectangle(50)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
    }
}
