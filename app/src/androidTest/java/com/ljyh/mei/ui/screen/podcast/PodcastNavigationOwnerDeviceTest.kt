package com.ljyh.mei.ui.screen.podcast

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
import com.ljyh.mei.R
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.Podcast
import com.ljyh.mei.data.model.melox.PodcastDetail
import com.ljyh.mei.data.model.melox.PodcastHome
import com.ljyh.mei.data.model.melox.PodcastPage
import com.ljyh.mei.data.model.melox.PodcastProgramPage
import com.ljyh.mei.data.repository.PodcastSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Original podcast list callbacks with closed sources and no real subscription mutations. */
@RunWith(Parameterized::class)
class PodcastNavigationOwnerDeviceTest(private val target: Target) {
    enum class Target(val tab: PodcastTab, val id: Long) {
        Recommended(PodcastTab.Discover, 91),
        Featured(PodcastTab.Discover, 92),
        Subscriptions(PodcastTab.Subscriptions, 93),
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): Collection<Array<Any>> = Target.entries.map { arrayOf<Any>(it) }
    }

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var accounts: AccountStore
    private lateinit var scope: CoroutineScope
    private lateinit var models: ViewModelStore
    private lateinit var podcasts: PodcastViewModel
    private lateinit var navigator: MeiNavigator
    private lateinit var guestTitle: String
    private var identity = SessionIdentity(17, true, false)
    private var renderedState: PodcastUiState? = null
    private val forbiddenCalls = CopyOnWriteArrayList<String>()
    private val reads = mutableListOf<SessionStamp>()
    private val subscriptionReads = mutableListOf<SessionStamp>()
    private var readGate: CompletableDeferred<Unit>? = null
    private val items = Target.entries.associateWith { kind ->
        Podcast(kind.id, "Fixture ${kind.name} podcast", null, null, null, null, null, null,
            1, 0, 0, null, kind == Target.Subscriptions, null)
    }
    private val home = PodcastHome(emptyList(), listOf(items.getValue(Target.Featured)),
        listOf(items.getValue(Target.Recommended)))

    private fun forbidden(operation: String): Nothing {
        forbiddenCalls += operation
        error("Unrelated podcast navigation fixture call: $operation")
    }

    private val source = object : PodcastSource {
        override suspend fun podcastHome(session: SessionStamp): PodcastHome {
            sessions.requireCurrent(session)
            reads += session
            readGate?.await()
            return home
        }

        override suspend fun subscribedPodcasts(session: SessionStamp, offset: Int, limit: Int): PodcastPage {
            sessions.requireCurrent(session)
            if (!session.identity.authenticated || session.identity.anonymous) forbidden("Guest subscription read")
            assertEquals(0, offset)
            assertEquals(50, limit)
            reads += session
            subscriptionReads += session
            readGate?.await()
            return PodcastPage(listOf(items.getValue(Target.Subscriptions)), false, 1)
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

    private fun showOriginalScreen() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            guestTitle = activity.getString(R.string.podcast_subscriptions_sign_in)
            sessions = SessionStore().apply { bind { identity } }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            accounts = AccountStore(sessions, { owner ->
                sessions.requireCurrent(owner)
                AccountProfile(owner.identity.userId, "Fixture account", null, null, null, null, null, null, null, null)
            }, scope)
            podcasts = PodcastViewModel(source, accounts)
            models = ViewModelStore().apply { put("podcasts", podcasts) }
            val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = models
                override val defaultViewModelProviderFactory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = forbidden("Fallback ${modelClass.name}")
                }
            }
            podcasts.selectTab(target.tab)
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
                                PodcastScreen(podcasts)
                                SideEffect { renderedState = rendered }
                            },
                        )
                    }
                }
            }
        }
        if (target == Target.Subscriptions && !identity.authenticated) await {
            val state = podcasts.state.value
            state.session == sessions.snapshot() && !state.authenticated &&
                state.selectedTab == target.tab && renderedState === state && hasText(guestTitle)
        } else await { hasRenderedRow() }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity {
                readGate?.complete(Unit)
                if (::models.isInitialized) models.clear()
                if (::accounts.isInitialized) accounts.close()
                if (::scope.isInitialized) scope.cancel()
            }
            scenario.close()
        }
        assertTrue("A closed navigation fixture invoked unrelated dependencies: $forbiddenCalls", forbiddenCalls.isEmpty())
    }

    private fun currentItem(): Podcast = when (target) {
        Target.Recommended -> checkNotNull(podcasts.state.value.home).personalized.single()
        Target.Featured -> checkNotNull(podcasts.state.value.home).featured.single()
        Target.Subscriptions -> podcasts.state.value.subscribedPodcasts.single()
    }

    private fun hasRenderedRow(): Boolean {
        val state = podcasts.state.value
        val contentReady = if (target == Target.Subscriptions) {
            state.subscriptionsLoaded && !state.isSubscriptionsLoading &&
                state.subscribedPodcasts.singleOrNull() === items.getValue(target)
        } else !state.isLoading && state.home === home
        return state.session == sessions.snapshot() && state.selectedTab == target.tab &&
            contentReady && renderedState === state && row() != null
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun nodes(): List<SemanticsNode> = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }

    private fun hasText(title: String): Boolean = nodes().any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true
    }

    private fun row(): SemanticsNode? = nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == items.getValue(target).name } == true &&
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
        val observation = onMain {
            val state = podcasts.state.value
            "tab=${state.selectedTab}, owner=${state.session}, loading=${state.isLoading}, " +
                "home=${state.home != null}, subscribed=${state.subscribedPodcasts.size}, " +
                "subscriptionLoading=${state.isSubscriptionsLoading}, rendered=${renderedState === state}, " +
                "row=${row() != null}"
        }
        fail("Timed out waiting for the original $target podcast render: $observation")
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
        assertEquals("A retired $target podcast callback navigated", "fixture-root", navigator.currentRoute)
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain {
            current()
            assertEquals("${Screen.PodcastDetail.route}/${target.id}", navigator.currentRoute)
            assertTrue(forbiddenCalls.isEmpty())
        }
    }

    @Test fun authenticatedRowNavigates() {
        showOriginalScreen()
        onMain { assertTrue(reads.isNotEmpty()); assertTrue(reads.all { it == sessions.snapshot() }) }
        assertCurrentNavigation()
    }

    @Test fun guestCanBrowseDiscoveryButCannotReadSubscriptions() {
        identity = SessionIdentity(0, false, true)
        showOriginalScreen()
        onMain { assertTrue(subscriptionReads.isEmpty()); assertFalse(podcasts.state.value.authenticated) }
        if (target == Target.Subscriptions) onMain { assertNull(row()); assertEquals("fixture-root", navigator.currentRoute) }
        else assertCurrentNavigation()
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
        val before = onMain { podcasts.state.value }
        val item = onMain { currentItem() }
        onMain { sessions.invalidate() }
        assertRejected(retained)
        await { hasRenderedRow() }
        onMain {
            assertEquals(before.session?.identity, podcasts.state.value.session?.identity)
            assertNotEquals(before.session, podcasts.state.value.session)
            assertSame(item, currentItem())
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameStampRecoveryRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { podcasts.state.value }
        val item = onMain { currentItem() }
        onMain {
            sessions.setRecoveryRequired(true)
            retained()
            assertEquals("fixture-root", navigator.currentRoute)
        }
        await { podcasts.state.value.session == null && renderedState === podcasts.state.value }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedRow() }
        onMain { assertEquals(before.session, podcasts.state.value.session); assertSame(item, currentItem()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameStampRefreshRetiresItsOriginalCallbackBeforeReplacementLoads() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { podcasts.state.value }
        val item = onMain { currentItem() }
        onMain { readGate = CompletableDeferred(); podcasts.refresh() }
        assertRejected(retained)
        await {
            val loading = if (target == Target.Subscriptions) podcasts.state.value.isSubscriptionsLoading
                else podcasts.state.value.isLoading
            loading && renderedState === podcasts.state.value
        }
        assertRejected(retained)
        onMain { checkNotNull(readGate).complete(Unit) }
        await { hasRenderedRow() && podcasts.state.value !== before }
        onMain { assertEquals(before.session, podcasts.state.value.session); assertSame(item, currentItem()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun immediateEqualContentRefreshRebindsItsRenderedCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { podcasts.state.value }
        val item = onMain { currentItem() }
        val beforeReads = onMain { reads.size }
        onMain { podcasts.refresh() }
        await { hasRenderedRow() && podcasts.state.value !== before && reads.size > beforeReads }
        onMain {
            val current = podcasts.state.value
            assertEquals(before.session, current.session)
            assertSame(item, currentItem())
            assertTrue(current.revision > before.revision)
            assertEquals(before.copy(revision = current.revision), current)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun tabReentryDoesNotReviveItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        val before = onMain { podcasts.state.value }
        val item = onMain { currentItem() }
        val other = if (target.tab == PodcastTab.Discover) PodcastTab.Subscriptions else PodcastTab.Discover
        onMain { podcasts.selectTab(other) }
        assertRejected(retained)
        await { podcasts.state.value.selectedTab == other && renderedState === podcasts.state.value }
        onMain { podcasts.selectTab(target.tab) }
        await { podcasts.state.value.selectedTab == target.tab && renderedState === podcasts.state.value }
        onMain {
            // Locate the original row again after tab reentry.
            val scroll = nodes().filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                .mapNotNull { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action }.single()
            assertTrue(scroll(0))
        }
        await { hasRenderedRow() && podcasts.state.value !== before }
        onMain { assertEquals(before.session, podcasts.state.value.session); assertSame(item, currentItem()) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun clearedModelRetiresItsOriginalCallback() {
        showOriginalScreen()
        val retained = action()
        onMain { models.clear() }
        assertRejected(retained)
        await { podcasts.state.value.session == null && renderedState === podcasts.state.value }
        assertRejected(retained)
    }
}
