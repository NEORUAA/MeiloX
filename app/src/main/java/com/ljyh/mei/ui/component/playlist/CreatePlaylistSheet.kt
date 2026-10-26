package com.ljyh.mei.ui.component.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import io.github.neoruaa.glasmio.icons.SfIcon

@Composable
fun CreatePlaylistSheet(
    title: String? = null,
    defaultText: String = "",
    defaultHidden: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (text: String, privacy: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(defaultText) }
    var isPrivate by remember { mutableStateOf(defaultHidden) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val submit = {
        text.trim().takeIf(String::isNotEmpty)?.let {
            onConfirm(it, isPrivate)
            focusManager.clearFocus()
        }
        Unit
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    GlasmioBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
                Text(
                    text = title ?: stringResource(R.string.create_playlist_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                GlasmioGroupedList {
                    GlasmioTextField(
                        value = text,
                        onValueChange = { if (it.length <= 20) text = it },
                        modifier = Modifier.focusRequester(focusRequester),
                        placeholder = stringResource(R.string.playlist_name_placeholder),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${text.length}/20",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (text.isNotEmpty()) {
                                    GlasmioIconButton(onClick = { text = "" }, modifier = Modifier.size(34.dp)) {
                                        SfIcon("xmark", stringResource(R.string.clear), size = 16.dp)
                                    }
                                }
                            }
                        },
                    )
                }

                GlasmioCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { isPrivate = !isPrivate },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SfIcon(if (isPrivate) "lock.iphone" else "globe", null, size = 24.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                stringResource(R.string.private_playlist_toggle),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(
                                    if (isPrivate) R.string.private_playlist_only_me
                                    else R.string.private_playlist_everyone,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GlasmioSwitch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    GlasmioButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    GlasmioButton(
                        onClick = submit,
                        enabled = text.isNotBlank(),
                        emphasis = GlassEmphasis.Prominent,
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
                Spacer(Modifier.size(4.dp))
        }
    }
}
