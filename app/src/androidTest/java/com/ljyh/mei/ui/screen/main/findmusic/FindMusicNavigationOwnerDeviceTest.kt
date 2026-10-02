package com.ljyh.mei.ui.screen.main.findmusic

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
import androidx.lifecycle.ViewModelStore
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ActivityScenario
import com.google.gson.Gson
import com.ljyh.mei.data.model.weapi.HighQualityPlaylistResult
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HighQualityPlaylistSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiNavigator
import com.ljyh.mei.ui.navigation.MeiRoute
import com.ljyh.mei.ui.screen.Screen
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Original rendered callbacks with in-memory public sessions and data; no accounts or network. */
class FindMusicNavigationOwnerDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var models: ViewModelStore
    private lateinit var viewModel: FindMusicViewModel
    private lateinit var source: Source
    private lateinit var navigator: MeiNavigator
    private var identity = SessionIdentity(17, true, false)
    private var renderedResource: Resource<HighQualityPlaylistResult>? = null
    private var renderedOwner: Pair<SessionStamp, Int>? = null
    private var renderedCategory: String? = null

    private class Source : HighQualityPlaylistSource {
        val requests = mutableListOf<Pair<String, SessionStamp>>()
        val result = Resource.Success(Gson().fromJson(
            """{"code":200,"lasttime":0,"more":false,"total":1,"playlists":[{"id":91,"name":"Fixture featured playlist","copywriter":"Fixture recommendation","coverImgUrl":null,"playCount":1}]}""",
            HighQualityPlaylistResult::class.java,
        ))

        override suspend fun getHighQualityPlaylist(cat: String, limit: Int, session: SessionStamp): Resource<HighQualityPlaylistResult> {
            assertEquals(30, limit)
            requests += cat to session
            return result
        }
    }

    private fun showOriginalPlaylist() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            source = Source()
            viewModel = FindMusicViewModel(source, sessions)
            models = ViewModelStore().apply { put("find-music", viewModel) }
            activity.setContent {
                val backStack = rememberNavBackStack(MeiRoute("fixture-root"))
                navigator = remember { MeiNavigator(activity, backStack) }
                CompositionLocalProvider(
                    LocalNavController provides navigator,
                    LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
                ) {
                    MaterialTheme {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                            overlayContent = {
                                val resource = viewModel.highQualityPlaylist.collectAsState().value
                                val owner = viewModel.playlistOwner.collectAsState().value
                                val category = viewModel.selectedCategory.collectAsState().value
                                FindMusicScreen(viewModel, isNavigationTab = false)
                                SideEffect {
                                    renderedResource = resource
                                    renderedOwner = owner
                                    renderedCategory = category
                                }
                            },
                        )
                    }
                }
            }
        }
        await { hasRenderedPlaylist() }
    }

    @After fun closeFixtures() {
        if (::scenario.isInitialized) {
            scenario.onActivity { if (::models.isInitialized) models.clear() }
            scenario.close()
        }
    }

    private fun hasRenderedPlaylist(): Boolean {
        val resource = viewModel.highQualityPlaylist.value
        val owner = viewModel.playlistOwner.value
        return resource is Resource.Success && owner?.first == sessions.snapshot() &&
            renderedResource === resource && renderedOwner == owner &&
            renderedCategory == viewModel.selectedCategory.value && row() != null
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }

    private fun row(): SemanticsNode? = WindowInspector.getGlobalWindowViews().flatMap(::roots)
        .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Fixture featured playlist" } == true &&
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
        fail("Timed out waiting for the original discover playlist render")
    }

    private fun action(): () -> Unit = onMain {
        // Retain the concrete render callback, not the mutable semantics forwarding node.
        val element = checkNotNull(row()).layoutInfo.getModifierInfo().map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
        @Suppress("UNCHECKED_CAST")
        (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
    }

    private fun assertRejected(retained: () -> Unit) = onMain {
        retained()
        assertEquals("A retained playlist callback navigated after its owner retired", "fixture-root", navigator.currentRoute)
    }

    private fun assertCurrentNavigation() {
        val current = action()
        onMain {
            current()
            assertEquals("${Screen.PlayList.route}/91", navigator.currentRoute)
        }
    }

    @Test fun currentGuestPlaylistCallbackNavigates() {
        identity = SessionIdentity(0, false, true)
        showOriginalPlaylist()
        onMain {
            assertTrue(source.requests.single().second.identity.anonymous)
            assertSame(source.result, viewModel.highQualityPlaylist.value)
        }
        assertCurrentNavigation()
    }

    @Test fun replacementAccountRejectsTheOriginalCallbackAndRebindsTheEqualPlaylist() {
        showOriginalPlaylist()
        val retained = action()
        onMain { sessions.beginTransition().use { identity = identity.copy(userId = 18) } }
        assertRejected(retained)
        await { hasRenderedPlaylist() }
        onMain { assertSame(source.result, viewModel.highQualityPlaylist.value) }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun sameAccountReauthorizationRejectsTheOriginalCallbackAndRebindsTheEqualPlaylist() {
        showOriginalPlaylist()
        val retained = action()
        val owner = onMain { sessions.snapshot() }
        onMain { sessions.invalidate() }
        assertRejected(retained)
        await { hasRenderedPlaylist() }
        onMain {
            assertEquals(owner.identity, sessions.snapshot().identity)
            assertNotEquals(owner, sessions.snapshot())
            assertSame(source.result, viewModel.highQualityPlaylist.value)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun recoveryWithinTheSameStampRejectsTheOriginalCallbackAndRebindsTheEqualPlaylist() {
        showOriginalPlaylist()
        val retained = action()
        val owner = onMain { sessions.snapshot() }
        onMain {
            sessions.setRecoveryRequired(true)
            retained()
            assertEquals("fixture-root", navigator.currentRoute)
        }
        await {
            viewModel.playlistOwner.value == null && viewModel.highQualityPlaylist.value is Resource.Error &&
                renderedResource === viewModel.highQualityPlaylist.value && renderedOwner == null
        }
        onMain { sessions.setRecoveryRequired(false) }
        await { hasRenderedPlaylist() }
        onMain {
            assertEquals(owner, sessions.snapshot())
            assertSame(source.result, viewModel.highQualityPlaylist.value)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }

    @Test fun cachedSameCategoryOwnerChangeRebindsCallbacksWithoutAResourceOrCategoryChange() {
        showOriginalPlaylist()
        val retained = action()
        val before = onMain { Triple(viewModel.highQualityPlaylist.value, viewModel.selectedCategory.value, viewModel.playlistOwner.value) }
        val requests = onMain { source.requests.size }
        onMain { viewModel.onCategorySelected(before.second) }
        assertRejected(retained)
        await { hasRenderedPlaylist() && renderedOwner != before.third }
        onMain {
            assertSame(before.first, viewModel.highQualityPlaylist.value)
            assertEquals(before.second, viewModel.selectedCategory.value)
            assertEquals(before.third?.first, viewModel.playlistOwner.value?.first)
            assertEquals(requests, source.requests.size)
        }
        assertCurrentNavigation()
    }

    @Test fun returningToACachedCategoryDoesNotReviveItsOriginalCallback() {
        showOriginalPlaylist()
        val retained = action()
        val before = onMain { Triple(viewModel.highQualityPlaylist.value, viewModel.selectedCategory.value, viewModel.playlistOwner.value) }
        onMain { viewModel.onCategorySelected("华语") }
        await { hasRenderedPlaylist() && renderedCategory == "华语" }
        assertRejected(retained)
        val requests = onMain { source.requests.size }
        onMain { viewModel.onCategorySelected(before.second) }
        await { hasRenderedPlaylist() && renderedCategory == before.second && renderedOwner != before.third }
        onMain {
            assertSame(before.first, viewModel.highQualityPlaylist.value)
            assertEquals(requests, source.requests.size)
        }
        assertRejected(retained)
        assertCurrentNavigation()
    }
}
