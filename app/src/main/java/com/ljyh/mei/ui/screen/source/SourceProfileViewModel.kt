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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
class SourceProfileViewModel internal constructor(
    preferences: SourcePreferences,
    registry: ProviderRegistry,
    private val readAccount: suspend (String, Boolean) -> SourceAccount?,
    private val exportAccount: suspend (String) -> Pair<String, String>,
) : ViewModel() {
    @Inject constructor(preferences: SourcePreferences, registry: ProviderRegistry, router: SourceRouter,
        client: com.ljyh.mei.source.core.SourceFeatureClient) : this(
        preferences, registry, { id, refresh -> router.account(id, refresh = refresh) }, { providerId ->
            val owner = client.bind(providerId, "session-export:${java.util.UUID.randomUUID()}")
            val value = owner.invoke("login", "exportSession").asJsonObject
            val label = value.get("label")?.asString?.takeIf { it.length in 1..100 } ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            val secret = value.get("value")?.asString?.takeIf { it.isNotBlank() && it.length <= 16_384 } ?: throw SourceException(SourceErrorCode.INVALID_RESPONSE)
            owner.ensureCurrent()
            label to secret
        },
    )
    private val refreshVersion = MutableStateFlow(0L)
    private var forcedRefreshVersion = 0L
    private val mutableState = MutableStateFlow(SourceProfileState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            var displayedOwner: Triple<MusicProvider?, String?, String?>? = null
            var completedForcedRefresh = 0L
            combine(preferences.settings.map { it.preferredBrowseProvider }.distinctUntilChanged(), registry.revision, refreshVersion) { id, _, refresh -> id to refresh }
                .flatMapLatest { (id, refresh) ->
                    val provider = registry.provider(id)
                    ((provider as? AccountRevisionCapability)?.accountRevision ?: flowOf(null))
                        .map { revision -> Triple(id, provider, revision) to
                            (forcedRefreshVersion.takeIf { it > completedForcedRefresh && it <= refresh } ?: 0L) }
                }.collectLatest { (selection, forceThrough) ->
                    val (id, provider, revision) = selection
                    val owner = Triple(provider, provider?.version, revision)
                    val account = mutableState.value.account.takeIf {
                        mutableState.value.providerId == id && displayedOwner?.first === provider &&
                            displayedOwner?.second == owner.second && displayedOwner?.third == revision
                    }
                    displayedOwner = owner
                    val base = SourceProfileState(id, provider?.displayName ?: id,
                        account = account,
                        featureRoutes = (provider as? FeatureContribution)?.featureRoutes.orEmpty(),
                        features = (provider as? SourceFeatureCapability)?.features.orEmpty())
                    if (provider == null || SourceCapability.ACCOUNT !in provider.capabilities) {
                        mutableState.value = base.copy(account = null)
                        return@collectLatest
                    }
                    mutableState.value = base.copy(loading = true)
                    suspend fun ownerCurrent(): Boolean = registry.provider(id) === provider && provider.version == owner.second &&
                        (provider as? AccountRevisionCapability)?.accountRevision?.first() == revision
                    try {
                        val current = readAccount(id, forceThrough > 0)
                        currentCoroutineContext().ensureActive()
                        if (ownerCurrent()) {
                            completedForcedRefresh = maxOf(completedForcedRefresh, forceThrough)
                            mutableState.value = base.copy(account = current)
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!ownerCurrent()) return@collectLatest
                        completedForcedRefresh = maxOf(completedForcedRefresh, forceThrough)
                        val code = error.sourceCode()
                        mutableState.value = base.copy(
                            account = base.account.takeUnless { code == SourceErrorCode.AUTH_REQUIRED || code == SourceErrorCode.AUTH_EXPIRED },
                            error = code,
                        )
                    }
                }
        }
    }
    fun refresh() = refresh(force = true)

    fun refresh(force: Boolean) {
        val next = refreshVersion.value + 1
        if (force) forcedRefreshVersion = next
        refreshVersion.value = next
    }

    suspend fun exportSession(providerId: String): Pair<String, String> = exportAccount(providerId)
}
