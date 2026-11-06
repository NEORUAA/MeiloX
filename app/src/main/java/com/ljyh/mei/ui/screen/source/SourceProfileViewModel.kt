package com.ljyh.mei.ui.screen.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.api.*
import com.ljyh.mei.source.core.ProviderRegistry
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SourceProfileState(
    val providerId: String = "",
    val providerName: String = "",
    val account: SourceAccount? = null,
    val loading: Boolean = false,
    val error: SourceErrorCode? = null,
)

@HiltViewModel
class SourceProfileViewModel @Inject constructor(
    preferences: SourcePreferences,
    registry: ProviderRegistry,
    router: SourceRouter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceProfileState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            combine(preferences.settings.map { it.preferredBrowseProvider }.distinctUntilChanged(), registry.revision) { id, revision -> id to revision }
                .collectLatest { (id, _) ->
                    val provider = registry.provider(id)
                    val base = SourceProfileState(id, provider?.displayName ?: id)
                    mutableState.value = base
                    if (id == ProviderIds.NETEASE || provider == null || SourceCapability.ACCOUNT !in provider.capabilities) return@collectLatest
                    mutableState.value = base.copy(loading = true)
                    try { mutableState.value = base.copy(account = router.account(id)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { mutableState.value = base.copy(error = error.sourceCode()) }
                }
        }
    }
}
