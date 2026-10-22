package com.ljyh.mei.ui.screen.main.home

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendationActionsTest {
    @Test
    fun resourceKeysFollowCardsAcrossReorderingAndDistinguishRepeatedServerEntries() {
        val first = resource("""{"resourceType":"playList","resourceId":"42","action":"orpheus://playlist/42"}""")
        val second = resource("""{"resourceType":"playList","resourceId":"7","action":"orpheus://playlist/7"}""")
        val original = homeResourceKeys(listOf(first, second))
        assertEquals(original.reversed(), homeResourceKeys(listOf(second, first)))
        val repeated = homeResourceKeys(listOf(first, second, first))
        assertEquals(repeated.size, repeated.toSet().size)
        assertEquals(original, repeated.take(2))
    }

    @Test
    fun resourceKeysSupportMissingIdsAndDoNotConfuseFieldSeparators() {
        val cards = listOf(
            resource("""{"resourceType":"nonstandard","resourceId":"null","title":"First"}"""),
            resource("""{"resourceType":"nonstandard","resourceId":"null","title":"Second"}"""),
            resource("""{"resourceType":"a:b","resourceId":"c"}"""),
            resource("""{"resourceType":"a","resourceId":"b:c"}"""),
        )
        val keys = homeResourceKeys(cards)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.reversed(), homeResourceKeys(cards.reversed()))
    }

    @Test
    fun similarSongCardParsesAllSeedsInTheDisplayedOrder() {
        val resource = resource(
            """
            {
              "resourceType": "similarSong",
              "resourceId": "[\"41416746\",\"26537202\",\"22692733\"]",
              "action": "orpheus://nm/play/similarFM?sourceType=song"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("41416746", "26537202", "22692733"), parseRecommendationSongIds(resource.resourceId))
    }

    @Test
    fun numericAndStringSeedsAreValidatedAndDeduplicatedWithoutReordering() {
        assertEquals(
            listOf("42", "7", "123"),
            parseRecommendationSongIds("""["42",7,"00042",123,"7"]"""),
        )
        assertEquals(
            listOf("42", "7"),
            parseRecommendationSongIds("""[null,-1,0,"null",true,{},[],1.5,"1e3",9223372036854775808,42,"7"]"""),
        )
    }

    @Test
    fun missingOrInvalidSeedsNeverBecomeAPlaybackRequest() {
        for (value in listOf(null, "", "null", "[]", "{}", "42", "[", "[0,-2,\"bad\"]")) {
            assertTrue("Expected no seeds for $value", parseRecommendationSongIds(value).isEmpty())
        }
    }

    @Test
    fun miniBannerUsesItsActionEvenWhenResourceIdIsTheStringNull() {
        val resource = resource(
            """
            {
              "resourceType": "nonstandard",
              "moduleType": "mini_banner",
              "resourceId": "null",
              "action": "orpheus://playlist/18408695522",
              "playBtnData": "{\"detailUrl\":\"orpheus://playlist/123\"}"
            }
            """.trimIndent(),
        )

        assertEquals(18_408_695_522L, parseRecommendationPlaylistId(resource.action, resource.playBtnData))
    }

    @Test
    fun playlistActionSupportsPathAndQueryRoutes() {
        assertEquals(123L, parseRecommendationPlaylistId("orpheus://playlist/123?source=home", null))
        assertEquals(123L, parseRecommendationPlaylistId("orpheus://playlist/123/", null))
        assertEquals(123L, parseRecommendationPlaylistId("orpheus://nm/playlist/detail?id=123&source=home", null))
        assertEquals(123L, parseRecommendationPlaylistId("orpheus://nm/playlist/detail?source=home&id=%31%32%33", null))
    }

    @Test
    fun missingOrUnsupportedActionCanUseThePlayButtonDetailUrl() {
        assertEquals(123L, parseRecommendationPlaylistId(null, """{"detailUrl":"orpheus://playlist/123"}"""))
        assertEquals(123L, parseRecommendationPlaylistId("null", mapOf("detailUrl" to "orpheus://playlist/123")))
        assertEquals(
            123L,
            parseRecommendationPlaylistId("orpheus://unhandled", JsonParser.parseString("""{"detailUrl":"orpheus://nm/playlist/detail?id=123"}""")),
        )
    }

    @Test
    fun invalidOrUnsupportedPlaylistActionsAreRejected() {
        for (action in listOf(
            null, "", "null", "123", "https://playlist/123", "orpheus://playlist/null",
            "orpheus://playlist/0", "orpheus://playlist/-1", "orpheus://playlist/1.5",
            "orpheus://playlist/9223372036854775808", "orpheus://playlist/123/456",
            "orpheus://playlist/../123", "orpheus://user@playlist/123", "orpheus://playlist:80/123",
            "orpheus://nm/playlist/detail", "orpheus://nm/playlist/detail?id=-1",
            "orpheus://nm/playlist/detail?id=123&id=456", "orpheus://nm/playlist/detail?id=%XX",
            "orpheus://album/123", "orpheus://nm/play/similarFM?sourceType=song",
        )) {
            assertNull("Expected no playlist for $action", parseRecommendationPlaylistId(action, null))
        }
        assertNull(parseRecommendationPlaylistId(null, "not json"))
        assertNull(parseRecommendationPlaylistId(null, """{"detailUrl":123}"""))
    }

    @Test
    fun podcastResourcesRemainOutsideTheNewSongAndPlaylistActions() {
        for (type in listOf("podcast", "djprogram", "musicPodcast")) {
            val resource = resource(
                """
                {
                  "resourceType": "$type",
                  "resourceId": "3065982542",
                  "action": "orpheus://program/3065982542?switch=true",
                  "playBtnData": "{\"detailUrl\":\"orpheus://nm/mainTab/select?tab=explore&exploreTabCode=podcast\"}",
                  "programDTO": "{\"programData\":{\"mainSong\":{\"id\":42}}}"
                }
                """.trimIndent(),
            )
            assertTrue(parseRecommendationSongIds(resource.resourceId).isEmpty())
            assertNull(parseRecommendationPlaylistId(resource.action, resource.playBtnData))
            assertEquals(type, resource.resourceType)
            assertEquals("""{"programData":{"mainSong":{"id":42}}}""", resource.programDTO)
        }
    }

    private fun resource(json: String) = Gson().fromJson(
        json,
        HomePageResourceShow.Data.Block.DslData.BlockResource.Resource::class.java,
    )
}
