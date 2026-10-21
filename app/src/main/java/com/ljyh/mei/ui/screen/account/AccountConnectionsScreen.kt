package com.ljyh.mei.ui.screen.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.AccountConnectionPage
import com.ljyh.mei.data.model.melox.AccountConnectionType
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountConnectionsState(
    val page: AccountConnectionPage? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
)

@HiltViewModel
class AccountConnectionsViewModel internal constructor(
    private val loadPage: suspend (Long, AccountConnectionType, Int) -> AccountConnectionPage,
) : ViewModel() {
    @Inject
    constructor(repository: MeloXRepository) : this(
        { userId, type, offset -> repository.accountConnections(userId, type, offset) },
    )

    private val _state = MutableStateFlow(AccountConnectionsState())
    val state = _state.asStateFlow()
    private var selection: Pair<Long, AccountConnectionType>? = null
    private var loadJob: Job? = null
    private var revision = 0L

    fun load(userId: Long, type: AccountConnectionType) {
        if (selection == (userId to type)) return
        selection = userId to type
        _state.value = AccountConnectionsState()
        refresh()
    }

    fun refresh() {
        if (selection == null) return
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = true,
            loadingMore = false,
            error = null,
            loadMoreError = null,
        )
        readPage(offset = 0, append = false)
    }

    fun loadMore() {
        val current = _state.value
        val page = current.page ?: return
        if (current.loading || current.loadingMore || current.error != null || !page.hasMore) return
        _state.value = current.copy(loadingMore = true, loadMoreError = null)
        readPage(page.nextOffset, append = true)
    }

    private fun readPage(offset: Int, append: Boolean) {
        val (userId, type) = selection ?: return
        val expected = ++revision
        loadJob = viewModelScope.launch {
            try {
                val incoming = loadPage(userId, type, offset)
                ensureActive()
                if (expected != revision) return@launch
                val users = if (append) _state.value.page?.users.orEmpty() + incoming.users else incoming.users
                _state.value = AccountConnectionsState(
                    page = incoming.copy(
                        users = users.distinctBy(AccountProfile::id),
                        hasMore = incoming.hasMore && incoming.nextOffset > offset,
                    ),
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                if (expected != revision) return@launch
                val message = error.message?.takeIf(String::isNotBlank) ?: error.toString()
                _state.value = if (append) {
                    _state.value.copy(loadingMore = false, loadMoreError = message)
                } else {
                    _state.value.copy(loading = false, error = message)
                }
            }
        }
    }
}

@Composable
fun AccountConnectionsScreen(
    userId: Long,
    type: AccountConnectionType,
    viewModel: AccountConnectionsViewModel = hiltViewModel(key = "account-connections:$userId:$type"),
) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    LaunchedEffect(userId, type) { viewModel.load(userId, type) }

    IosPinnedListPage(
        title = stringResource(
            if (type == AccountConnectionType.Following) R.string.account_follows else R.string.account_followers,
        ),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            GlassIconButton(viewModel::refresh, enabled = !state.loading) {
                if (state.loading && state.page != null) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
                }
            }
        },
    ) {
        if (state.loading && state.page == null) {
            item(key = "connections-loading") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        state.error?.let { message ->
            item(key = "connections-error") { ConnectionsError(message, viewModel::refresh) }
        }
        val users = state.page?.users.orEmpty()
        if (!state.loading && state.error == null && users.isEmpty()) {
            item(key = "connections-empty") {
                Text(
                    stringResource(
                        if (type == AccountConnectionType.Following) R.string.account_no_follows
                        else R.string.account_no_followers,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groupedLazyItems(
            items = users,
            key = { "account-connection-${it.id}" },
            contentType = "account-connection",
            firstItemTopPadding = 10.dp,
        ) { user, index ->
            IosListRow(
                title = user.nickname,
                subtitle = user.signature?.takeIf(String::isNotBlank),
                showTopSeparator = index > 0,
                leading = {
                    AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                },
                onClick = user.artistId?.takeIf { it > 0 }?.let { artistId ->
                    {
                        Screen.Artist.navigate(navController) { addPath(artistId.toString()) }
                    }
                },
            )
        }
        if (state.loadingMore) {
            item(key = "connections-loading-more") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        } else if (state.loadMoreError != null) {
            item(key = "connections-load-more-error") {
                ConnectionsError(checkNotNull(state.loadMoreError), viewModel::loadMore)
            }
        } else if (state.page?.hasMore == true && !state.loading && state.error == null) {
            item(key = "connections-load-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    GlassButton(viewModel::loadMore) { Text(stringResource(R.string.account_load_more)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionsError(message: String, onRetry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            GlassButton(onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
