package com.ljyh.mei.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.google.gson.JsonObject
import com.google.gson.Gson
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.constants.UserAvatarUrlKey
import com.ljyh.mei.constants.UserNicknameKey
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.UserAlbumList
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.network.NETEASE_EXPECTED_SESSION_HEADER
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.ui.model.Album
import com.ljyh.mei.ui.model.toAlbum
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class LibraryAccount(val userId: String, private val credential: String) {
    val sessionFingerprint: String get() = neteaseSessionFingerprint(credential)
    override fun toString(): String = "LibraryAccount(userId=$userId)"
}

data class LibraryCollectionPage<T>(
    val items: List<T>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

@Singleton
class LibraryCollectionsRepository @Inject constructor(
    private val api: ApiService,
    @Named("MeloXWeapi") private val weapi: MeloXDirectService,
    @Named("MeloXEapi") private val eapi: MeloXDirectService,
    @ApplicationContext private val context: Context,
) {
    private val profileMutex = Mutex()
    private val likedPlaylists = LibraryLikedPlaylistCache()
    private val albumWriteMutex = Mutex()
    private val albumWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val accounts = context.dataStore.data.map { preferences ->
        val userId = preferences[UserIdKey].orEmpty()
        val credential = preferences[CookieKey].orEmpty()
        if (userId.toLongOrNull()?.let { it > 0 } == true && credential.isNotBlank()) {
            LibraryAccount(userId, credential)
        } else null
    }.distinctUntilChanged()

    private val _albumChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val albumChanges = _albumChanges.asSharedFlow()

    suspend fun restoreAccountIfNeeded() = profileMutex.withLock {
        val saved = context.dataStore.data.first()
        val cookie = saved[CookieKey].orEmpty()
        if (cookie.isBlank() || saved[UserIdKey]?.toLongOrNull()?.let { it > 0 } == true) return@withLock
        try {
            val response = api.getAccountDetail()
            val profile = response.profile
            if (response.code != 200 || profile == null || profile.userId <= 0) return@withLock
            context.dataStore.edit { current ->
                if (current[CookieKey] == cookie && current[UserIdKey]?.toLongOrNull()?.let { it > 0 } != true) {
                    current[UserIdKey] = profile.userId.toString()
                    current[UserNicknameKey] = profile.nickname
                    current[UserAvatarUrlKey] = profile.avatarUrl
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed profile restore leaves the normal sign-in entry available.
        }
    }

    suspend fun albums(account: LibraryAccount, offset: Int): LibraryCollectionPage<Album> {
        ensureAccount(account)
        val response = Gson().fromJson(
            readRequest("/api/album/sublist", mapOf("limit" to 50, "offset" to offset, "total" to true)),
            UserAlbumList::class.java,
        )
        ensureAccount(account)
        check(response.code == 200) { "NetEase album request failed (${response.code})" }
        val albums = response.data
        return LibraryCollectionPage(
            items = albums.map { it.toAlbum() },
            nextOffset = offset + albums.size,
            hasMore = response.hasMore && albums.isNotEmpty(),
        )
    }

    suspend fun playlists(account: LibraryAccount, offset: Int, limit: Int = 100): LibraryCollectionPage<UserPlaylist.Playlist> {
        ensureAccount(account)
        val response = Gson().fromJson(
            readRequest("/api/user/playlist", mapOf("uid" to account.userId, "limit" to limit, "offset" to offset)),
            UserPlaylist::class.java,
        )
        ensureAccount(account)
        check(response.code == 200) { "NetEase playlist request failed (${response.code})" }
        return LibraryCollectionPage(
            items = response.playlist,
            nextOffset = offset + response.playlist.size,
            hasMore = response.more,
        )
    }

    /** Resolve songs independently of the library's playlist/database synchronization. */
    suspend fun likedPlaylistId(account: LibraryAccount): Long? {
        ensureAccount(account)
        val id = likedPlaylists.resolve(account) { offset, limit -> playlists(account, offset, limit) }
        ensureAccount(account)
        return id
    }

    suspend fun allPlaylists(account: LibraryAccount): List<UserPlaylist.Playlist> =
        collectLibraryPages(key = { it.id.toString() }) { offset -> playlists(account, offset) }

    suspend fun albumSubscription(account: LibraryAccount, id: Long): Boolean = albumWriteMutex.withLock {
        ensureAccount(account)
        val response = readRequest("/api/album/detail/dynamic", mapOf("id" to id))
        ensureAccount(account)
        response.requireSuccess()
        response.get("isSub")?.takeUnless { it.isJsonNull }?.asBoolean
            ?: error("NetEase album subscription status is unavailable")
    }

    suspend fun setAlbumSubscription(account: LibraryAccount, id: Long, subscribed: Boolean) {
        // Accepted writes continue after the detail screen leaves composition.
        albumWriteScope.async(start = CoroutineStart.UNDISPATCHED) {
            albumWriteMutex.withLock {
                ensureAccount(account)
                val response = weapi.post(
                    if (subscribed) "/weapi/album/sub" else "/weapi/album/unsub",
                    mapOf("id" to id),
                    headers = mapOf(NETEASE_EXPECTED_SESSION_HEADER to account.sessionFingerprint),
                )
                ensureAccount(account)
                response.requireSuccess()
                _albumChanges.emit(Unit)
            }
        }.await()
    }

    private suspend fun ensureAccount(expected: LibraryAccount) {
        if (accounts.first() != expected) throw CancellationException("Library account changed")
    }

    private suspend fun readRequest(path: String, body: Map<String, Any>): JsonObject = try {
        weapi.post(path.replaceFirst("/api/", "/weapi/"), body).also { it.requireSuccess() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        eapi.post(path.replaceFirst("/api/", "/eapi/"), body).also { it.requireSuccess() }
    }
}

/** Retain one session's resolved ID, without retaining credentials for past accounts. */
internal class LibraryLikedPlaylistCache {
    private val mutex = Mutex()
    private var cached: Pair<LibraryAccount, Long>? = null

    suspend fun resolve(
        account: LibraryAccount,
        loadPage: suspend (offset: Int, limit: Int) -> LibraryCollectionPage<UserPlaylist.Playlist>,
    ): Long? = mutex.withLock {
        cached?.takeIf { it.first == account }?.let { return@withLock it.second }
        cached = null
        // The liked playlist is normally first; continue if the server orders it later.
        var offset = 0
        var page = loadPage(offset, 1)
        val seen = mutableSetOf<Long>()
        while (true) {
            page.items.firstOrNull { it.specialType == 5 && it.creator.userId.toString() == account.userId }
                ?.let { playlist ->
                    cached = account to playlist.id
                    return@withLock playlist.id
                }
            if (!page.hasMore) break
            val added = page.items.count { seen.add(it.id) }
            check(added > 0 && page.nextOffset > offset) { "NetEase playlist pagination did not advance" }
            offset = page.nextOffset
            page = loadPage(offset, 100)
        }
        null
    }
}

/** Keep raw offsets while rejecting a continuation that repeats the same page. */
internal suspend fun <T> collectLibraryPages(
    key: (T) -> String,
    loadPage: suspend (Int) -> LibraryCollectionPage<T>,
): List<T> {
    val items = linkedMapOf<String, T>()
    var offset = 0
    do {
        val page = loadPage(offset)
        val previousSize = items.size
        page.items.forEach { items.putIfAbsent(key(it), it) }
        if (!page.hasMore) return items.values.toList()
        check(page.nextOffset > offset && items.size > previousSize) {
            "NetEase playlist pagination did not advance"
        }
        offset = page.nextOffset
    } while (true)
}

private fun JsonObject.requireSuccess() {
    val code = get("code")?.takeUnless { it.isJsonNull }?.asInt
    check(code == 200) { "NetEase album request failed (${code ?: "missing code"})" }
}
