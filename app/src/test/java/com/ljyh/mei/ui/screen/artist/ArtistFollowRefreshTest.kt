package com.ljyh.mei.ui.screen.artist

import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.repository.ArtistRepository
import com.ljyh.mei.di.dao.ColorDao
import com.ljyh.mei.di.repository.ColorRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistFollowRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var songsRequests: Channel<PendingRead<ArtistSong>>
    private lateinit var albumsRequests: Channel<PendingRead<ArtistAlbum>>
    private lateinit var mutations: Channel<PendingMutation>
    private lateinit var viewModel: ArtistViewModel
    private var mutationCallCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        songsRequests = Channel(Channel.UNLIMITED)
        albumsRequests = Channel(Channel.UNLIMITED)
        mutations = Channel(Channel.UNLIMITED)
        mutationCallCount = 0

        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, arguments ->
            assertEquals("42", arguments!![1])
            when (method.name) {
                "getArtistSongs" -> {
                    val read = PendingRead<ArtistSong>()
                    check(songsRequests.trySend(read).isSuccess)
                    awaitResponse(read, arguments.last())
                }
                "getArtistAlbums" -> {
                    val read = PendingRead<ArtistAlbum>()
                    check(albumsRequests.trySend(read).isSuccess)
                    awaitResponse(read, arguments.last())
                }
                else -> error("Unexpected API request: ${method.name}")
            }
        } as ApiService
        val colorDao = Proxy.newProxyInstance(
            ColorDao::class.java.classLoader,
            arrayOf(ColorDao::class.java),
        ) { _, method, _ ->
            error("Unexpected color request: ${method.name}")
        } as ColorDao
        viewModel = ArtistViewModel(ArtistRepository(api), ColorRepository(colorDao)) { id, followed ->
            mutationCallCount++
            val mutation = PendingMutation(id, followed)
            mutations.send(mutation)
            mutation.completion.await()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test(timeout = 10_000)
    fun oldSongReadCannotOverrideSuccessfulFollowOrFreshAlbumRead() = runTest(dispatcher) {
        viewModel.getArtistSongs("42")
        val oldSongs = songsRequests.receive()

        viewModel.setArtistFollowed(42, true)
        val follow = mutations.receive()
        assertEquals(42L, follow.id)
        assertEquals(true, follow.followed)
        follow.completion.complete(Unit)
        viewModel.followMutation.first { it is Resource.Success }
        assertEquals(true, viewModel.confirmedFollowed.value)

        oldSongs.response.complete(songs(followed = false, name = "Old songs"))
        viewModel.artistSongs.first { it is Resource.Success }
        assertEquals(true, viewModel.confirmedFollowed.value)

        viewModel.getArtistAlbums("42")
        val freshAlbums = albumsRequests.receive()
        freshAlbums.response.complete(albums(followed = true))
        viewModel.artistAlbums.first { it is Resource.Success }
        assertEquals(true, viewModel.confirmedFollowed.value)
    }

    @Test(timeout = 10_000)
    fun freshSongReadRefreshesConfirmedStateAfterSuccessfulFollow() = runTest(dispatcher) {
        viewModel.setArtistFollowed(42, true)
        val follow = mutations.receive()
        follow.completion.complete(Unit)
        viewModel.followMutation.first { it is Resource.Success }
        assertEquals(true, viewModel.confirmedFollowed.value)

        viewModel.getArtistSongs("42")
        val freshSongs = songsRequests.receive()
        freshSongs.response.complete(songs(followed = false))
        viewModel.artistSongs.first { it is Resource.Success }
        assertEquals(false, viewModel.confirmedFollowed.value)
    }

    @Test(timeout = 10_000)
    fun readDuringFailedUnfollowKeepsConfirmedStateAndDoubleTapMakesOneMutation() =
        runTest(dispatcher) {
            viewModel.getArtistSongs("42")
            songsRequests.receive().response.complete(songs(followed = true))
            viewModel.artistSongs.first { it is Resource.Success }
            assertEquals(true, viewModel.confirmedFollowed.value)

            viewModel.setArtistFollowed(42, false)
            viewModel.setArtistFollowed(42, false)
            val unfollow = mutations.receive()
            runCurrent()
            assertEquals(false, unfollow.followed)
            assertEquals(1, mutationCallCount)

            viewModel.getArtistSongs("42")
            songsRequests.receive().response.complete(songs(followed = false))
            viewModel.artistSongs.first { it is Resource.Success }
            assertEquals(true, viewModel.confirmedFollowed.value)

            unfollow.completion.completeExceptionally(IllegalStateException("Unfollow failed"))
            viewModel.followMutation.first { it is Resource.Error }
            assertEquals(true, viewModel.confirmedFollowed.value)
            assertEquals(1, mutationCallCount)
        }

    @Test(timeout = 10_000)
    fun olderSongReloadCannotReplaceNewerFollowStateOrSourceData() = runTest(dispatcher) {
        viewModel.getArtistSongs("42")
        val oldJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single()
        val oldSongs = songsRequests.receive()

        viewModel.getArtistSongs("42")
        val newSongs = songsRequests.receive()
        newSongs.response.complete(songs(followed = true, name = "New songs"))
        viewModel.artistSongs.first { it is Resource.Success }
        assertEquals(true, viewModel.confirmedFollowed.value)

        oldSongs.response.complete(songs(followed = false, name = "Old songs"))
        oldJob.join()
        val source = viewModel.artistSongs.value as Resource.Success<ArtistSong>
        assertEquals("New songs", source.data.artist.name)
        assertEquals(true, source.data.artist.followed)
        assertEquals(true, viewModel.confirmedFollowed.value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> awaitResponse(read: PendingRead<T>, continuation: Any?): Any {
        val await: suspend () -> T = { read.response.await() }
        await.startCoroutine(continuation as Continuation<T>)
        return COROUTINE_SUSPENDED
    }

    private fun songs(followed: Boolean, name: String = "Artist"): ArtistSong = Gson().fromJson(
        """{"artist":{"id":42,"name":"$name","followed":$followed},
            "hotSongs":[],"more":false,"code":200}""".trimIndent(),
        ArtistSong::class.java,
    )

    private fun albums(followed: Boolean): ArtistAlbum = Gson().fromJson(
        """{"artist":{"id":42,"name":"Artist","followed":$followed},
            "hotAlbums":[],"more":false,"code":200}""".trimIndent(),
        ArtistAlbum::class.java,
    )

    private class PendingRead<T> {
        val response = CompletableDeferred<T>()
    }

    private class PendingMutation(val id: Long, val followed: Boolean) {
        val completion = CompletableDeferred<Unit>()
    }
}
