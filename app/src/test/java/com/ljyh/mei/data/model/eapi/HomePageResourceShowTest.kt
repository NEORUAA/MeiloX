package com.ljyh.mei.data.model.eapi

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.ljyh.mei.data.model.weapi.GetHomePageResourceShow
import com.ljyh.mei.source.netease.repository.loadHomePageBlocks
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomePageResourceShowTest {
    private val gson = Gson()

    @Test
    fun explicitNullDslAtReportedCrashPathKeepsSiblingBlocks() {
        val blocks = parseResponse(optionalDslField = "\"dslData\":null").data.blocks

        assertEquals(4, blocks.size)
        assertTrue(blocks[2].dslData.isNullOrJsonNull())
        assertRegularSiblingBlocks(blocks)
    }

    @Test
    fun missingDslKeepsSiblingBlocks() {
        val blocks = parseResponse(optionalDslField = "").data.blocks

        assertNull(blocks[2].dslData)
        assertRegularSiblingBlocks(blocks)
    }

    @Test
    fun objectDslRetainsNestedResourceData() {
        val dsl = """{"blockResource":{"title":"Featured","resources":[{"resourceId":"42","title":"Playlist"}]}}"""
        val blocks = parseResponse(optionalDslField = "\"dslData\":$dsl").data.blocks

        assertEquals(JsonParser.parseString(dsl), blocks[2].dslData)
        assertRegularSiblingBlocks(blocks)
    }

    @Test
    fun nonObjectDslDoesNotPreventParsingRegularBlocks() {
        for (dsl in listOf("[]", "[{}]", "false", "42", "\"unavailable\"")) {
            val blocks = parseResponse(optionalDslField = "\"dslData\":$dsl").data.blocks

            assertEquals(JsonParser.parseString(dsl), blocks[2].dslData)
            assertRegularSiblingBlocks(blocks)
        }
    }

    @Test
    fun cacheRoundTripRetainsRegularBlocksAlongsideNullOrMissingDsl() {
        val blocks = listOf("\"dslData\":null", "", "\"dslData\":[]")
            .flatMap { parseResponse(optionalDslField = it).data.blocks }
        val cachedJson = gson.toJson(blocks)
        val restored = gson.fromJson<List<HomePageResourceShow.Data.Block>>(
            cachedJson,
            object : TypeToken<List<HomePageResourceShow.Data.Block>>() {}.type,
        )

        assertEquals(blocks.size, restored.size)
        assertEquals(blocks.map { it.positionCode }, restored.map { it.positionCode })
        for (offset in listOf(0, 4, 8)) {
            assertRegularSiblingBlocks(restored.subList(offset, offset + 4))
        }
        assertTrue(restored[2].dslData.isNullOrJsonNull())
        assertNull(restored[6].dslData)
        assertTrue(restored[10].dslData!!.isJsonArray)
    }

    @Test
    fun mobileFeedLoadsRemainingModulesWithTheServerContinuation() = runBlocking {
        val calls = mutableListOf<GetHomePageResourceShow>()
        val pages = listOf(
            page(listOf("DAILY"), 10, listOf("RADAR", "RANK", "PLAYLIST"), "page-two"),
            page(listOf("DAILY", "RADAR", "RANK"), 17, listOf("PLAYLIST"), "page-three"),
            page(listOf("PLAYLIST"), 19, emptyList()),
        )
        val result = loadHomePageBlocks(initialRequest()) { request ->
            calls += request
            pages[calls.lastIndex]
        }

        assertEquals(listOf("DAILY", "RADAR", "RANK", "PLAYLIST"), result.map { it.positionCode })
        assertEquals(listOf("0", "10", "17"), calls.map { it.cursor })
        assertNull(calls.first().blockCodeOrderList)
        assertEquals(listOf("true", "false", "false"), calls.map { it.isFirstScreen })
        assertEquals(listOf("true", "false", "false"), calls.map { it.refresh })
        assertEquals("[\"RADAR\",\"RANK\",\"PLAYLIST\"]", calls[1].blockCodeOrderList)
        assertEquals("[\"PLAYLIST\"]", calls[2].blockCodeOrderList)
        assertEquals("[\"DAILY\"]", calls[1].loadedPositionCodes)
        assertEquals("[\"DAILY\",\"RADAR\",\"RANK\"]", calls[2].loadedPositionCodes)
        assertEquals("page-two", calls[1].callbackParameters)
        assertEquals("page-three", calls[2].callbackParameters)
        val continuationJson = gson.toJsonTree(calls[1]).asJsonObject
        assertEquals(calls[1].blockCodeOrderList, continuationJson["blockCodeOrderList"].asString)
        calls.drop(1).forEach { request ->
            val ext = JsonParser.parseString(request.extJson).asJsonObject
            assertEquals("loadMore", ext["refreshAction"].asString)
            assertEquals(false, ext["firstRequestPerLaunch"].asBoolean)
            assertEquals("fastPlay", ext["homeFrameworkType"].asString)
            assertEquals("{}", request.ruleJson)
        }
    }

    @Test
    fun completeFeedDoesNotRequestAnExtraPage() = runBlocking {
        var calls = 0
        val result = loadHomePageBlocks(initialRequest()) {
            calls++
            page(listOf("DAILY", "RADAR", "RANK"), 17, emptyList())
        }
        assertEquals(1, calls)
        assertEquals(3, result.size)
    }

    @Test
    fun missingOrInvalidContinuationDoesNotPublishAPartialFeed() = runBlocking {
        for (order in listOf(null, "", "[]", "{}", "[42]")) {
            var calls = 0
            try {
                loadHomePageBlocks(initialRequest()) {
                    calls++
                    page(listOf("DAILY"), 10, listOf("RADAR"), blockOrder = order)
                }
                fail("An incomplete feed must carry a valid block order")
            } catch (expected: IOException) {
                assertEquals("Home pagination is missing its block order", expected.message)
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun repeatedContinuationStopsBeforeFetchingTheSamePageAgain() = runBlocking {
        var calls = 0
        try {
            loadHomePageBlocks(initialRequest()) {
                calls++
                page(listOf("DAILY"), 10, listOf("RADAR"))
            }
            fail("A repeated continuation must fail")
        } catch (expected: IOException) {
            assertEquals("Home pagination did not advance", expected.message)
        }
        assertEquals(2, calls)
    }

    @Test
    fun laterPageFailureAndCancellationPropagateWithoutReturningEarlierBlocks() = runBlocking {
        for (failure in listOf(IOException("Offline"), CancellationException("Cancelled"))) {
            var calls = 0
            try {
                loadHomePageBlocks(initialRequest()) {
                    if (calls++ == 0) page(listOf("DAILY"), 10, listOf("RADAR"))
                    else throw failure
                }
                fail("A later page failure must propagate")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(2, calls)
        }

        var calls = 0
        try {
            loadHomePageBlocks(initialRequest()) {
                if (calls++ == 0) page(listOf("DAILY"), 10, listOf("RADAR"))
                else gson.fromJson("""{"code":500}""", HomePageResourceShow::class.java)
            }
            fail("An unsuccessful business response must fail")
        } catch (expected: IOException) {
            assertEquals("Home request failed (500)", expected.message)
        }
        assertEquals(2, calls)
    }

    private fun initialRequest() = GetHomePageResourceShow(
        pageCode = "HOME_RECOMMEND_PAGE",
        isFirstScreen = "true",
        cursor = "0",
        refresh = "true",
        widthDp = "400",
        heightDp = "800",
        loadedPositionCodes = "",
        clientCacheBlockCode = "[]",
        callbackParameters = "",
        extJson = """{"homeFrameworkType":"fastPlay","refreshAction":"init","firstRequestPerLaunch":true}""",
        pageStyleType = "cutBlock",
        adExtJson = "{}",
        reqTimeStamp = "1",
        clientTime = "2026-10-15 12:00:00",
        ruleJson = "{}",
        algDemoteBlockCodeOrderList = "[]",
        header = "{}",
        eR = true,
    )

    private fun page(
        positions: List<String>,
        cursor: Int,
        remaining: List<String>,
        callback: String = "",
        blockOrder: Any? = gson.toJson(remaining),
    ): HomePageResourceShow = gson.fromJson(
        gson.toJson(
            mapOf(
                "code" to 200,
                "data" to mapOf(
                    "blocks" to positions.map { mapOf("positionCode" to it) },
                    "cursor" to cursor,
                    "hasMore" to remaining.isNotEmpty(),
                    "blockCodeOrderList" to blockOrder,
                    "callbackParameters" to callback,
                ),
            ),
        ),
        HomePageResourceShow::class.java,
    )

    private fun parseResponse(optionalDslField: String): HomePageResourceShow {
        val optionalFields = optionalDslField.takeIf { it.isNotEmpty() }?.let { ",$it" }.orEmpty()
        return gson.fromJson(
            """
            {
              "code": 200,
              "data": {
                "blocks": [
                  {
                    "positionCode": "PAGE_RECOMMEND_DAILY_RECOMMEND",
                    "dslData": {"blockResource": {"title": "Daily", "resources": [{"resourceId": "1"}]}}
                  },
                  {
                    "positionCode": "PAGE_RECOMMEND_RANK",
                    "dslData": {"rank": {"title": "Rank", "resources": [{"resourceId": "2"}]}}
                  },
                  {"positionCode": "PAGE_RECOMMEND_OPTIONAL"$optionalFields},
                  {
                    "positionCode": "PAGE_RECOMMEND_PLAYLIST",
                    "dslData": {"common_playlist": {"title": "Playlists", "resources": [{"resourceId": "3"}]}}
                  }
                ]
              }
            }
            """.trimIndent(),
            HomePageResourceShow::class.java,
        )
    }

    private fun assertRegularSiblingBlocks(blocks: List<HomePageResourceShow.Data.Block>) {
        assertEquals("PAGE_RECOMMEND_DAILY_RECOMMEND", blocks[0].positionCode)
        assertEquals("1", resourceId(blocks[0].dslData, "blockResource"))
        assertEquals("PAGE_RECOMMEND_RANK", blocks[1].positionCode)
        assertEquals("2", resourceId(blocks[1].dslData, "rank"))
        assertEquals("PAGE_RECOMMEND_PLAYLIST", blocks[3].positionCode)
        assertEquals("3", resourceId(blocks[3].dslData, "common_playlist"))
    }

    private fun resourceId(dsl: JsonElement?, key: String): String =
        dsl!!.asJsonObject.getAsJsonObject(key).getAsJsonArray("resources")
            .single().asJsonObject.get("resourceId").asString

    private fun JsonElement?.isNullOrJsonNull(): Boolean = this == null || isJsonNull
}
