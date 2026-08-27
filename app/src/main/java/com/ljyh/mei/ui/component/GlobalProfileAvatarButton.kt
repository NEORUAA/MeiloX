package com.ljyh.mei.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseRefreshTokenKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberPreference

/** Shared account entry shown on every primary tab's pinned navigation bar. */
@Composable
fun GlobalProfileAvatarButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    val (cookie) = rememberPreference(CookieKey, "")
    val (refreshToken) = rememberPreference(NeteaseRefreshTokenKey, "")
    val (avatarUrl) = rememberPreference(UserAvatarUrlKey, "")
    val (nickname) = rememberPreference(UserNicknameKey, "")
    val accountDescription = stringResource(R.string.account_home)

    GlassIconButton(
        onClick = {
            if (cookie.isBlank() || refreshToken.isBlank()) {
                Screen.NeteaseLogin.navigate(navController)
            } else {
                Screen.AccountHome.navigate(navController)
            }
        },
        modifier = modifier,
    ) {
        if (avatarUrl.isBlank() || refreshToken.isBlank()) {
            SfIcon(
                systemName = "person.crop.circle",
                contentDescription = accountDescription,
                size = 27.dp,
            )
        } else {
            AsyncImage(
                model = avatarUrl,
                contentDescription = nickname.ifBlank { accountDescription },
                modifier = Modifier.size(36.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
