package com.ljyh.mei.ui.screen.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.ljyh.mei.R
import com.ljyh.mei.source.shared.SharedOnlineRepository
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neoruaa.glasmio.ui.GlasmioButton
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Existing route keys remain valid; installed sources own page recipes and native mappings. */
enum class OfficialNeteasePage(@StringRes val titleRes: Int) {
    Musician(R.string.netease_musician),
    MusicianCenter(R.string.musician_workbench),
    MusicianApply(R.string.musician_apply),
    PhoneBinding(R.string.account_phone_binding),
    ThirdPartyBinding(R.string.account_third_party_binding),
    LoginProtection(R.string.account_login_protection),
    DeviceManagement(R.string.account_device_management),
    SecurityCenter(R.string.account_security_center),
}

@HiltViewModel
class OfficialSourcePageViewModel @Inject constructor(private val repository: SharedOnlineRepository, savedStateHandle: SavedStateHandle) : ViewModel() {
    val providerId: String = savedStateHandle["providerId"] ?: ProviderIds.NETEASE
    suspend fun open(page: OfficialNeteasePage) = repository.screen("official-account-page", providerId).openWebPage(page.name)
}

@Composable
fun OfficialNeteasePageScreen(page: OfficialNeteasePage, viewModel: OfficialSourcePageViewModel = hiltViewModel()) {
    val nav = LocalNavController.current
    var attempt by remember(page) { mutableIntStateOf(0) }
    var failed by remember(page) { mutableStateOf(false) }
    var requiresLogin by remember(page) { mutableStateOf(false) }
    var awaitingLogin by remember(page) { mutableStateOf(false) }
    val login = {
        awaitingLogin = true
        Screen.NeteaseLogin.navigate(nav) { addQuery("providerId", viewModel.providerId) }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (awaitingLogin) { awaitingLogin = false; attempt++ }
    }
    LaunchedEffect(page, attempt) {
        requiresLogin = false
        failed = false
        try {
            if (viewModel.open(page)) nav.navigateUp() else login()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) nav.navigateUp()
            throw cancelled
        } catch (error: SourceException) {
            requiresLogin = error.code in setOf(SourceErrorCode.AUTH_REQUIRED, SourceErrorCode.AUTH_EXPIRED)
            failed = !requiresLogin
        } catch (_: Exception) { failed = true }
    }
    // The source Web host supplies the original WebView chrome while the effect is pending.
    MeiloXPinnedPage(title = stringResource(page.titleRes), onNavigateBack = nav::navigateUp,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (requiresLogin) GlasmioButton(onClick = login) { Text(stringResource(R.string.netease_login)) }
            else if (failed) GlasmioButton(onClick = { failed = false; attempt++ }) { Text(stringResource(R.string.retry)) }
            else CircularProgressIndicator()
        }
    }
}
