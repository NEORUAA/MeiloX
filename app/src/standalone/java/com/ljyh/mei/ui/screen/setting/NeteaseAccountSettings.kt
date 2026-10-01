package com.ljyh.mei.ui.screen.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.utils.setClipboard

internal fun LazyListScope.neteaseAccountSettings() {
    item {
        val context = LocalContext.current
        val (cookie) = rememberPreference(CookieKey, "")
        SettingsGroup(stringResource(R.string.settings_account)) {
            GlassCard(
                modifier = Modifier.fillMaxWidth().alpha(if (cookie.isNotBlank()) 1f else 0.38f),
                onClick = if (cookie.isNotBlank()) {
                    { setClipboard(context, cookie, "MUSIC_U") }
                } else null,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SfIcon("document.on.clipboard", null, size = 21.dp)
                    Column(
                        Modifier.weight(1f).padding(horizontal = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.general_export_music_u))
                        Text(
                            stringResource(R.string.general_export_music_u_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SfIcon("chevron.forward", null, size = 15.dp, tint = LocalGlassColors.current.separator)
                }
            }
        }
    }
}
