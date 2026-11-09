package com.ljyh.mei.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import io.github.neoruaa.glasmio.scaffold.GlasmioHeaderAction
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.ui.screen.source.SourceProfileState
import com.ljyh.mei.ui.screen.source.SourceProfileViewModel

@Composable
fun globalProfileHeaderAction(): GlasmioHeaderAction {
    val navController = LocalNavController.current
    val sourceViewModel: SourceProfileViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val profile by sourceViewModel.state.collectAsState()
    return meiloxHeaderAction(
        key = "account",
        visualKey = listOf(profile.providerId, profile.account?.ref, profile.account?.avatarUrl),
        contentDescription = profile.account?.displayName ?: profile.providerName.ifBlank { "账号" },
        enabled = profile.providerId.isNotBlank() && profile.providerId != ProviderIds.LOCAL,
        onClick = { openSourceProfile(navController, profile, sourceViewModel::refresh) },
    ) { SourceProfileImage(profile) }
}

/** Shared account entry shown on every primary tab's pinned navigation bar. */
@Composable
fun GlobalProfileAvatarButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    val sourceViewModel: SourceProfileViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val profile by sourceViewModel.state.collectAsState()
    GlasmioIconButton(
        modifier = modifier,
        enabled = profile.providerId.isNotBlank() && profile.providerId != ProviderIds.LOCAL,
        onClick = { openSourceProfile(navController, profile, sourceViewModel::refresh) },
    ) { SourceProfileImage(profile) }
}

internal fun openSourceProfile(nav: com.ljyh.mei.ui.navigation.MeiNavigator, profile: SourceProfileState, refresh: () -> Unit) {
    if (profile.error != null) refresh()
    val screen = sourceProfileFeatureScreen(profile)
    if (screen != null) screen.navigate(nav) { addQuery("providerId", Uri.encode(profile.providerId)) }
    else Screen.SourceAccount.navigate(nav) { addPath(Uri.encode(profile.providerId)) }
}

internal fun sourceProfileFeatureScreen(profile: SourceProfileState): Screen? = when {
    profile.account != null && "account/home" in profile.featureRoutes -> Screen.AccountHome
    profile.account == null && !profile.loading &&
        (profile.error == null || profile.error == SourceErrorCode.AUTH_REQUIRED || profile.error == SourceErrorCode.AUTH_EXPIRED) &&
        "account/login" in profile.featureRoutes &&
        setOf("webLogin", "loginWithMobilePassword", "requestMobileSmsCode", "loginWithMobileSms")
            .all { it in profile.features["login"].orEmpty() } -> Screen.NeteaseLogin
    else -> null
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
