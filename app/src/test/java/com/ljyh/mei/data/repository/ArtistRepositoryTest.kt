package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.api.AllArtistSongs
import com.ljyh.mei.data.model.api.ArtistAlbum
import com.ljyh.mei.data.model.api.ArtistCollectionResponse
import com.ljyh.mei.data.model.api.ArtistDetail
import com.ljyh.mei.data.model.api.ArtistSong
import com.ljyh.mei.data.model.api.BaseResponse
import com.ljyh.mei.data.model.api.GetAllArtistSongs
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.parasite.HostSessionBridge
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtistRepositoryTest {
    private var identity = HostSessionIdentity(1, true, false)
    private val sessions = HostSessionBridge().apply { bind { identity } }
    private val owner = sessions.snapshot()
    private val calls = mutableListOf<Pair<String, Any?>>()
    private var detail = artistDetail("10")
    private var albums = artistAlbums("10")
    private var hotSongs = artistHotSongs("10")
    private var songs = AllArtistSongs(200, listOf(artistTrack(100)), false)
    private var followed = ArtistCollectionResponse(200, ArtistCollectionResponse.Data(ArtistCollectionResponse.Artist(10, false)))
    private var mutation = BaseResponse(200)
    private var afterCall: () -> Unit = {}
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        val parameters = args.orEmpty()
        val tag = parameters.filterIsInstance<HostSessionStamp>().single()
        assertEquals(sessions.snapshot(), tag)
        calls += method.name to parameters.firstOrNull()
        when (method.name) {
            "getArtistDetail" -> detail
            "getArtistAlbums" -> albums
            "getArtistSongs" -> hotSongs
            "getAllArtistSongs" -> songs
            "getArtistCollection" -> followed
            "subscribeArtist", "unsubscribeArtist" -> mutation
            else -> error("Unexpected ${method.name}")
        }.also { afterCall() }
    } as ApiService
    private val repository = ArtistRepository(api, sessions)

    @Test fun allReadsUseTheCapturedSessionAndExplicitCatalogParameters() = runBlocking {
        assertTrue(repository.detail("10", owner) is Resource.Success)
        assertTrue(repository.albums("10", owner) is Resource.Success)
        assertTrue(repository.hotSongs("10", owner) is Resource.Success)
        assertTrue(repository.songs("10", 100, owner) is Resource.Success)
        val request = calls.last().second as GetAllArtistSongs
        assertEquals(GetAllArtistSongs("10", 100, 100, "hot", true, 1), request)
    }

    @Test fun collectionReadsUseArtistNotAssociatedUserFollowState() = runBlocking {
        assertEquals(Resource.Success(false), repository.followed("10", owner))
        assertEquals(mapOf("artistId" to "10"), calls.single().second)
        followed = followed.copy(data = ArtistCollectionResponse.Data(ArtistCollectionResponse.Artist(10, true)))
        assertEquals(Resource.Success(true), repository.followed("10", owner))
    }

    @Test fun subscribeAndUnsubscribeHaveDifferentExactPayloads() = runBlocking {
        assertEquals(Resource.Success(Unit), repository.follow("10", true, owner))
        assertEquals(Resource.Success(Unit), repository.follow("10", false, owner))
        assertEquals(listOf("subscribeArtist" to mapOf("artistId" to "10"), "unsubscribeArtist" to mapOf("artistIds" to "[10]")), calls)
    }

    @Test fun missingCollectionFlagsWrongArtistsAndBusinessFailuresAreNotFalse() = runBlocking {
        for (response in listOf(
            ArtistCollectionResponse(301, null), ArtistCollectionResponse(200, null),
            ArtistCollectionResponse(200, ArtistCollectionResponse.Data(ArtistCollectionResponse.Artist(11, false))),
            ArtistCollectionResponse(200, ArtistCollectionResponse.Data(ArtistCollectionResponse.Artist(10, null))),
        )) {
            followed = response
            assertTrue(repository.followed("10", owner) is Resource.Error)
        }
        mutation = BaseResponse(500)
        assertTrue(repository.follow("10", true, owner) is Resource.Error)
    }

    @Test fun missingListsAndEmptyNonterminalPagesCannotMasqueradeAsCompletion() = runBlocking {
        for (response in listOf(AllArtistSongs(500, emptyList(), false), AllArtistSongs(200, null, false), AllArtistSongs(200, emptyList(), true))) {
            songs = response
            assertTrue(repository.songs("10", 0, owner) is Resource.Error)
        }
        songs = AllArtistSongs(200, emptyList(), false)
        assertTrue(repository.songs("10", 0, owner) is Resource.Success)
        albums = albums.copy(hotAlbums = null)
        hotSongs = hotSongs.copy(hotSongs = null)
        assertTrue(repository.albums("10", owner) is Resource.Error)
        assertTrue(repository.hotSongs("10", owner) is Resource.Error)
    }

    @Test fun detailAndCatalogIdentityFailuresAreRejected() = runBlocking {
        detail = artistDetail("11")
        albums = artistAlbums("11")
        hotSongs = artistHotSongs("11")
        assertTrue(repository.detail("10", owner) is Resource.Error)
        assertTrue(repository.albums("10", owner) is Resource.Error)
        assertTrue(repository.hotSongs("10", owner) is Resource.Error)
        detail = artistDetail("10").copy(code = 500)
        assertTrue(repository.detail("10", owner) is Resource.Error)
        detail = ArtistDetail(200, null, null)
        assertTrue(repository.detail("10", owner) is Resource.Success)
    }

    @Test fun invalidOrDuplicateRowsFailTheWholePage() = runBlocking {
        songs = AllArtistSongs(200, listOf(artistTrack(100), artistTrack(100)), true)
        assertTrue(repository.songs("10", 0, owner) is Resource.Error)
        songs = AllArtistSongs(200, listOf(artistTrack(0)), false)
        assertTrue(repository.songs("10", 0, owner) is Resource.Error)
    }

    @Test fun guestsDoNotReadPrivateStateOrWrite() = runBlocking {
        identity = HostSessionIdentity(0, false, true)
        val guest = sessions.snapshot()
        assertEquals(Resource.Success(false), repository.followed("10", guest))
        assertTrue(repository.follow("10", true, guest) is Resource.Error)
        assertTrue(calls.isEmpty())
        assertTrue(repository.hotSongs("10", guest) is Resource.Success)
    }

    @Test fun invalidAndStaleInputsNeverDispatch() = runBlocking {
        assertTrue(repository.detail("bad", owner) is Resource.Error)
        assertTrue(repository.songs("10", -1, owner) is Resource.Error)
        sessions.invalidate()
        assertTrue(repository.detail("10", owner) is Resource.Error)
        assertTrue(repository.follow("10", true, owner) is Resource.Error)
        assertTrue(calls.isEmpty())
    }

    @Test fun accountChangesAndCancellationAfterResponsesAreRejected() = runBlocking {
        afterCall = { throw CancellationException() }
        assertTrue(runCatching { repository.songs("10", 0, owner) }.exceptionOrNull() is CancellationException)
        afterCall = sessions::invalidate
        assertTrue(repository.follow("10", true, owner) is Resource.Error)
    }
}

internal fun artistDetail(id: String): ArtistDetail = Gson().fromJson(
    """{"code":200,"data":{"artist":{"id":$id,"name":"Artist $id"},"user":{"followed":true}}}""", ArtistDetail::class.java,
)
internal fun artistAlbums(id: String): ArtistAlbum = Gson().fromJson(
    """{"code":200,"artist":{"id":$id},"hotAlbums":[],"more":false}""", ArtistAlbum::class.java,
)
internal fun artistHotSongs(id: String): ArtistSong = Gson().fromJson(
    """{"code":200,"artist":{"id":$id},"hotSongs":[],"more":false}""", ArtistSong::class.java,
)
internal fun artistTrack(id: Long): ArtistSong.HotSong = Gson().fromJson(
    """{"id":$id,"name":"Track $id","ar":[{"id":1,"name":"Artist"}],"al":{"id":2,"name":"Album","pic":1},"dt":1000}""", ArtistSong.HotSong::class.java,
)
