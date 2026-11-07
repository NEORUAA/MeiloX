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
    val featureRoutes: Set<String> = emptySet(),
    val features: Map<String, Set<String>> = emptyMap(),
)

@HiltViewModel
class SourceProfileViewModel @Inject constructor(
    preferences: SourcePreferences,
    registry: ProviderRegistry,
    router: SourceRouter,
    private val client: com.ljyh.mei.source.core.SourceFeatureClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceProfileState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            combine(preferences.settings.map { it.preferredBrowseProvider }.distinctUntilChanged(), registry.revision) { id, _ -> id }
                .flatMapLatest { id ->
                    val provider = registry.provider(id)
                    ((provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null))
                        .map { Triple(id, provider, it) }
                }.collectLatest { (id, provider, _) ->
                    val base = SourceProfileState(id, provider?.displayName ?: id,
                        featureRoutes = (provider as? FeatureContribution)?.featureRoutes.orEmpty(),
                        features = (provider as? SourceFeatureCapability)?.features.orEmpty())
                    mutableState.value = base
                    if (provider == null || SourceCapability.ACCOUNT !in provider.capabilities) return@collectLatest
                    mutableState.value = base.copy(loading = true)
                    try { mutableState.value = base.copy(account = router.account(id)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { mutableState.value = base.copy(error = error.sourceCode()) }
                }
        }
    }
    suspend fun exportSession(providerId: String): Pair<String, String> {
        val owner = client.bind(providerId, "session-export:${java.util.UUID.randomUUID()}")
        val value = owner.invoke("login", "exportSession").asJsonObject
        val label = value.get("label")?.asString?.takeIf { it.length in 1..100 } ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        val secret = value.get("value")?.asString?.takeIf { it.isNotBlank() && it.length <= 16_384 } ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        owner.ensureCurrent()
        return label to secret
    }

}
