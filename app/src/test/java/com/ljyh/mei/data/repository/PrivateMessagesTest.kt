package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesTest {
    @Test
    fun officialRequestUsesTheNativeAggregationAndTimeCursor() {
        val body = officialConversationsBody(before = 123456789L, limit = 20)
        val query = JsonParser.parseString(body.getValue("dialogueAggregationQueryMeta").toString()).asJsonObject

        assertEquals(1, query["aggregationType"].asInt)
        assertEquals(8888L, query["relatedId"].asLong)
        assertEquals(123456789L, query["time"].asLong)
        assertEquals(20, query["limit"].asInt)
    }

    @Test
    fun officialRecordsKeepAccountIdentitySummaryUnreadCountAndPagination() {
        val page = parseOfficialConversations(
            JsonParser.parseString(
                """
                {"data":{"more":true,"records":[
                  {"userInfoVO":{"userInfoDetailList":[
                    {"userId":9003,"nickname":"Secretary","avatarUrl":"https://example.com/secretary.png",
                     "avatarDetail":{"identityIconUrl":"https://example.com/verified.png"}}
                  ]},"lastMsg":{"time":2000,"briefText":"Shared an album"},"msgCountVO":{"unreadCount":3}},
                  {"userInfoVO":{"userInfoDetailList":[{"userId":9004,"nickname":"VIP"}]},
                   "lastMsg":{"time":1000,"briefText":"Birthday song"},"msgCountVO":{"unreadCount":0}}
                ]}}
                """.trimIndent(),
            ).asJsonObject,
        )

        assertEquals(listOf(9003L, 9004L), page.conversations.map { it.participant(42).id })
        assertEquals("Secretary", page.conversations.first().participant(42).displayName)
        assertEquals("https://example.com/secretary.png", page.conversations.first().participant(42).avatarUrl)
        assertEquals("https://example.com/verified.png", page.conversations.first().participant(42).avatarBadgeUrl)
        assertEquals("Shared an album", page.conversations.first().summary)
        assertEquals(3, page.conversations.first().unreadCount)
        assertEquals(1000L, page.nextTime)
        assertTrue(page.hasMore)
        assertFalse(page.conversations.first().isOfficialGroup)
    }

    @Test
    fun officialNoticeAccountsUseTheirOwnNameAndAvatar() {
        val response = JsonParser.parseString(
            """
            {"data":{"more":false,"records":[
              {"userInfoVO":{"userInfoDetailList":[{"userId":100,"nickname":"Underlying account"}]},
               "lastMsg":{"time":1000,"briefText":"Notice","noticeAccountFlag":true,
                 "noticeAccount":{"name":"Music Benefits","avatarUrl":"https://example.com/notice.png"}}}
            ]}}
            """.trimIndent(),
        ).asJsonObject

        val account = parseOfficialConversations(response).conversations.single().participant(42)
        assertEquals(100L, account.id)
        assertEquals("Music Benefits", account.displayName)
        assertEquals("https://example.com/notice.png", account.avatarUrl)

        val records = response.getAsJsonObject("data").getAsJsonArray("records")
        response.getAsJsonObject("data").addProperty("records", records.toString())
        assertEquals(account, parseOfficialConversations(response).conversations.single().participant(42))
    }

    @Test
    fun emptyOfficialPageStopsPagination() {
        val page = parseOfficialConversations(JsonParser.parseString("""{"data":{"more":true,"records":[]}}""").asJsonObject)
        assertTrue(page.conversations.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(-1L, page.nextTime)
    }

    @Test
    fun legacyOfficialEntryIsAGroupAndOrdinaryAccountsRemainConversations() {
        val group = requireNotNull(parseConversation(JsonParser.parseString(
            """{"fromUser":{"userId":8888,"nickname":"Official Messages"},"toUser":{"userId":42},"lastMsg":"{\"msg\":\"Latest notice\"}"}""",
        )))
        assertTrue(group.isOfficialGroup)
        assertEquals("Latest notice", group.summary)
        assertEquals(8888L, group.participant(42).id)

        val conversation = requireNotNull(parseConversation(JsonParser.parseString(
            """{"fromUser":{"userId":42},"toUser":{"userId":9003,"nickname":"Secretary"}}""",
        )))
        assertFalse(conversation.isOfficialGroup)
        assertEquals(9003L, conversation.participant(42).id)
    }
}
