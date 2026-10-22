package com.ljyh.mei.ui.screen.artist

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.AllArtistSongs
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.GetArtistAlbum
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.ArtistRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistAlbumsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val requests = mutableListOf<GetArtistAlbum>()
    private val responses = ArrayDeque<ArtistAlbum>()
    private lateinit var viewModel: ArtistAlbumsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, arguments ->
            check(method.name == "getArtistAlbums")
            assertEquals("42", arguments!![1])
            requests += arguments[0] as GetArtistAlbum
            responses.removeFirst()
        } as ApiService
        viewModel = ArtistAlbumsViewModel(ArtistRepository(api))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun overlappingPagesUseServerOffsetsAndIgnoreConcurrentOrFinishedLoads() = runTest(dispatcher) {
        responses += page(more = true, ids = listOf(1, 2))
        responses += page(more = false, ids = listOf(2, 3))

        viewModel.loadMore("42")
        assertTrue(viewModel.state.value.isLoading)
        viewModel.loadMore("42")
        awaitIdle()
        viewModel.loadMore("42")
        awaitIdle()
        viewModel.loadMore("42")

        assertEquals(listOf(0, 2), requests.map { it.offset })
        assertEquals(listOf(1L, 2L, 3L), viewModel.state.value.albums.map { it.id })
        assertEquals(4, viewModel.state.value.offset)
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun failedAppendKeepsExistingAlbumsAndRetriesTheSameOffset() = runTest(dispatcher) {
        responses += page(more = true, ids = listOf(1))
        responses += page(code = 500, more = true)
        responses += page(more = false, ids = listOf(2))

        viewModel.loadMore("42")
        awaitIdle()
        viewModel.loadMore("42")
        awaitIdle()

        assertEquals(listOf(1L), viewModel.state.value.albums.map { it.id })
        assertEquals(1, viewModel.state.value.offset)
        assertNotNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.hasMore)

        viewModel.loadMore("42")
        assertNull(viewModel.state.value.error)
        awaitIdle()

        assertEquals(listOf(0, 1, 1), requests.map { it.offset })
        assertEquals(listOf(1L, 2L), viewModel.state.value.albums.map { it.id })
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun emptyPageStopsLoadingEvenWhenServerReportsMore() = runTest(dispatcher) {
        responses += page(more = true)

        viewModel.loadMore("42")
        awaitIdle()
        viewModel.loadMore("42")

        assertEquals(1, requests.size)
        assertTrue(viewModel.state.value.albums.isEmpty())
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun searchSharesLoadedPagesAndRequiresContinueAfterThreeMorePages() = runTest(dispatcher) {
        val offsets = mutableListOf<Int>()
        val pages = mapOf(
            0 to page(more = true, ids = listOf(1, 2)),
            2 to page(more = true, ids = listOf(2, 3)),
            4 to page(more = true, ids = listOf(4)),
            5 to page(more = true, ids = listOf(5)),
            6 to page(more = false, ids = listOf(6)),
        )
        viewModel = ArtistAlbumsViewModel { _, offset ->
            offsets += offset
            Resource.Success(pages.getValue(offset))
        }
        viewModel.loadMore("42")
        advanceUntilIdle()

        viewModel.updateQuery("42", "  ALBUM 6  ")
        advanceUntilIdle()
        viewModel.loadMore("42")
        advanceUntilIdle()

        assertEquals(listOf(0, 2, 4, 5), offsets)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), viewModel.state.value.albums.map { it.id })
        assertTrue(viewModel.state.value.visibleAlbums.isEmpty())
        assertTrue(viewModel.state.value.hasMore)
        assertFalse(viewModel.state.value.isLoading)

        viewModel.continueSearch("42")
        advanceUntilIdle()

        assertEquals(listOf(0, 2, 4, 5, 6), offsets)
        assertEquals(listOf(6L), viewModel.state.value.visibleAlbums.map { it.id })
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun searchRetryPreservesPartialMatchesAndRawOffsetUntilAnEmptyPage() = runTest(dispatcher) {
        val offsets = mutableListOf<Int>()
        val pages = ArrayDeque<Resource<ArtistAlbum>>(
            listOf(
                Resource.Success(page(more = true, ids = listOf(1))),
                Resource.Success(page(more = true, ids = listOf(2))),
                Resource.Error("Unavailable"),
                Resource.Success(page(more = true, ids = listOf(2, 3))),
                Resource.Success(page(more = true)),
            ),
        )
        viewModel = ArtistAlbumsViewModel { _, offset ->
            offsets += offset
            pages.removeFirst()
        }
        viewModel.loadMore("42")
        advanceUntilIdle()
        viewModel.updateQuery("42", "album")
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), viewModel.state.value.visibleAlbums.map { it.id })
        assertEquals(2, viewModel.state.value.offset)
        assertEquals("Unavailable", viewModel.state.value.error)
        assertTrue(viewModel.state.value.hasMore)

        viewModel.continueSearch("42")
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2, 2, 4), offsets)
        assertEquals(listOf(1L, 2L, 3L), viewModel.state.value.visibleAlbums.map { it.id })
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun staleQueryAndArtistResponsesCannotReplaceTheActiveCatalog() = runTest(dispatcher) {
        val oldQuery = CompletableDeferred<Resource<ArtistAlbum>>()
        val oldArtist = CompletableDeferred<Resource<ArtistAlbum>>()
        var firstQuery = true
        viewModel = ArtistAlbumsViewModel { id, _ ->
            when {
                id == "42" && firstQuery -> {
                    firstQuery = false
                    withContext(NonCancellable) { oldQuery.await() }
                }
                id == "42" -> Resource.Success(page(more = false, ids = listOf(2)))
                id == "99" -> withContext(NonCancellable) { oldArtist.await() }
                else -> Resource.Success(page(more = false, ids = listOf(4)))
            }
        }
        viewModel.updateQuery("42", "album 1")
        advanceTimeBy(250)
        runCurrent()
        viewModel.updateQuery("42", "album 2")
        advanceUntilIdle()
        oldQuery.complete(Resource.Success(page(more = true, ids = listOf(1))))
        advanceUntilIdle()

        assertEquals("album 2", viewModel.state.value.query)
        assertEquals(listOf(2L), viewModel.state.value.albums.map { it.id })
        assertFalse(viewModel.state.value.hasMore)

        viewModel.loadMore("99")
        runCurrent()
        assertTrue(viewModel.state.value.albums.isEmpty())
        viewModel.updateQuery("100", "album 4")
        advanceUntilIdle()
        oldArtist.complete(Resource.Success(page(more = true, ids = listOf(3))))
        advanceUntilIdle()

        assertEquals("album 4", viewModel.state.value.query)
        assertEquals(listOf(4L), viewModel.state.value.albums.map { it.id })
        assertEquals(1, viewModel.state.value.offset)
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun songSearchDebouncesFiltersTitlesAndAlbumsAndResumesCatalogAfterClearing() = runTest(dispatcher) {
        val offsets = mutableListOf<Int>()
        val songs = ArtistSongsViewModel { _, offset ->
            offsets += offset
            Resource.Success(songPage(offset, more = offset < 4))
        }
        songs.loadMore("42")
        advanceUntilIdle()
        songs.updateQuery("42", "m")
        songs.updateQuery("42", "match")
        advanceTimeBy(249)
        assertEquals(listOf(0), offsets)
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2, 3), offsets)
        assertEquals(listOf(1L, 2L), songs.state.value.visibleSongs.map { it.id })
        assertEquals(4, songs.state.value.songs.size)
        assertTrue(songs.state.value.hasMore)

        songs.updateQuery("42", "")
        assertEquals(songs.state.value.songs, songs.state.value.visibleSongs)
        songs.loadMore("42")
        advanceUntilIdle()

        assertEquals(listOf(0, 1, 2, 3, 4), offsets)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), songs.state.value.songs.map { it.id })
        assertFalse(songs.state.value.hasMore)
    }

    private suspend fun awaitIdle() {
        viewModel.state.first { !it.isLoading }
    }

    private fun page(code: Int = 200, more: Boolean, ids: List<Int> = emptyList()): ArtistAlbum {
        val albums = ids.joinToString(",") { id ->
            """{"id":$id,"name":"Album $id","picUrl":"https://example.com/$id.jpg",
                "size":10,"artists":[{"id":42,"name":"Artist"}]}""".trimIndent()
        }
        return Gson().fromJson(
            """{"code":$code,"more":$more,"hotAlbums":[$albums]}""",
            ArtistAlbum::class.java,
        )
    }

    private fun songPage(id: Int, more: Boolean): AllArtistSongs {
        val title = if (id == 1) "Match song" else "Song $id"
        val album = if (id == 2) "Match album" else "Album $id"
        return Gson().fromJson(
            """{"code":200,"more":$more,"songs":[{"id":$id,"name":"$title","dt":1000,
                "ar":[{"id":42,"name":"Artist"}],"al":{"id":$id,"name":"$album","pic":1}}]}""",
            AllArtistSongs::class.java,
        )
    }
}
