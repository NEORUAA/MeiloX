package com.ljyh.mei.ui.screen.search

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ActivityScenario
import com.google.gson.Gson
import com.ljyh.mei.data.model.api.SearchResult
import com.ljyh.mei.data.model.api.SearchSuggest
import com.ljyh.mei.data.model.melox.SearchDiscovery
import com.ljyh.mei.data.model.melox.SearchDiscoveryPlaylist
import com.ljyh.mei.data.network.QQMusicUApiService
import com.ljyh.mei.data.network.Resource
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
import com.ljyh.mei.data.repository.SearchSource
import com.ljyh.mei.data.repository.SongFavoritesBackend
import com.ljyh.mei.data.repository.UserRepository
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
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.utils.lyric.DuetDetector
import com.ljyh.mei.utils.lyric.LyricManager
import com.ljyh.mei.utils.lyric.LyricPreloader
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Original search callbacks with synthetic public sessions; no account, graph, or transport. */
@RunWith(Parameterized::class)
class SearchNavigationOwnerDeviceTest(private val target: Target) {
    enum class Target(val type: SearchType?, val title: String, val screen: Screen) {
        Recommendation(null, "Fixture recommendation", Screen.PlayList),
        Artist(SearchType.Artist, "Fixture artist", Screen.Artist),
        Album(SearchType.Album, "Fixture album", Screen.Album),
        Playlist(SearchType.Playlist, "Fixture playlist", Screen.PlayList),
        Podcast(SearchType.Podcast, "Fixture podcast", Screen.PodcastDetail),
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): Collection<Array<Any>> = Target.entries.map { arrayOf<Any>(it) }
    }

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var models: ViewModelStore
    private lateinit var search: SearchViewModel
    private lateinit var landing: SearchDiscoveryViewModel
    private lateinit var navigator: MeiNavigator
    private var identity = SessionIdentity(17, true, false)
    private val query = mutableStateOf("Fixture query")
    private var renderedState: Any? = null
    private val reads = mutableListOf<SessionStamp>()
    private val forbiddenCalls = CopyOnWriteArrayList<String>()
    private var landingGate: CompletableDeferred<Unit>? = null
    private var lyricManager: LyricManager? = null
    private var closedClient: OkHttpClient? = null
    private val discovery = SearchDiscovery(listOf(SearchDiscoveryPlaylist(91, Target.Recommendation.title, null, null, null)))

    private val result = Gson().fromJson(
        """{"code":200,"result":{"hasMore":false,
        "artists":[{"id":91,"name":"Fixture artist","picUrl":null,"alias":[]}],
        "albums":[{"id":91,"name":"Fixture album","picUrl":"/fixture-no-artwork","artists":[],"size":1}],
        "playlists":[{"id":91,"name":"Fixture playlist","coverImgUrl":"/fixture-no-artwork","trackCount":1,"creator":{"userId":9,"nickname":"Fixture creator","avatarUrl":""}}],
        "djRadios":[{"id":91,"name":"Fixture podcast","picUrl":null,"programCount":1,"subCount":0}]}}""",
        SearchResult::class.java,
    )

    private val source = object : SearchSource {
        override suspend fun search(session: SessionStamp, keyword: String, type: Int, limit: Int, offset: Int): Resource<SearchResult> {
            assertEquals(target.type?.type, type)
            assertEquals(30, limit)
            assertEquals(0, offset)
            reads += session
            return Resource.Success(result)
        }

        override suspend fun searchSuggest(session: SessionStamp, keyword: String): Resource<SearchSuggest> {
            forbiddenCalls += "Search suggestions"
            error("A navigation fixture cannot request suggestions")
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ ->
        val operation = "${T::class.java.simpleName}.${method.name}"
        forbiddenCalls += operation
        error("Unrelated fixture call: $operation")
    } as T

    private fun seedInactiveOverlayModels(activity: ComponentActivity, owner: ViewModelStoreOwner) {
        // The original result screen resolves these defaults even while its overlay is None.
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
            throw IOException("Network is disabled in navigation fixtures")
        }.build().also { closedClient = it }
        val repository = PlayerRepository(unused<QQMusicUApiService>(), api, weapi, sessions,
            unused<SongFavoritesBackend>(), amllClient = client)
        val qq = QQSongRepository(unused<QQSongDao>())
        val manager = LyricManager(repository, qq, CachedLyricRepository(unused<CachedLyricDao>()),
            DuetDetector(), LyricPreloader(repository, qq, sessions), activity, sessions).also { lyricManager = it }
        val player = PlayerViewModel(repository, qq, sessions, library, manager)
        val provider = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val model = when (modelClass) {
                    PlaylistViewModel::class.java -> playlist
                    PlayerViewModel::class.java -> player
                    else -> error("Unexpected fixture model: ${modelClass.name}")
                }
                return checkNotNull(modelClass.cast(model))
            }
        })
        assertSame(playlist, provider[PlaylistViewModel::class.java])
        assertSame(player, provider[PlayerViewModel::class.java])
    }

    private fun showOriginalScreen() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            models = ViewModelStore()
            val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = models
                override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        forbiddenCalls += "Fallback model ${modelClass.name}"
                        error("Navigation fixtures cannot resolve fallback models")
                    }
                }
            }
            if (target == Target.Recommendation) {
                landing = SearchDiscoveryViewModel(sessions) {
                    reads += it
                    landingGate?.await()
                    discovery
                }
                models.put("landing", landing)
            } else {
                search = SearchViewModel(source, sessions)
                models.put("search", search)
                seedInactiveOverlayModels(activity, owner)
            }
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
                                val rendered = if (target == Target.Recommendation) landing.state.collectAsState().value
                                    else search.state.collectAsState().value
                                if (target == Target.Recommendation) SearchLandingScreen(landing)
                                else SearchResultScreen(query.value, checkNotNull(target.type).type, search)
                                SideEffect { renderedState = rendered }
                            },
                        )
                    }
                }
            }
        }
        await { hasRenderedRow() }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity {
                landingGate?.complete(Unit)
                if (::models.isInitialized) models.clear()
                lyricManager?.release()
            }
            scenario.close()
        }
        closedClient?.connectionPool?.evictAll()
        closedClient?.dispatcher?.executorService?.shutdown()
        assertTrue("A closed navigation fixture invoked unrelated dependencies: $forbiddenCalls", forbiddenCalls.isEmpty())
    }

    private fun currentState(): Any = if (target == Target.Recommendation) landing.state.value else search.state.value

    private fun currentItem(): Any {
        if (target == Target.Recommendation) return checkNotNull(landing.state.value.discovery).recommendations.single()
        val data = (search.state.value.result as Resource.Success).data.result
        return when (target) {
            Target.Artist -> data.artists.orEmpty().single()
            Target.Album -> data.albums.orEmpty().single()
            Target.Playlist -> data.playlists.orEmpty().single()
            Target.Podcast -> data.podcasts.orEmpty().single()
            Target.Recommendation -> error("Recommendation does not use search results")
        }
    }

    private fun hasRenderedRow(): Boolean {
        val loaded = if (target == Target.Recommendation) {
            val state = landing.state.value
            state.session == sessions.snapshot() && !state.loading && !state.error && state.discovery === discovery
        } else {
            val state = search.state.value
            state.session == sessions.snapshot() && state.query == query.value && state.type == target.type &&
                state.result is Resource.Success && !state.hasMore
        }
        return loaded && renderedState === currentState() && row() != null
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun row(): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == target.title } == true &&
                node.config.contains(SemanticsActions.OnClick)
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
        fail("Timed out waiting for the original $target render")
    }

    private fun action(): () -> Unit = onMain {
        // Retain this render's concrete callback, not mutable semantics forwarding state.
        val element = checkNotNull(row()).layoutInfo.getModifierInfo().map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
        @Suppress("UNCHECKED_CAST")
        (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
    }

    private fun assertRejected(retained: () -> Unit) = onMain {
        retained()
        assertEquals("A retired $target callback navigated", "fixture-root", navigator.currentRoute)
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain {
            current()
            assertEquals("${target.screen.route}/91", navigator.currentRoute)
            assertTrue(forbiddenCalls.isEmpty())
        }
    }

    @Test fun currentGuestCallbackNavigates() {
        identity = SessionIdentity(0, false, true)
        showOriginalScreen()
        onMain { assertTrue(reads.isNotEmpty()); assertTrue(reads.all { it.identity.anonymous }) }
        assertCurrentNavigation()
    }

    @Test fun replacementRenderWithinTheSameSessionRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { Triple(currentState(), sessions.snapshot(), currentItem()) }
        if (target == Target.Recommendation) {
            onMain { landingGate = CompletableDeferred(); landing.refresh() }
            await { landing.state.value.loading && renderedState === landing.state.value }
            assertRejected(retained)
            onMain { checkNotNull(landingGate).complete(Unit) }
        } else {
            onMain { query.value = "Replacement fixture query" }
        }
        await { hasRenderedRow() && currentState() !== before.first }
        onMain {
            assertEquals(before.second, sessions.snapshot())
            assertSame(before.third, currentItem())
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun replacementAccountRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val item = onMain { currentItem() }
        onMain { sessions.beginTransition().use { identity = identity.copy(userId = 18) } }
        assertRejected(retained)
        await { hasRenderedRow() }
        onMain { assertSame(item, currentItem()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameAccountReauthorizationRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { sessions.snapshot() }
        val item = onMain { currentItem() }
        onMain { sessions.invalidate() }
        assertRejected(retained)
        await { hasRenderedRow() }
        onMain {
            assertEquals(before.identity, sessions.snapshot().identity)
            assertNotEquals(before, sessions.snapshot())
            assertSame(item, currentItem())
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun recoveryWithinTheSameStampRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { sessions.snapshot() }
        val item = onMain { currentItem() }
        onMain {
            sessions.setRecoveryRequired(true)
            retained()
            assertEquals("fixture-root", navigator.currentRoute)
        }
        await {
            val pending = if (target == Target.Recommendation) {
                landing.state.value.session == null && landing.state.value.error
            } else search.state.value.session == null && search.state.value.result is Resource.Error
            pending && renderedState === currentState()
        }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedRow() }
        onMain {
            assertEquals(before, sessions.snapshot())
            assertSame(item, currentItem())
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }
}
