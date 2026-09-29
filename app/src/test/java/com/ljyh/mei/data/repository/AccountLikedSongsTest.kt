package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.PlaylistDetail
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.api.GetSongDetails
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.EApiService
import com.ljyh.mei.data.network.api.WeApiService
import com.ljyh.mei.di.dao.PlaylistDao
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.data.session.SessionStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountLikedSongsTest {
    private val sessions = SessionStore().apply { bind { SessionIdentity(1, true, false) } }
    private val owner = sessions.snapshot()
    private val membership = AccountPlaylist(Playlist("10", "Any title", "", "1", "Creator", "", 405), true)
    private val entries = MutableStateFlow(listOf(membership))
    private val calls = mutableListOf<String>()
    private var ids = (1..405).toList()
    private var creator = 1L
    private var afterDetail: () -> Unit = {}
    private var load: (List<Int>) -> List<PlaylistDetail.Playlist.Track> = { it.reversed().map(::track) }

    private inline fun <reified T> proxy(noinline invoke: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, args -> invoke(method.name, args.orEmpty()) } as T

    private fun repository(): AccountLibraryRepository {
        val api = proxy<ApiService> { name, args ->
            assertEquals(owner, args[1])
            sessions.requireCurrent(args[1] as SessionStamp)
            calls += name
            when (name) {
                "getPlaylistDetail" -> Gson().fromJson(
                    """{"code":200,"playlist":{"id":10,"creator":{"userId":$creator},"tracks":[],"trackIds":[${ids.joinToString(",") { "{\"id\":$it}" }}]}}""",
                    PlaylistDetail::class.java,
                ).also { afterDetail() }
                "getSongDetail" -> {
                    val requested = JsonParser.parseString((args[0] as GetSongDetails).c).asJsonArray.map { it.asJsonObject["id"].asInt }
                    Tracks(200, emptyList(), load(requested))
                }
                else -> error("Unexpected $name")
            }
        }
        val weapi = proxy<WeApiService> { _, _ -> error("Unexpected WEAPI") }
        val eapi = proxy<EApiService> { _, _ -> error("Unexpected EAPI") }
        val local = LocalPlaylistRepository(proxy<PlaylistDao> { name, args ->
            assertEquals("getAccountPlaylists", name)
            assertEquals("1", args[0])
            entries
        })
        val collections = PlaylistCollectionBackend { _, _, _ -> error("Unexpected playlist collection") }
        val catalog = proxy<CatalogCollectionBackend> { _, _ -> error("Unused catalog") }
        return AccountLibraryRepository(UserRepository(api, eapi, weapi), local, PlaylistRepository(api, weapi, collections, sessions, catalog), sessions)
    }

    @Test fun everyPageUsesCapturedOwnerAndReturnsTheCompleteOrderedList() = runBlocking {
        val result = repository().likedSongs("10", owner) as Resource.Success
        assertEquals(ids.map(Int::toLong), result.data.map { it.id })
        assertTrue(result.data.all { it.tns == null })
        assertEquals(3, calls.count { it == "getSongDetail" })
    }

    @Test fun missingTracksNeverBecomeSuccessfulPartialLibraryResults() = runBlocking {
        load = { requested -> requested.dropLast(1).map(::track) }
        assertTrue(repository().likedSongs("10", owner) is Resource.Error)
    }

    @Test fun noSongsIsAValidCompleteLibraryResult() = runBlocking {
        ids = emptyList()
        val result = repository().likedSongs("10", owner) as Resource.Success
        assertTrue(result.data.isEmpty())
        assertEquals(listOf("getPlaylistDetail"), calls)
    }

    @Test fun guestForeignAndUnmarkedMembershipsNeverDispatch() = runBlocking {
        val source = repository()
        assertTrue(source.likedSongs("10", owner.copy(identity = SessionIdentity(0, false, true))) is Resource.Error)
        for (entry in listOf(membership.copy(isLiked = false), membership.copy(playlist = membership.playlist.copy(author = "2")))) {
            entries.value = listOf(entry)
            assertTrue(source.likedSongs("10", owner) is Resource.Error)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun changedServerOwnershipIsRejectedBeforeLoadingSongDetails() = runBlocking {
        creator = 2L
        assertTrue(repository().likedSongs("10", owner) is Resource.Error)
        assertEquals(listOf("getPlaylistDetail"), calls)
    }

    @Test fun invalidationBetweenDetailAndPagesStopsTheRead() = runBlocking {
        afterDetail = { sessions.invalidate() }
        assertTrue(repository().likedSongs("10", owner) is Resource.Error)
        assertEquals(listOf("getPlaylistDetail"), calls)
    }

    @Test fun invalidationBetweenPagesRejectsTheOldOwner() = runBlocking {
        load = { requested -> sessions.invalidate(); requested.map(::track) }
        assertTrue(repository().likedSongs("10", owner) is Resource.Error)
        assertEquals(1, calls.count { it == "getSongDetail" })
    }

    @Test fun canceledLikedReadsDoNotFallBackToInitialTracks() = runBlocking {
        load = { throw CancellationException() }
        assertTrue(runCatching { repository().likedSongs("10", owner) }.exceptionOrNull() is CancellationException)
    }

    private fun track(id: Int): PlaylistDetail.Playlist.Track = Gson().fromJson(
        """{"id":$id,"name":"Song $id","al":{"id":1,"name":"Album","picUrl":""},"ar":[],"dt":1000,"tns":[]}""",
        PlaylistDetail.Playlist.Track::class.java,
    )
}
