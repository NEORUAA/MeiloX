package com.ljyh.mei.ui.screen.account

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.model.melox.AccountDetail
import com.ljyh.mei.data.model.melox.AccountConnectionType
import com.ljyh.mei.data.model.melox.AccountPlaylist
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.ListeningStatsSummary
import com.ljyh.mei.data.model.melox.UserPlayRecord
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.glass.GlassButton
import com.ljyh.mei.ui.glass.GlassCard
import com.ljyh.mei.ui.glass.GlassEmphasis
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.GlassSegmentedControl
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.IosListRow
import com.ljyh.mei.ui.glass.IosPinnedListPage
import com.ljyh.mei.ui.glass.IosPinnedPage
import com.ljyh.mei.ui.glass.LocalGlassColors
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.liquidglass.InteractiveHighlight
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.groupedLazyItems
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class AccountHomeState(
    val profile: AccountProfile? = null,
    val detail: AccountDetail? = null,
    val playlists: List<AccountPlaylist> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val listeningSummary: ListeningStatsSummary? = null,
    val summaryLoading: Boolean = true,
)

@HiltViewModel
class AccountHomeViewModel @Inject constructor(
    private val repository: MeloXRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountHomeState())
    val state: StateFlow<AccountHomeState> = _state

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, summaryLoading = true, error = null)
            try {
                val profile = repository.accountProfile()
                context.dataStore.edit { preferences ->
                    preferences[UserIdKey] = profile.id.toString()
                    preferences[UserNicknameKey] = profile.nickname
                    profile.avatarUrl?.let { preferences[UserAvatarUrlKey] = it }
                }
                coroutineScope {
                    val detail = async { runCatching { repository.accountDetail(profile.id) }.getOrNull() }
                    val playlists = async { runCatching { repository.accountPlaylists(profile.id) }.getOrDefault(emptyList()) }
                    val listeningSummary = async { runCatching { repository.listeningStatsSummary() } }
                    val account = AccountHomeState(
                        profile = profile,
                        detail = detail.await(),
                        playlists = playlists.await(),
                        loading = false,
                        listeningSummary = _state.value.listeningSummary.takeIf { _state.value.profile?.id == profile.id },
                    )
                    ensureActive()
                    _state.value = account
                    val summary = listeningSummary.await()
                    ensureActive()
                    _state.value = account.copy(
                        listeningSummary = summary.getOrNull() ?: account.listeningSummary,
                        summaryLoading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureActive()
                _state.value = _state.value.copy(loading = false, summaryLoading = false, error = error.message)
            }
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
                        listeningSummary = state.listeningSummary,
                        summaryLoading = state.summaryLoading,
                        onListeningStats = { Screen.ListeningStats.navigate(navController) },
                        onConnections = { type ->
                            Screen.AccountConnections.navigate(navController) {
                                addPath(displayedProfile.id.toString())
                                addPath(type.name)
                            }
                        },
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
    listeningSummary: ListeningStatsSummary?,
    summaryLoading: Boolean,
    onListeningStats: () -> Unit,
    onConnections: (AccountConnectionType) -> Unit,
    onRankings: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 24.dp, bottom = 9.dp),
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
                        stringResource(
                            R.string.listening_account_level_summary,
                            it.level,
                            it.listenSongs?.let { count -> NumberFormat.getIntegerInstance().format(count) } ?: "—",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SfIcon(SfSymbol.Clock, null, size = 14.dp, tint = LocalGlassColors.current.secondaryContent)
                    Text(
                        stringResource(R.string.listening_stats_total) + " " +
                            (listeningSummary?.let { listeningDuration(it.totalSeconds / 60, it.totalSeconds > 0) }
                                ?: if (summaryLoading) "—" else stringResource(R.string.listening_stats_load_failed)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalGlassColors.current.secondaryContent,
                    )
                }
                Text(
                    stringResource(R.string.account_user_id, profile.id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth()) {
                AccountMetric(
                    profile.follows,
                    stringResource(R.string.account_follows),
                    Modifier.weight(1f),
                    onClick = { onConnections(AccountConnectionType.Following) },
                )
                AccountMetric(
                    profile.followers,
                    stringResource(R.string.account_followers),
                    Modifier.weight(1f),
                    onClick = { onConnections(AccountConnectionType.Followers) },
                )
                AccountMetric(
                    profile.playlistCount ?: playlistCount,
                    stringResource(R.string.account_playlist_metric),
                    Modifier.weight(1f),
                )
            }
            IosListRow(
                title = stringResource(R.string.listening_stats_entry),
                systemName = "clock",
                onClick = onListeningStats,
            )
            IosListRow(
                title = stringResource(R.string.account_listening_rank),
                systemName = "chart.bar.xaxis",
                onClick = onRankings,
            )
        }
    }
}

