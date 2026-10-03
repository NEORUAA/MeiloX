package com.ljyh.mei.ui.screen.podcast

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
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
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgram
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.repository.PodcastSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.playback.DownloadSourceBackend
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Original detail composition with recreated models and strictly offline sources. */
class PodcastDetailRestorationDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var registry: SaveableStateRegistry
    private var sessions = newSessions()
    private lateinit var scope: CoroutineScope
    private lateinit var accounts: AccountStore
    private var modelCount = 0
    private var renderedState: PodcastDetailUiState? = null
    private val reads = mutableListOf<SessionStamp>()
    private val podcast = Podcast(91, "Fixture podcast", null, null, null, null, null, null,
        40, 0, 0, null, false, null)
    private val programs = (1L..40L).map { index ->
        PodcastProgram(index, "Episode $index", null, null, null, 60_000, 0, 0, 0,
            index.toInt(), podcast.id, podcast.name, null, index)
    }

    private fun newSessions() = SessionStore().apply { bind { SessionIdentity(17, true, false) } }

    private inline fun <reified T> forbidden(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unrelated restoration fixture call: ${method.name}") } as T

    private val source = object : PodcastSource {
        override suspend fun podcastDetail(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastDetail {
            sessions.requireCurrent(session)
            assertEquals(podcast.id, id)
            assertEquals(0, offset)
            reads += session
            return PodcastDetail(podcast, programs, false, programs.size)
        }

        override suspend fun podcastHome(session: SessionStamp): PodcastHome = error("Unrelated home")
        override suspend fun subscribedPodcasts(session: SessionStamp, offset: Int, limit: Int): PodcastPage =
            error("Unrelated subscriptions")
        override suspend fun podcasts(session: SessionStamp, categoryId: Long, offset: Int, limit: Int): List<Podcast> =
            error("Unrelated category")
        override suspend fun podcastPrograms(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastProgramPage =
            error("Unrelated pagination")
        override suspend fun setPodcastSubscribed(session: SessionStamp, id: Long, subscribed: Boolean): Unit =
            error("Subscription writes are forbidden")
    }

    @Before fun prepareAccounts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        check(packageName == "com.neoruaa.meilox.standalone.debug")
        // HyperOS blocks this fixture's initial background launch. Bootstrap only the
        // offline component through the shell; do not change background-start permissions.
        val command = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-f 0x10008000 -n $packageName/androidx.activity.ComponentActivity"
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val result = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        check("Status: ok" in result) { "Could not foreground the offline fixture: $result" }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        createAccounts()
    }

    private fun createAccounts() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        accounts = AccountStore(sessions, { owner ->
            sessions.requireCurrent(owner)
            AccountProfile(owner.identity.userId, "Fixture account", null, null, null, null, null, null, null, null)
        }, scope)
    }

    @After fun closeAccounts() {
        if (::scenario.isInitialized) scenario.close()
        if (::accounts.isInitialized) accounts.close()
        if (::scope.isInitialized) scope.cancel()
    }

    private fun showOriginalDetail(restored: Map<String, List<Any?>>? = null) = onMain {
        val activity = scenarioActivity
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.removeAllViews()
        val view = ComposeView(activity)
        activity.setContentView(view)
        view.setContent {
            val context = LocalContext.current
            val parentRegistry = checkNotNull(LocalSaveableStateRegistry.current)
            val savedState = remember { SaveableStateRegistry(restored, parentRegistry::canBeSaved) }
            SideEffect { registry = savedState }
            val models = remember { ViewModelStore() }
            val detail = remember { PodcastDetailViewModel(source, accounts).also {
                models.put("detail", it)
                modelCount++
            } }
            val owner = remember {
                object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                    override val viewModelStore = models
                    override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            check(modelClass == PlaylistViewModel::class.java)
                            val api = forbidden<ApiService>()
                            val repository = PlaylistRepository(api, forbidden<WeApiService>(),
                                forbidden<PlaylistCollectionBackend>(), sessions, forbidden<CatalogCollectionBackend>(),
                                forbidden<PlaylistTracksBackend>(), forbidden<DownloadSourceBackend>())
                            @Suppress("UNCHECKED_CAST")
                            return PlaylistViewModel(repository, repository, repository,
                                LocalPlaylistRepository(forbidden<PlaylistDao>()), api, sessions,
                                forbidden<AccountLibrarySource>()) as T
                        }
                    }
                }
            }
            DisposableEffect(models) { onDispose { models.clear() } }
            val backStack = rememberNavBackStack(MeiRoute("podcast-restoration-fixture"))
            val navigator = remember { MeiNavigator(context, backStack) }
            CompositionLocalProvider(
                LocalSaveableStateRegistry provides savedState,
                LocalViewModelStoreOwner provides owner,
                LocalNavController provides navigator,
                LocalPlayerConnection provides null,
                LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
            ) {
                MaterialTheme {
                    GlassBackdropHost(
                        modifier = Modifier.fillMaxSize(),
                        sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                        overlayContent = {
                            val state = detail.state.collectAsState().value
                            PodcastDetailScreen(podcast.id, detail)
                            SideEffect { renderedState = state }
                        },
                    )
                }
            }
        }
    }

    private lateinit var scenarioActivity: ComponentActivity

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { activity -> scenarioActivity = activity; result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            if (onMain(condition)) return
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for the original podcast detail")
    }

    private fun awaitLoaded() = await {
        renderedState?.let { !it.isLoading && it.detail?.programs?.size == programs.size } == true && list() != null
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun list(): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .singleOrNull { it.config.contains(SemanticsActions.ScrollToIndex) }

    private fun scrollPosition(): Float = checkNotNull(list()).config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun reacquiringTheSameSessionDoesNotDiscardRestoredScroll() = restoreScroll(false)

    @Test fun processLocalGenerationDoesNotDiscardTheSameAccountsScroll() = restoreScroll(true)

    private fun restoreScroll(resetGeneration: Boolean) {
        if (resetGeneration) onMain { sessions.invalidate() }
        showOriginalDetail()
        awaitLoaded()
        onMain { assertTrue(checkNotNull(list()).config[SemanticsActions.ScrollToIndex].action!!(18)) }
        await { scrollPosition() >= 18f }
        val before = onMain { scrollPosition() }
        assertTrue("The original list must be scrolled before saving", before > 10f)
        val previousModels = onMain { modelCount }
        val saved = onMain { registry.performSave().also { renderedState = null } }
        if (resetGeneration) onMain {
            accounts.close()
            scope.cancel()
            sessions = newSessions()
            createAccounts()
        }
        showOriginalDetail(saved)
        awaitLoaded()
        onMain {
            assertEquals(previousModels + 1, modelCount)
            assertEquals(sessions.snapshot(), renderedState?.session)
            assertEquals("Same-session restoration must retain the original list position", before, scrollPosition(), 0.01f)
            assertEquals(2, reads.size)
            assertTrue(reads.all { it.identity == sessions.snapshot().identity })
            assertEquals(if (resetGeneration) listOf(1L, 0L) else listOf(0L, 0L), reads.map { it.generation })
        }
    }
}
