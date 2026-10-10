package com.ljyh.mei.data.model.eapi

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
