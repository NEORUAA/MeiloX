package com.ljyh.mei.ui.screen.playlist

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.R
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.data.repository.CatalogCollectionBackend
import com.ljyh.mei.data.repository.PlaylistCollectionBackend
import com.ljyh.mei.data.repository.PlaylistMutationSource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.PlaylistTracksBackend
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.playback.DownloadSourceBackend
import com.ljyh.mei.ui.component.player.OverlayState
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.screen.playlist.component.PlaylistActionOverlay
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Original menu/picker chain; library reads and all mutations are closed substitutes. */
class PlaylistOverlayOwnerDeviceTest {
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var sessions: SessionStore
    private lateinit var model: PlaylistViewModel
    private lateinit var models: ViewModelStore
    private var identity = SessionIdentity(17, true, false)
    private val track = MediaMetadata(11, "Fixture song", "", emptyList(), 60_000, MediaMetadata.Album(1, "Fixture"))
    private val overlay = mutableStateOf<OverlayState>(OverlayState.TrackActionMenu(track))
    private val transitions = mutableListOf<OverlayState>()
    private val reads = mutableListOf<SessionStamp>()
    private var renderedOwner: SessionStamp? = null
    private var renderedOverlay: OverlayState? = null
    private var dismissed = 0

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unrelated fixture call: ${method.name}") } as T

    @Before fun showOriginalMenu() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            sessions = SessionStore().apply { bind { identity } }
            val api = unused<ApiService>()
            val repository = PlaylistRepository(api, unused<WeApiService>(), unused<PlaylistCollectionBackend>(), sessions,
                unused<CatalogCollectionBackend>(), unused<PlaylistTracksBackend>(), unused<DownloadSourceBackend>())
            val library = object : AccountLibrarySource {
                override val collectionChanges = emptyFlow<SessionStamp>()
                override fun playlists(accountId: String) = flowOf(emptyList<AccountPlaylist>())
                override suspend fun sync(stamp: SessionStamp): Resource<Unit> {
                    reads += stamp
                    return Resource.Success(Unit)
                }
                override suspend fun albums(stamp: SessionStamp) = error("Unrelated albums")
                override suspend fun photos(stamp: SessionStamp) = error("Unrelated photos")
                override suspend fun likedSongs(playlistId: String, stamp: SessionStamp) = error("Unrelated songs")
            }
            model = PlaylistViewModel(repository, unused<PlaylistMutationSource>(), repository,
                LocalPlaylistRepository(unused<PlaylistDao>()), api, sessions, library)
            models = ViewModelStore().apply { put("overlay", model) }
            activity.setContent {
                MaterialTheme {
                    GlassBackdropHost(
                        modifier = Modifier.fillMaxSize(),
                        sampledContent = { Box(Modifier.fillMaxSize().background(Color.White)) },
                        overlayContent = {
                            val owner = model.actionSession.collectAsState().value
                            val displayed = overlay.value
                            CompositionLocalProvider(LocalPlayerConnection provides null) {
                                PlaylistActionOverlay(displayed, false, 0,
                                    onDismiss = { dismissed++ },
                                    onUpdateOverlay = {
                                        transitions += it
                                        if (it !is OverlayState.Share) overlay.value = it
                                    }, viewModel = model)
                            }
                            SideEffect { renderedOwner = owner; renderedOverlay = displayed }
                        },
                    )
                }
            }
        }
        await { button(label(R.string.track_action_add_playlist)) != null }
    }

    @After fun closeFixture() {
        if (::scenario.isInitialized) {
            scenario.onActivity { if (::models.isInitialized) models.clear() }
            scenario.close()
        }
    }

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest && view.isAttachedToWindow) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }
    private fun button(text: String): SemanticsNode? = WindowInspector.getGlobalWindowViews()
        .flatMap(::roots).flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
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
        fail("Timed out waiting for the original overlay")
    }
    private fun action(id: Int): () -> Unit {
        await { button(label(id)) != null }
        return onMain {
            val element = checkNotNull(button(label(id))).layoutInfo.getModifierInfo().map { it.modifier }
                .single { it.javaClass.name == "androidx.compose.foundation.ClickableElement" }
            @Suppress("UNCHECKED_CAST")
            (element.javaClass.getDeclaredField("onClick").apply { isAccessible = true }.get(element) as () -> Unit)
        }
    }
    private fun replaceAccount(sameAccount: Boolean = false) {
        onMain { sessions.beginTransition().use { if (!sameAccount) identity = identity.copy(userId = 18) } }
        await { renderedOwner == sessions.snapshot() }
    }

    @Test fun obsoleteAddCallbackCannotOpenAPickerForAnotherAccount() {
        val retained = action(R.string.track_action_add_playlist)
        replaceAccount()
        val before = onMain { dismissed }
        onMain { retained() }
        await { dismissed > before }
        onMain { assertTrue(transitions.isEmpty()); assertTrue(reads.isEmpty()) }
    }

    @Test fun obsoleteShareCallbackCannotAdoptSameAccountReauthorization() {
        val retained = action(R.string.track_action_share)
        replaceAccount(sameAccount = true)
        val before = onMain { dismissed }
        onMain { retained() }
        await { dismissed > before }
        onMain { assertTrue(transitions.isEmpty()) }
    }

    @Test fun obsoletePickerCallbackCannotOpenCreateUnderAReplacement() {
        val add = action(R.string.track_action_add_playlist)
        onMain { add() }
        val retained = action(R.string.create_playlist_title)
        replaceAccount()
        onMain {
            val before = transitions.toList()
            retained()
            assertEquals(before, transitions)
        }
    }

    @Test fun queuedOverlayTypeChangeCannotRecaptureTheNewAccount() {
        val original = onMain { sessions.snapshot() }
        replaceAccount()
        onMain { overlay.value = OverlayState.AddToPlaylist(track) }
        await { renderedOverlay is OverlayState.AddToPlaylist }
        onMain { assertTrue(reads.none { it != original }); assertTrue(dismissed > 0) }
    }

    @Test fun currentMenuPickerAndCreateChainRetainsOneOwner() {
        val owner = onMain { sessions.snapshot() }
        val add = action(R.string.track_action_add_playlist)
        onMain { add() }
        await { renderedOverlay is OverlayState.AddToPlaylist && reads.isNotEmpty() }
        val create = action(R.string.create_playlist_title)
        onMain { create() }
        await { renderedOverlay == OverlayState.CreatePlaylist }
        onMain {
            assertEquals(listOf(OverlayState.AddToPlaylist(track), OverlayState.CreatePlaylist), transitions)
            assertEquals(listOf(owner), reads)
            assertEquals(1, dismissed)
        }
    }

    @Test fun currentMenuShareTransitionStillWorks() {
        val retained = action(R.string.track_action_share)
        onMain { retained() }
        await { transitions.isNotEmpty() }
        onMain { assertEquals(listOf(OverlayState.Share(track)), transitions) }
    }

    @Test fun reopeningAfterDismissCapturesTheReplacementOwner() {
        replaceAccount()
        onMain { overlay.value = OverlayState.None }
        await { renderedOverlay == OverlayState.None }
        onMain { overlay.value = OverlayState.TrackActionMenu(track) }
        val add = action(R.string.track_action_add_playlist)
        onMain { add() }
        await { reads.isNotEmpty() }
        onMain { assertEquals(listOf(sessions.snapshot()), reads) }
    }

}
