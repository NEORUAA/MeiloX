package com.ljyh.mei.data.repository

import com.google.gson.Gson
import com.ljyh.mei.data.model.UserPlaylist
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLikedPlaylistCacheTest {
    @Test
    fun theFirstLookupRequestsOnePlaylistAndCachesOnlyWithinTheSameAccountSession() = runTest {
        val cache = LibraryLikedPlaylistCache()
        val requests = mutableListOf<Pair<Int, Int>>()
        suspend fun resolve(account: LibraryAccount, id: Long) = cache.resolve(account) { offset, limit ->
            requests += offset to limit
            LibraryCollectionPage(listOf(playlist(id, account.userId)), 1, true)
        }
        val first = LibraryAccount("1", "first-session")
        assertEquals(42L, resolve(first, 42))
        assertEquals(42L, resolve(first, 43))
        assertEquals(listOf(0 to 1), requests)
        assertEquals(43L, resolve(LibraryAccount("1", "new-session"), 43))
        assertEquals(44L, resolve(LibraryAccount("2", "second-account"), 44))
        assertEquals(listOf(0 to 1, 0 to 1, 0 to 1), requests)
    }

    @Test
    fun lookupContinuesPastTheFirstPageAndIgnoresOtherUsersLikedPlaylists() = runTest {
        val requests = mutableListOf<Pair<Int, Int>>()
        val id = LibraryLikedPlaylistCache().resolve(LibraryAccount("1", "session")) { offset, limit ->
            requests += offset to limit
            when (offset) {
                0 -> LibraryCollectionPage(listOf(playlist(9, "2")), 1, true)
                1 -> LibraryCollectionPage(listOf(playlist(10, "1", specialType = 0)), 101, true)
                101 -> LibraryCollectionPage(listOf(playlist(42, "1")), 102, false)
                else -> error("Unexpected offset")
            }
        }
        assertEquals(42L, id)
        assertEquals(listOf(0 to 1, 1 to 100, 101 to 100), requests)
    }

    @Test
    fun absentLikedPlaylistsAreNotCachedAndInvalidContinuationsFailInsteadOfSpinning() = runTest {
        val cache = LibraryLikedPlaylistCache()
        val account = LibraryAccount("1", "session")
        assertNull(cache.resolve(account) { _, _ -> LibraryCollectionPage(emptyList(), 0, false) })
        assertEquals(42L, cache.resolve(account) { _, _ -> LibraryCollectionPage(listOf(playlist(42, "1")), 1, false) })
        val result = runCatching {
            LibraryLikedPlaylistCache().resolve(account) { _, _ -> LibraryCollectionPage(emptyList(), 0, true) }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        var repeatedPages = 0
        val repeated = runCatching {
            LibraryLikedPlaylistCache().resolve(account) { offset, _ ->
                repeatedPages++
                LibraryCollectionPage(listOf(playlist(9, "2")), offset + 1, true)
            }
        }
        assertTrue(repeated.exceptionOrNull() is IllegalStateException)
        assertEquals(2, repeatedPages)
    }

    private fun playlist(id: Long, owner: String, specialType: Int = 5): UserPlaylist.Playlist = Gson().fromJson(
        """{"id":$id,"specialType":$specialType,"creator":{"userId":$owner}}""",
        UserPlaylist.Playlist::class.java,
    )
}
