package com.ljyh.mei.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserNicknameKey
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.utils.rememberPreference
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.ui.screen.source.SourceProfileState
import com.ljyh.mei.ui.screen.source.SourceProfileViewModel

@Composable
fun globalProfileHeaderAction(): GlasmioHeaderAction {
    val navController = LocalNavController.current
    val sourceViewModel: SourceProfileViewModel = hiltViewModel()
    val profile by sourceViewModel.state.collectAsState()
    if (profile.providerId != ProviderIds.NETEASE) {
        return meiloxHeaderAction(
            key = "account",
            visualKey = listOf(profile.providerId, profile.account?.ref, profile.account?.avatarUrl),
            contentDescription = profile.account?.displayName ?: profile.providerName.ifBlank { "账号" },
            enabled = profile.providerId.isNotBlank() && profile.providerId != ProviderIds.LOCAL,
            onClick = { Screen.SourceAccount.navigate(navController) { addPath(Uri.encode(profile.providerId)) } },
        ) { SourceProfileImage(profile) }
    }
    val (cookie) = rememberPreference(CookieKey, "")
    val (avatarUrl) = rememberPreference(UserAvatarUrlKey, "")
    val (nickname) = rememberPreference(UserNicknameKey, "")
    val accountDescription = stringResource(R.string.account_home)
    return meiloxHeaderAction(
        key = "account",
        visualKey = listOf("account", avatarUrl, cookie.isBlank()),
        contentDescription = if (avatarUrl.isBlank() || cookie.isBlank()) accountDescription
            else nickname.ifBlank { accountDescription },
        onClick = {
            if (cookie.isBlank()) Screen.NeteaseLogin.navigate(navController)
            else Screen.AccountHome.navigate(navController)
        },
    ) {
        // Shared actions reserve a 24dp glyph slot inside their 44dp hit surface.
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (avatarUrl.isBlank() || cookie.isBlank()) {
                SfIcon("person.crop.circle", null, Modifier.requiredSize(27.dp), size = 27.dp)
            } else {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = nickname.ifBlank { accountDescription },
                    modifier = Modifier.requiredSize(36.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** Shared account entry shown on every primary tab's pinned navigation bar. */
@Composable
fun GlobalProfileAvatarButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    val sourceViewModel: SourceProfileViewModel = hiltViewModel()
    val profile by sourceViewModel.state.collectAsState()
    if (profile.providerId != ProviderIds.NETEASE) {
        GlasmioIconButton(
            modifier = modifier,
            enabled = profile.providerId.isNotBlank() && profile.providerId != ProviderIds.LOCAL,
            onClick = { Screen.SourceAccount.navigate(navController) { addPath(Uri.encode(profile.providerId)) } },
        ) { SourceProfileImage(profile) }
        return
    }
    val (cookie) = rememberPreference(CookieKey, "")
    val (avatarUrl) = rememberPreference(UserAvatarUrlKey, "")
    val (nickname) = rememberPreference(UserNicknameKey, "")
    val accountDescription = stringResource(R.string.account_home)

    GlasmioIconButton(
        onClick = {
            if (cookie.isBlank()) {
                Screen.NeteaseLogin.navigate(navController)
            } else {
                Screen.AccountHome.navigate(navController)
            }
        },
        modifier = modifier,
    ) {
        if (avatarUrl.isBlank() || cookie.isBlank()) {
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

@Composable
private fun SourceProfileImage(profile: SourceProfileState) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        val avatar = profile.account?.avatarUrl.orEmpty()
        if (avatar.isBlank()) SfIcon("person.crop.circle", null, Modifier.requiredSize(27.dp), size = 27.dp)
        else AsyncImage(avatar, profile.account?.displayName,
            modifier = Modifier.requiredSize(36.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    }
}
