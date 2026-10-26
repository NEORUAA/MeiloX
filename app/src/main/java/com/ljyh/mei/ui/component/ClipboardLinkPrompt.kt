package com.ljyh.mei.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.NeteaseMusicLink
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon

@Composable
fun ClipboardLinkPrompt(
    link: NeteaseMusicLink,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val isSong = link is NeteaseMusicLink.Song
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    SfIcon(if (isSong) "music.note" else "person.2.wave.2", null, size = 28.dp)
                    Text(
                        stringResource(if (isSong) R.string.clipboard_song_title else R.string.clipboard_listen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    stringResource(if (isSong) R.string.clipboard_song_message else R.string.clipboard_listen_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    GlasmioButton(onDismiss) { Text(stringResource(R.string.clipboard_ignore)) }
                    GlasmioButton(onOpen, emphasis = GlassEmphasis.Prominent) {
                        Text(stringResource(if (isSong) R.string.clipboard_open_song else R.string.clipboard_view_invitation))
                    }
                }
            }
        }
    }
}
