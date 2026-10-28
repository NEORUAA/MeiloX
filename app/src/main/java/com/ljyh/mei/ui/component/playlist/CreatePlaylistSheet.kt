package com.ljyh.mei.ui.component.playlist

import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.core.LocalGlassColors
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetScrollEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioSheetToolbarButton
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
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
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)
    val scrollState = rememberScrollState()
    val scrollProgress = rememberMeiloXSheetScrollEdge(scrollState)
    val sheetTitle = title ?: stringResource(R.string.create_playlist_title)
    val submit = {
        text.trim().takeIf(String::isNotEmpty)?.let {
            val privacy = isPrivate
            focusManager.clearFocus()
            dismissal.requestDismiss { onConfirm(it, privacy) }
        }
        Unit
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        skipPartiallyExpanded = false,
        fitContent = false,
        title = sheetTitle,
        contentWindowInsets = { WindowInsets.statusBars.union(WindowInsets.ime) },
        topBarBlurProgress = scrollProgress,
        topBar = {
            GlasmioSheetTopToolbar(sheetTitle, actions = {
                GlasmioSheetToolbarButton(onClick = { dismissal.requestDismiss() }) {
                    SfIcon("xmark", stringResource(R.string.cancel))
                }
            })
        },
    ) {
        val padding = LocalGlasmioSheetContentPadding.current
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState).padding(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                                    color = LocalGlassColors.current.secondaryContent,
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

                GlasmioGroupedList {
                    GlasmioListRow(
                        title = stringResource(R.string.private_playlist_toggle),
                        subtitle = stringResource(if (isPrivate) R.string.private_playlist_only_me else R.string.private_playlist_everyone),
                        systemName = if (isPrivate) "lock.iphone" else "globe",
                        showTopSeparator = false,
                        onClick = { isPrivate = !isPrivate },
                        trailing = { GlasmioSwitch(checked = isPrivate, onCheckedChange = { isPrivate = it }) },
                    )
                }

        }
        Row(
                    Modifier.fillMaxWidth().padding(bottom = padding.calculateBottomPadding())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    GlasmioButton(onClick = { dismissal.requestDismiss() }, size = GlasmioButtonSize.Large) {
                        Text(stringResource(R.string.cancel))
                    }
                    GlasmioButton(
                        onClick = submit,
                        enabled = text.isNotBlank(),
                        size = GlasmioButtonSize.Large,
                        emphasis = GlassEmphasis.Prominent,
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
    }
}
