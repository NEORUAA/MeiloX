package com.ljyh.mei.ui.screen.account

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.R
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.model.melox.AccountDetail
import com.ljyh.mei.data.model.melox.AccountPlaylist
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.UserPlayRecord
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.GlassSurface
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AccountHomeState(
    val profile: AccountProfile? = null,
    val detail: AccountDetail? = null,
    val playlists: List<AccountPlaylist> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AccountHomeViewModel @Inject constructor(
    private val repository: MeloXRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountHomeState())
    val state: StateFlow<AccountHomeState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val profile = repository.accountProfile()
                context.dataStore.edit { preferences ->
                    preferences[UserIdKey] = profile.id.toString()
                    preferences[UserNicknameKey] = profile.nickname
                    profile.avatarUrl?.let { preferences[UserAvatarUrlKey] = it }
                }
                coroutineScope {
                    val detail = async { runCatching { repository.accountDetail(profile.id) }.getOrNull() }
                    val playlists = async { runCatching { repository.accountPlaylists(profile.id) }.getOrDefault(emptyList()) }
                    AccountHomeState(profile, detail.await(), playlists.await(), loading = false)
                }
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }
}

@Composable
fun AccountHomeScreen(viewModel: AccountHomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    IosPinnedListPage(
        title = stringResource(R.string.account_home),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            GlassIconButton(viewModel::refresh) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        state.profile?.let { profile ->
            val displayedProfile = state.detail?.profile ?: profile
            item {
                Box(Modifier.padding(top = 10.dp)) {
                    AccountHero(
                        profile = displayedProfile,
                        detail = state.detail,
                        playlistCount = state.playlists.size,
                        onRankings = {
                            Screen.AccountListeningRank.navigate(navController) {
                                addPath(displayedProfile.id.toString())
                            }
                        },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.account_playlists, state.playlists.size),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, start = 4.dp),
                )
            }
        }
        if (state.loading && state.profile == null) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 10.dp).padding(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        state.error?.let { message ->
            item {
                GlassCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        GlassButton(viewModel::refresh, emphasis = GlassEmphasis.Prominent) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
        if (state.playlists.isNotEmpty()) {
            groupedLazyItems(
                items = state.playlists,
                key = { "account-playlist-${it.id}" },
                contentType = "account-playlist",
                firstItemTopPadding = 10.dp,
            ) { playlist, index ->
                IosListRow(
                    title = playlist.name,
                    subtitle = stringResource(R.string.account_track_count, playlist.trackCount),
                    showTopSeparator = index > 0,
                    leading = {
                        AsyncImage(
                            model = playlist.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                        )
                    },
                    onClick = {
                        Screen.PlayList.navigate(navController) { addPath(playlist.id.toString()) }
                    },
                )
            }
        }
    }
}

@Composable
private fun AccountHero(
    profile: AccountProfile,
    detail: AccountDetail?,
    playlistCount: Int,
    onRankings: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(132.dp).clip(CircleShape),
            )
            Text(
                profile.nickname,
                style = MaterialTheme.typography.headlineSmall,
                color = LocalGlassColors.current.content,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            profile.signature?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
            }
            detail?.let {
                Text(
                    stringResource(R.string.account_level_listens, it.level, it.listenSongs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.account_user_id, profile.id),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassSurface(Modifier.fillMaxWidth(), shape = ContinuousRoundedRectangle(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    AccountMetric(profile.follows, stringResource(R.string.account_follows), Modifier.weight(1f))
                    AccountMetric(profile.followers, stringResource(R.string.account_followers), Modifier.weight(1f))
                    AccountMetric(profile.playlistCount ?: playlistCount, stringResource(R.string.account_playlist_metric), Modifier.weight(1f))
                }
            }
            GlassButton(onRankings, emphasis = GlassEmphasis.Prominent) {
                SfIcon("chart.bar.xaxis", null, size = 18.dp)
                Text(stringResource(R.string.account_listening_rank), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun AccountMetric(value: Int?, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value?.toString() ?: "—", fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class ListeningPeriod { Week, AllTime }

data class ListeningRankState(
    val period: ListeningPeriod = ListeningPeriod.Week,
    val records: List<UserPlayRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ListeningRankViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val cache = mutableMapOf<ListeningPeriod, List<UserPlayRecord>>()
    private val _state = MutableStateFlow(ListeningRankState())
    val state: StateFlow<ListeningRankState> = _state

    fun load(userId: Long, period: ListeningPeriod, force: Boolean = false) {
        if (!force && cache.containsKey(period)) {
            _state.value = ListeningRankState(period, cache.getValue(period), loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(period = period, loading = true, error = null)
            runCatching { repository.userPlayRecords(userId, period == ListeningPeriod.AllTime) }
                .onSuccess {
                    cache[period] = it
                    _state.value = ListeningRankState(period, it, loading = false)
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.message)
                }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun ListeningRankScreen(userId: Long, viewModel: ListeningRankViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    LaunchedEffect(userId) { viewModel.load(userId, ListeningPeriod.Week) }
    IosPinnedListPage(
        title = stringResource(R.string.account_listening_rank),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        actions = {
            GlassIconButton({ viewModel.load(userId, state.period, force = true) }) {
                SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh))
            }
        },
    ) {
        item(key = "rank-period") {
            GlassSegmentedControl(
                items = listOf(
                    ListeningPeriod.Week to stringResource(R.string.account_week),
                    ListeningPeriod.AllTime to stringResource(R.string.account_all_time),
                ),
                selected = state.period,
                onSelected = { viewModel.load(userId, it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        if (state.loading && state.records.isEmpty()) {
            item(key = "rank-loading") {
                Box(
                    Modifier.fillMaxWidth().padding(top = 10.dp).padding(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            state.error?.let {
                item {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!state.loading && state.records.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.account_no_listening_records),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).padding(48.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (state.records.isNotEmpty()) {
                groupedLazyItems(
                    items = state.records,
                    key = { "account-rank-${state.period.name}-${it.song.id}" },
                    contentType = "account-rank-record",
                    firstItemTopPadding = 10.dp,
                ) { record, index ->
                    IosListRow(
                        title = record.song.name,
                        subtitle = record.song.artists.joinToString(" / "),
                        detail = stringResource(R.string.account_play_count, record.playCount),
                        showTopSeparator = index > 0,
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${index + 1}",
                                    modifier = Modifier.width(28.dp).offset(x = (-4).dp),
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                )
                                AsyncImage(
                                    model = record.song.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(44.dp).clip(ContinuousRoundedRectangle(10.dp)),
                                )
                            }
                        },
                        onClick = {
                            playerConnection?.playQueue(
                                ListQueue(
                                    id = "account-rank-${state.period.name}",
                                    title = navController.context.getString(R.string.account_listening_rank),
                                    items = state.records.map { it.song.id.toString() to null },
                                    startIndex = index,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}
