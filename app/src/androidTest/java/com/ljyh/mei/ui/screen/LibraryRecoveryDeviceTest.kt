package com.ljyh.mei.ui.screen

import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.data.model.weapi.EveryDaySongs
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.data.repository.AccountLibrarySource
import com.ljyh.mei.data.repository.PlaylistPageSource
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.PodcastSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.ui.screen.playlist.PlaylistViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastDetailViewModel
import com.ljyh.mei.ui.screen.podcast.PodcastViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Actual Android ViewModels with private sessions, synthetic sources and closed dependencies. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRecoveryDeviceTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(17, true, false) } }
    private val unexpected = mutableListOf<String>()

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args ->
        if (method.name == "touchAccountPlaylist") {
            @Suppress("UNCHECKED_CAST")
            (args[3] as () -> Unit)()
            Unit
        } else {
            unexpected += method.name
            error("Closed dependency: ${method.name}")
        }
    } as T

    @Test fun playlistRecoveryBlocksReadsAndCapturedActionsWithoutChangingTheStamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val reads = mutableListOf<String>()
        var dailyReads = 0
        var writes = 0
        val source = object : PlaylistPageSource {
            override suspend fun getPlaylistDetail(id: String, session: SessionStamp?): Resource<PlaylistDetail> {
                reads += id
                return Resource.Success(Gson().fromJson(
                    """{"code":200,"playlist":{"id":$id,"subscribed":false,"creator":{"userId":99},"tracks":[],"trackIds":[]}}""",
                    PlaylistDetail::class.java,
                ))
            }

            override suspend fun getEveryDayRecommendSongs(session: SessionStamp): Resource<EveryDaySongs> {
                dailyReads++
                return Resource.Success(Gson().fromJson("""{"code":200,"data":{"dailySongs":[]}}""", EveryDaySongs::class.java))
            }

            override suspend fun getPlaylistTrackDetails(ids: List<String>, session: SessionStamp?) = error("Closed track source")
            override suspend fun subscribePlaylist(id: String, session: SessionStamp?): Resource<BaseResponse> {
                writes++
                return Resource.Success(BaseResponse(200))
            }
            override suspend fun unSubscribePlaylist(id: String, session: SessionStamp?) = subscribePlaylist(id, session)
        }
        val api = unused<ApiService>()
        val remote = PlaylistRepository(api, unused<WeApiService>(), unused(), sessions, unused(), unused(), unused())
        val library = object : AccountLibrarySource {
            override val collectionChanges = emptyFlow<SessionStamp>()
            override fun playlists(accountId: String) = emptyFlow<List<com.ljyh.mei.data.model.room.AccountPlaylist>>()
            override suspend fun sync(stamp: SessionStamp): Resource<Unit> = error("Closed library source")
            override suspend fun albums(stamp: SessionStamp) = error("Closed library source")
            override suspend fun photos(stamp: SessionStamp) = error("Closed library source")
            override suspend fun likedSongs(playlistId: String, stamp: SessionStamp) = error("Closed library source")
        }
        try {
            sessions.setRecoveryRequired(true)
            val owner = sessions.snapshot()
            val model = PlaylistViewModel(source, remote, remote, LocalPlaylistRepository(unused<PlaylistDao>()), api, sessions, library)
            store.put("playlist", model)
            runCurrent()
            model.getPlaylistDetail("10")
            model.getPlaylistDetail("20")
            model.getEveryDayRecommendSongs()
            runCurrent()
            assertTrue(reads.isEmpty())
            assertEquals(0, dailyReads)
            assertNull(model.detailSession.value)
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(listOf("20"), reads)
            assertEquals(1, dailyReads)
            val detail = model.playlistDetail.value
            val daily = model.everyDay.value
            model.subscribePlaylist("20")
            model.getEveryDayRecommendSongs()
            sessions.setRecoveryRequired(true)
            assertTrue(runCatching { model.requireDetail(owner, detail) }.isFailure)
            assertTrue(model.dailyTracks(owner, daily).isEmpty())
            assertTrue(runCatching { model.getSongDetails(listOf("1"), owner) }.isFailure)
            assertTrue(runCatching { model.resolveDownloadSources(listOf("1"), MusicQuality.STANDARD, owner) }.isFailure)
            assertEquals("", model.userId)
            runCurrent()
            assertEquals(0, writes)
            assertEquals(1, dailyReads)
            assertTrue(unexpected.isEmpty())
            assertNull(model.collected.value)
            assertNull(model.dailySession.value)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class PodcastFixture : PodcastSource {
        var homeReads = 0
        val detailReads = mutableListOf<Long>()
        var categoryReads = 0
        var writes = 0
        var page: suspend () -> PodcastProgramPage = { PodcastProgramPage(emptyList(), false, 0) }
        var write: suspend () -> Unit = {}
        override suspend fun podcastHome(session: SessionStamp): PodcastHome {
            homeReads++
            return PodcastHome(emptyList(), emptyList(), emptyList())
        }
        override suspend fun podcasts(session: SessionStamp, categoryId: Long, offset: Int, limit: Int): List<Podcast> {
            categoryReads++
            return emptyList()
        }
        override suspend fun podcastDetail(session: SessionStamp, id: Long, offset: Int, limit: Int): PodcastDetail {
            detailReads += id
            return PodcastDetail(podcast(id), listOf(program(id)), id == 1L, 2)
        }
        override suspend fun podcastPrograms(session: SessionStamp, id: Long, offset: Int, limit: Int) = page()
        override suspend fun subscribedPodcasts(session: SessionStamp, offset: Int, limit: Int) = PodcastPage(emptyList(), false, 0)
        override suspend fun setPodcastSubscribed(session: SessionStamp, id: Long, subscribed: Boolean) {
            writes++
            write()
        }
    }

    private fun withPodcasts(check: suspend TestScope.(PodcastViewModel, PodcastDetailViewModel, PodcastFixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val accounts = AccountStore(sessions, {
            AccountProfile(17, "Fixture", null, null, null, null, null, null, null, null)
        }, backgroundScope)
        try {
            val source = PodcastFixture()
            val list = PodcastViewModel(source, accounts)
            val detail = PodcastDetailViewModel(source, accounts)
            store.put("list", list)
            store.put("detail", detail)
            check(list, detail, source)
        } finally {
            store.clear()
            accounts.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun podcastRecoveryResumesTheLatestDetailAndImmediatelyRejectsCachedPrograms() {
        sessions.setRecoveryRequired(true)
        withPodcasts { list, detail, source ->
            val owner = sessions.snapshot()
            runCurrent()
            list.refresh()
            detail.load(1)
            detail.load(2, true)
            runCurrent()
            assertEquals(0, source.homeReads)
            assertTrue(source.detailReads.isEmpty())
            sessions.setRecoveryRequired(false)
            runCurrent()
            assertEquals(owner, sessions.snapshot())
            assertEquals(1, source.homeReads)
            assertEquals(listOf(2L), source.detailReads)
            assertEquals(2L, detail.allPrograms(2).single().id)
            list.selectCategory(1)
            detail.toggleSubscription()
            sessions.setRecoveryRequired(true)
            assertTrue(runCatching { detail.allPrograms(2) }.isFailure)
            runCurrent()
            assertEquals(0, source.categoryReads)
            assertEquals(0, source.writes)
            assertNull(list.state.value.home)
            assertNull(detail.state.value.detail)
        }
    }

    @Test fun podcastRecoveryRetiresNonCooperativeBulkPagesAndSubscriptionResults() = withPodcasts { _, detail, source ->
        val page = CompletableDeferred<PodcastProgramPage>()
        val write = CompletableDeferred<Unit>()
        source.page = { withContext(NonCancellable) { page.await() } }
        source.write = { withContext(NonCancellable) { write.await() } }
        detail.load(1)
        runCurrent()
        val bulk = async { runCatching { detail.allPrograms(1) } }
        try {
            detail.toggleSubscription()
            runCurrent()
            assertEquals(1, source.writes)
            sessions.setRecoveryRequired(true)
            runCurrent()
            detail.load(2, true)
            runCurrent()
        } finally {
            page.complete(PodcastProgramPage(listOf(program(2)), false, 2))
            write.complete(Unit)
            runCurrent()
        }
        assertTrue(bulk.await().isFailure)
        assertEquals(listOf(1L), source.detailReads)
        assertNull(detail.state.value.detail)
        assertFalse(detail.state.value.isUpdatingSubscription)
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(listOf(1L, 2L), source.detailReads)
        assertTrue(detail.state.value.detail!!.podcast.isSubscribed)
    }

    companion object {
        private fun podcast(id: Long) = Podcast(id, "Fixture $id", null, null, null, null, null, null, 0, 0, 0, null, true, null)
        private fun program(id: Long) = PodcastProgram(id, "Fixture $id", null, null, null, 1000, 0, 0, 0, null, 1, "Fixture", null, id)
    }
}
