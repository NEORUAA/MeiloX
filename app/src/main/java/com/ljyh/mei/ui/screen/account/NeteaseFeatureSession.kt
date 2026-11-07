package com.ljyh.mei.ui.screen.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljyh.mei.source.api.AccountRevisionCapability
import com.ljyh.mei.source.core.SourcePreferences
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.source.shared.OnlineSessionKind
import com.ljyh.mei.source.shared.OnlineSessionState
import com.ljyh.mei.source.shared.SharedOnlineRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SourceSessionEntryPoint {
    fun onlineRepository(): SharedOnlineRepository
    fun sourcePreferences(): SourcePreferences
    fun sourceRouter(): SourceRouter
}

/** Account status observes source-owned revisions. Screen actions keep their separate pinned bindings. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberSourceSessionState(providerId: String? = null): OnlineSessionState {
    val context = LocalContext.current.applicationContext
    val flow = remember(context, providerId) {
        val dependencies = EntryPointAccessors.fromApplication(context, SourceSessionEntryPoint::class.java)
        val router = dependencies.sourceRouter()
        combine(dependencies.sourcePreferences().settings, router.registry.revision) { settings, registryRevision ->
            (providerId ?: settings.preferredBrowseProvider) to registryRevision
        }.flatMapLatest { (id, _) ->
            ((router.registry.provider(id) as? AccountRevisionCapability)?.accountRevision ?: flowOf(null)).map { id to it }
        }.mapLatest { (id, _) ->
            try { dependencies.onlineRepository().screen("source-session-status", id).sessionState() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OnlineSessionState(id, false, OnlineSessionKind.None) }
        }
    }
    return flow.collectAsStateWithLifecycle(initialValue = OnlineSessionState(providerId.orEmpty(), false, OnlineSessionKind.None)).value
}
