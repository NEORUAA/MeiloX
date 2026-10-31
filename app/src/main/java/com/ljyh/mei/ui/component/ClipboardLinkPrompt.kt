package com.ljyh.mei.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.NeteaseMusicLink
import com.ljyh.mei.ui.component.utils.rememberAlertContent
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog

@Composable
fun ClipboardLinkPrompt(
    link: NeteaseMusicLink?,
    onDismiss: () -> Unit,
    onOpen: (NeteaseMusicLink) -> Unit,
) {
    val displayedLink = rememberAlertContent(link) ?: return
    val isSong = displayedLink is NeteaseMusicLink.Song
    GlasmioAlertDialog(
        visible = link != null,
        onDismissRequest = onDismiss,
        title = stringResource(if (isSong) R.string.clipboard_song_title else R.string.clipboard_listen_title),
        message = stringResource(if (isSong) R.string.clipboard_song_message else R.string.clipboard_listen_message),
        buttons = listOf(
            GlasmioAlertButtonSpec(
                label = stringResource(R.string.clipboard_ignore),
                onClick = onDismiss,
                role = GlasmioAlertButtonRole.Cancel,
                enabled = link != null,
            ),
            GlasmioAlertButtonSpec(
                label = stringResource(if (isSong) R.string.clipboard_open_song else R.string.clipboard_view_invitation),
                onClick = { onOpen(displayedLink) },
                enabled = link != null,
            ),
        ),
    )
}