@Composable
private fun AccountMetric(value: Int?, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = LocalGlassColors.current
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val shape = ContinuousRoundedRectangle(24.dp)
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.15f * highlight.pressProgress), shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).then(highlight.gestureModifier) else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = colors.content)
        Spacer(Modifier.height(6.dp))
        Text(label, style = IosTypography.subheadline, color = colors.content,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

enum class ListeningPeriod { Week, AllTime }

data class ListeningRankState(
    val period: ListeningPeriod = ListeningPeriod.Week,
    val records: List<UserPlayRecord> = emptyList(),
    val totalSongsListened: Int? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ListeningRankViewModel @Inject constructor(
    private val repository: MeloXRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val cache = mutableMapOf<Pair<Long, ListeningPeriod>, List<UserPlayRecord>>()
    private val totalSongsCache = mutableMapOf<Long, Int?>()
    private var loadJob: Job? = null
    private var currentUserId: Long? = null
    private val _state = MutableStateFlow(ListeningRankState())
    val state: StateFlow<ListeningRankState> = _state

    init {
        viewModelScope.launch {
            context.dataStore.data
                .map { it[UserIdKey] to it[CookieKey] }
                .distinctUntilChanged()
                .collect {
                    loadJob?.cancel()
                    cache.clear()
                    totalSongsCache.clear()
                    val period = _state.value.period
                    _state.value = ListeningRankState(period = period)
                    currentUserId?.let { userId -> load(userId, period, force = true) }
                }
        }
    }

    fun load(userId: Long, period: ListeningPeriod, force: Boolean = false) {
        currentUserId = userId
        loadJob?.cancel()
        val key = userId to period
        if (!force && cache.containsKey(key) && totalSongsCache.containsKey(userId)) {
            _state.value = ListeningRankState(
                period = period,
                records = cache.getValue(key),
                totalSongsListened = totalSongsCache[userId],
                loading = false,
            )
            return
        }
        _state.value = ListeningRankState(
            period = period,
            records = cache[key].orEmpty(),
            totalSongsListened = totalSongsCache[userId],
            refreshing = force,
        )
        loadJob = viewModelScope.launch {
            val summary = async {
                runCatching {
                    if (!force && totalSongsCache.containsKey(userId)) totalSongsCache[userId]
                    else repository.accountDetail(userId).listenSongs
                }
            }
            val records = runCatching {
                if (!force && cache.containsKey(key)) cache.getValue(key)
                else repository.userPlayRecords(userId, period == ListeningPeriod.AllTime)
            }
            val totalSongs = summary.await()
            ensureActive()
            totalSongs.onSuccess { totalSongsCache[userId] = it }
            records.onSuccess { cache[key] = it }
            _state.value = ListeningRankState(
                period = period,
                records = cache[key].orEmpty(),
                totalSongsListened = totalSongsCache[userId],
                loading = false,
                error = records.exceptionOrNull()?.message,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningRankScreen(userId: Long, viewModel: ListeningRankViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val listState = rememberLazyListState()
    val refreshState = rememberPullToRefreshState()
    val collapseDistancePx = with(LocalDensity.current) { 56.dp.toPx() }
    val collapseProgress by remember(listState, collapseDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(userId) { viewModel.load(userId, ListeningPeriod.Week) }
    IosPinnedPage(
        title = stringResource(R.string.account_listening_rank),
        onNavigateBack = navController::navigateUp,
        bottomPadding = insets.calculateBottomPadding(),
        collapseProgress = collapseProgress,
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { if (!state.loading) viewModel.load(userId, state.period, force = true) },
            state = refreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refreshState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = contentPadding.calculateTopPadding()),
                )
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item(key = "rank-summary") {
                    ListeningSummary(state.totalSongsListened)
                }
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
                item(key = "rank-context") {
                    Text(
                        stringResource(
                            if (state.period == ListeningPeriod.Week) R.string.listening_rank_week_context
                            else R.string.listening_rank_all_time_context,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
                if (state.loading && !state.refreshing && state.records.isEmpty()) {
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
                    if (!state.loading && state.error == null && state.records.isEmpty()) {
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
                                detail = record.playCount?.let {
                                    stringResource(R.string.listening_rank_play_count, NumberFormat.getIntegerInstance().format(it))
                                } ?: stringResource(R.string.listening_count_unavailable),
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
    }
}

@Composable
private fun ListeningSummary(totalSongsListened: Int?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).offset( y = (-6).dp ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.listening_cumulative_songs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            totalSongsListened?.let {
                stringResource(R.string.listening_song_count, NumberFormat.getIntegerInstance().format(it))
            } ?: stringResource(R.string.listening_count_unavailable),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.listening_account_total_context),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
