package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.AccountConnectionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConnectionsTest {
    @Test
    fun followingAndFollowersReadTheirOwnUserList() {
        val response = JsonParser.parseString(
            """
            {
              "code": 200,
              "more": false,
              "follow": [{"userId": "12", "nickname": "Following", "avatarUrl": "https://example.com/avatar", "signature": "Music"}],
              "followeds": [{"userId": 34, "nickname": "Follower", "followeds": 8}]
            }
            """.trimIndent(),
        ).asJsonObject

        val following = parseAccountConnections(response, AccountConnectionType.Following, 0, 30)
        val followers = parseAccountConnections(response, AccountConnectionType.Followers, 0, 30)

        assertEquals(12L, following.users.single().id)
        assertEquals("Following", following.users.single().nickname)
        assertEquals("https://example.com/avatar", following.users.single().avatarUrl)
        assertEquals("Music", following.users.single().signature)
        assertEquals(34L, followers.users.single().id)
        assertEquals(8, followers.users.single().followers)
    }

    @Test
    fun paginationAdvancesByRawRowsWhileFilteringInvalidAndDuplicateUsers() {
        val page = parse(
            """
            {"code": 200, "more": true, "follow": [
              {"userId": 12}, {"userId": 12}, {"userId": 0}, {}, null, {"userId": 34}
            ]}
            """.trimIndent(),
            offset = 30,
        )

        assertEquals(listOf(12L, 34L), page.users.map { it.id })
        assertEquals(36, page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test
    fun emptyPagesAndExplicitEndStopPagination() {
        assertFalse(parse("""{"code": 200, "more": true, "follow": []}""").hasMore)
        assertFalse(parse("""{"code": 200, "more": false, "follow": [{"userId": 12}]}""", limit = 1).hasMore)
        assertFalse(parse("""{"code": 200, "follow": [{"userId": 12}]}""", limit = 30).hasMore)
        assertTrue(parse("""{"code": 200, "follow": [{"userId": 12}]}""", limit = 1).hasMore)
        assertFalse(parse("""{"code": 200, "more": true, "follow": [{"userId": 12}]}""", offset = Int.MAX_VALUE).hasMore)
    }

    @Test
    fun failedOrMissingListsDoNotBecomeEmptyAccounts() {
        listOf(
            """{"code": 403, "message": "Private", "follow": []}""",
            """{"follow": []}""",
            """{"code": 200}""",
            """{"code": 200, "follow": null}""",
            """{"code": 200, "follow": {}}""",
        ).forEach { response ->
            assertThrows(IllegalStateException::class.java) { parse(response) }
        }
        assertEquals(emptyList<Any>(), parse("""{"code": 200, "follow": []}""").users)
    }

    @Test
    fun verifiedIdentityDoesNotTurnUserIdsIntoArtistIds() {
        val page = parse(
            """
            {"code": 200, "follow": [
              {"userId": 12, "userType": 4, "artistId": "34"},
              {"userId": 56, "userType": 4, "artistId": 0},
              {"userId": 78, "userType": 2, "allAuthTypes": [{"type": 4}]},
              {"userId": 90, "userType": 0},
              {"userId": 91, "userType": 0, "artistId": -1}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf(34L, null, null, null, null), page.users.map { it.artistId })
        assertEquals(listOf(true, true, true, false, false), page.users.map { it.hasVerifiedIdentity })
    }

    @Test
    fun onlyVerifiedUsersMissingAnArtistIdLoadTheirProfile() = runBlocking {
        val users = parse(
            """
            {"code": 200, "follow": [
              {"userId": 1, "userType": 0, "authStatus": 0},
              {"userId": 2, "userType": 4, "artistId": 22},
              {"userId": 3, "authStatus": 1},
              {"userId": 4, "userType": 2, "allAuthTypes": [{"type": 2}]},
              {"userId": 5, "mainAuthType": {"type": 4}},
              {"userId": 6, "allAuthTypes": [{"type": 2}]}
            ]}
            """.trimIndent(),
        ).users
        val requested = mutableListOf<Long>()

        val resolved = resolveAccountConnectionArtists(users) { userId ->
            requested += userId
            profile("""{"userId": $userId, "artistId": ${userId * 11}}""")
        }

        assertEquals(listOf(3L, 4L, 5L, 6L), requested.sorted())
        assertEquals(listOf(null, 22L, 33L, 44L, 55L, 66L), resolved.map { it.artistId })
        assertEquals(users.map { it.id }, resolved.map { it.id })
    }

    @Test
    fun verifiedOrganizationResolvesTheArtistIdFromUserDetail() = runBlocking {
        val user = profile(
            """
            {"userId": 1321189664, "nickname": "HOYO-MiX", "userType": 2,
             "authStatus": 1, "avatarDetail": {"userType": 2}}
            """.trimIndent(),
        )
        val detailed = profile(
            """
            {"userId": 1321189664, "nickname": "HOYO-MiX", "userType": 2,
             "artistId": 12487174, "allAuthTypes": [{"type": 2}], "mainAuthType": {"type": 2}}
            """.trimIndent(),
        )

        val resolved = resolveAccountConnectionArtists(listOf(user)) { userId ->
            assertEquals(1321189664L, userId)
            detailed
        }

        assertEquals(12487174L, resolved.single().artistId)
        assertEquals(user.id, resolved.single().id)
    }

    @Test
    fun failedOrMismatchedArtistDetailsKeepTheListAndCancellationPropagates() {
        runBlocking {
            val users = listOf(
                profile("""{"userId": 1, "userType": 4}"""),
                profile("""{"userId": 2, "userType": 4}"""),
            )

            val resolved = resolveAccountConnectionArtists(users) { userId ->
                if (userId == 1L) error("Profile unavailable")
                profile("""{"userId": 999, "artistId": 22}""")
            }

            assertEquals(users, resolved)
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    resolveAccountConnectionArtists(users) { throw CancellationException("Cancelled") }
                }
            }
        }
    }

    private fun profile(json: String) = parse("""{"code": 200, "follow": [$json]}""").users.single()

    private fun parse(response: String, offset: Int = 0, limit: Int = 30) = parseAccountConnections(
        JsonParser.parseString(response).asJsonObject,
        AccountConnectionType.Following,
        offset,
        limit,
    )
}
