package com.ljyh.mei.ui.screen.main.library

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ActivityScenario
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.AccountLibraryRepository
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.repository.PlaylistMutationSource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.repository.PodcastSource
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.repository.UserRepository
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.dao.CachedLyricDao
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.dao.QQSongDao
import com.ljyh.mei.di.repository.CachedLyricRepository
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.playback.DownloadSourceBackend
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.main.library.component.LibraryMobileLayout
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastTab
import com.ljyh.mei.ui.screen.podcast.PodcastUiState
import com.ljyh.mei.ui.screen.podcast.PodcastViewModel
import com.ljyh.mei.utils.lyric.DuetDetector
import com.ljyh.mei.utils.lyric.LyricManager
import com.ljyh.mei.utils.lyric.LyricPreloader
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Original Library rows with synthetic sessions and fail-closed inactive overlay dependencies. */
class LibraryPodcastNavigationOwnerDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var accounts: AccountStore
    private lateinit var scope: CoroutineScope
    private lateinit var models: ViewModelStore
    private lateinit var podcasts: PodcastViewModel
    private lateinit var navigator: MeiNavigator
    private var identity = SessionIdentity(17, true, false)
    private var renderedState: PodcastUiState? = null
    private val forbiddenCalls = CopyOnWriteArrayList<String>()
    private val subscriptionReads = mutableListOf<SessionStamp>()
    private var readGate: CompletableDeferred<Unit>? = null
    private var lyricManager: LyricManager? = null
    private var closedClient: OkHttpClient? = null
    private val podcast = Podcast(91, "Fixture library podcast", null, null, null, null, null,
        null, 1, 0, 0, null, true, null)
    private var rows = listOf(podcast)

    private fun forbidden(operation: String): Nothing {
        forbiddenCalls += operation
        error("Unrelated Library fixture call: $operation")
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> forbidden("${T::class.java.simpleName}.${method.name}") } as T

    private val source = object : PodcastSource {
        override suspend fun podcastHome(session: SessionStamp): PodcastHome {
            sessions.requireCurrent(session)
            return PodcastHome(emptyList(), emptyList(), emptyList())
        }
        override suspend fun subscribedPodcasts(session: SessionStamp, offset: Int, limit: Int): PodcastPage {
            sessions.requireCurrent(session)
            if (!session.identity.authenticated || session.identity.anonymous) forbidden("Guest subscription read")
            assertEquals(0, offset)
            assertEquals(50, limit)
            subscriptionReads += session
            readGate?.await()
            return PodcastPage(rows, false, rows.size)
        }
        override suspend fun podcasts(session: SessionStamp, categoryId: Long, offset: Int, limit: Int): List<Podcast> =
            forbidden("Category read")
        override suspend fun podcastDetail(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastDetail =
            forbidden("Detail read")
        override suspend fun podcastPrograms(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastProgramPage =
            forbidden("Program read")
        override suspend fun setPodcastSubscribed(session: SessionStamp, id: Long, subscribed: Boolean): Unit =
            forbidden("Subscription mutation")
    }

    private fun seedInactiveOverlayModels(activity: ComponentActivity, owner: ViewModelStoreOwner) {
        val api = unused<ApiService>()
        val weapi = unused<WeApiService>()
        val playlists = PlaylistRepository(api, weapi, unused<PlaylistCollectionBackend>(), sessions,
            unused<CatalogCollectionBackend>(), unused<PlaylistTracksBackend>(), unused<DownloadSourceBackend>())
        val local = LocalPlaylistRepository(unused<PlaylistDao>())
        val playlist = PlaylistViewModel(playlists, unused<PlaylistMutationSource>(), playlists,
            local, api, sessions, unused<AccountLibrarySource>())
        val library = AccountLibraryRepository(UserRepository(api, unused<EApiService>(), weapi), local, playlists, sessions)
        val client = OkHttpClient.Builder().addInterceptor {
            forbiddenCalls += "AMLL request"
            throw IOException("Network is disabled in Library navigation fixtures")
        }.build().also { closedClient = it }
        val repository = PlayerRepository(unused<QQMusicUApiService>(), api, weapi, sessions,
            unused<SongFavoritesBackend>(), amllClient = client)
        val qq = QQSongRepository(unused<QQSongDao>())
        val manager = LyricManager(repository, qq, CachedLyricRepository(unused<CachedLyricDao>()),
            DuetDetector(), LyricPreloader(repository, qq, sessions), activity, sessions).also { lyricManager = it }
        val player = PlayerViewModel(repository, qq, sessions, library, manager)
        val provider = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(when (modelClass) {
                PlaylistViewModel::class.java -> playlist
                PlayerViewModel::class.java -> player
                PodcastViewModel::class.java -> podcasts
                else -> forbidden("Model ${modelClass.name}")
            }))
        })
        assertSame(playlist, provider[PlaylistViewModel::class.java])
        assertSame(player, provider[PlayerViewModel::class.java])
        assertSame(podcasts, provider[PodcastViewModel::class.java])
    }

    private fun showOriginalLayout() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            accounts = AccountStore(sessions, { owner ->
                sessions.requireCurrent(owner)
                AccountProfile(owner.identity.userId, "Fixture account", null, null, null, null, null, null, null, null)
            }, scope)
            podcasts = PodcastViewModel(source, accounts)
            models = ViewModelStore()
            val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = models
                override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = forbidden("Fallback ${modelClass.name}")
                }
            }
            seedInactiveOverlayModels(activity, owner)
            activity.setContent {
                val backStack = rememberNavBackStack(MeiRoute("fixture-root"))
                navigator = remember { MeiNavigator(activity, backStack) }
                CompositionLocalProvider(
                    LocalNavController provides navigator,
                    LocalViewModelStoreOwner provides owner,
                    LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
                    LocalPlayerConnection provides null,
                ) {
                    MaterialTheme {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                            overlayContent = {
                                val rendered = podcasts.state.collectAsState().value
                                LibraryMobileLayout(
                                    userPhoto = "", isNavigationTab = false, selectedPage = LibraryPage.Podcasts,
                                    onPageSelect = { forbidden("Page selection") }, createdPlaylists = emptyList(),
                                    collectedPlaylists = emptyList(), albums = emptyList(), likedSongs = emptyList(),
                                    likedSongsLoading = false, userId = rendered.session?.identity?.userId?.toString().orEmpty(),
                                    session = rendered.session, onPlaylistClick = { forbidden("Playlist navigation") },
                                    onAlbumClick = { forbidden("Album navigation") },
                                )
                                SideEffect { renderedState = rendered }
                            },
                        )
                    }
                }
            }
        }
        if (identity.authenticated) await { hasRenderedRows() }
        else await { podcasts.state.value.session == sessions.snapshot() && renderedState === podcasts.state.value }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity {
                it.setContent { }
                readGate?.complete(Unit)
                if (::models.isInitialized) models.clear()
                lyricManager?.release()
                if (::accounts.isInitialized) accounts.close()
                if (::scope.isInitialized) scope.cancel()
            }
            scenario.close()
        }
        closedClient?.connectionPool?.evictAll()
        closedClient?.dispatcher?.executorService?.shutdown()
        assertTrue("A closed Library fixture invoked unrelated dependencies: $forbiddenCalls", forbiddenCalls.isEmpty())
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun row(): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == podcast.name } == true &&
            node.config.contains(SemanticsActions.OnClick) }

    private fun hasRenderedRows(): Boolean {
        val state = podcasts.state.value
        return state.session == sessions.snapshot() && state.subscriptionsLoaded && !state.isSubscriptionsLoading &&
            state.subscribedPodcasts == rows && renderedState === state && (rows.isEmpty() || row() != null)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            if (onMain(condition)) return
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for the original Library podcast render")
    }

    private fun action(): () -> Unit = onMain {
        // Retain the original row's callback rather than mutable semantics forwarding state.
        val element = checkNotNull(row()).layoutInfo.getModifierInfo().map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
        @Suppress("UNCHECKED_CAST")
        (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
    }

    private fun assertRejected(retained: () -> Unit) = onMain {
        retained()
        assertEquals("A retired Library podcast callback navigated", "fixture-root", navigator.currentRoute)
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain { current(); assertEquals("${Screen.PodcastDetail.route}/${podcast.id}", navigator.currentRoute) }
    }

    @Test fun currentSubscriptionNavigatesWithoutSelectingThePodcastSubscriptionsTab() {
        showOriginalLayout()
        onMain { assertEquals(PodcastTab.Discover, podcasts.state.value.selectedTab); assertTrue(subscriptionReads.isNotEmpty()) }
        assertCurrentNavigation()
    }

    @Test fun guestDoesNotReadOrRenderPrivateSubscriptions() {
        identity = SessionIdentity(0, false, true)
        showOriginalLayout()
        onMain { assertTrue(subscriptionReads.isEmpty()); assertNull(row()); assertEquals("fixture-root", navigator.currentRoute) }
    }

    @Test fun replacementAccountRetiresItsOriginalCallback() {
        showOriginalLayout()
        val retained = action()
        onMain { sessions.beginTransition().use { identity = identity.copy(userId = 18) } }
        assertRejected(retained)
        await { hasRenderedRows() }
        onMain { assertSame(podcast, podcasts.state.value.subscribedPodcasts.single()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun reauthorizationRetiresItsOriginalCallback() {
        showOriginalLayout()
        val retained = action()
        val before = onMain { podcasts.state.value }
        onMain { sessions.invalidate() }
        assertRejected(retained)
        await { hasRenderedRows() }
        onMain { assertEquals(before.session?.identity, podcasts.state.value.session?.identity); assertNotEquals(before.session, podcasts.state.value.session) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameStampRecoveryDoesNotReviveItsOriginalCallback() {
        showOriginalLayout()
        val retained = action()
        val before = onMain { podcasts.state.value }
        onMain { sessions.setRecoveryRequired(true) }
        assertRejected(retained)
        await { podcasts.state.value.session == null && renderedState === podcasts.state.value }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedRows() }
        onMain { assertEquals(before.session, podcasts.state.value.session) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun identicalImmediateRefreshRebindsItsOriginalRow() {
        showOriginalLayout()
        val retained = action()
        val before = onMain { podcasts.state.value }
        onMain { podcasts.refreshSubscriptions() }
        await { hasRenderedRows() && podcasts.state.value !== before }
        onMain { assertSame(podcast, podcasts.state.value.subscribedPodcasts.single()); assertEquals(before.session, podcasts.state.value.session) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun pendingRefreshRetiresItsOriginalCallback() {
        showOriginalLayout()
        val retained = action()
        onMain { readGate = CompletableDeferred(); podcasts.refreshSubscriptions() }
        assertRejected(retained)
        await { podcasts.state.value.isSubscriptionsLoading && renderedState === podcasts.state.value }
        assertRejected(retained)
        onMain { checkNotNull(readGate).complete(Unit) }
        await { hasRenderedRows() }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun removedSubscriptionCannotReviveItsOriginalCallback() {
        showOriginalLayout()
        val retained = action()
        onMain { rows = emptyList(); podcasts.refreshSubscriptions() }
        await { hasRenderedRows() }
        onMain { assertNull(row()) }
        assertRejected(retained)
    }

    @Test fun clearedModelRetiresItsOriginalCallback() {
        showOriginalLayout()
        val retained = action()
        onMain { ViewModelStore().apply { put("cleared-podcast", podcasts) }.clear() }
        assertRejected(retained)
    }
}
