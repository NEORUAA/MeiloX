package com.ljyh.mei.ui.screen.artist

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.GetArtistAlbum
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.ArtistRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
}
