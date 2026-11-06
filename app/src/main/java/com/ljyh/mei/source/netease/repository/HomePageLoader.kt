package com.ljyh.mei.source.netease.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.weapi.GetHomePageResourceShow
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_HOME_PAGES = 10

/** Loads the mobile feed's ordered pages before publishing a complete cache entry. */
internal suspend fun loadHomePageBlocks(
    initialRequest: GetHomePageResourceShow,
    fetch: suspend (GetHomePageResourceShow) -> HomePageResourceShow,
): List<HomePageResourceShow.Data.Block> {
    val gson = Gson()
    val blocks = linkedMapOf<String, HomePageResourceShow.Data.Block>()
    val visitedPages = mutableSetOf<Pair<String, String?>>()
    var request = initialRequest

    repeat(MAX_HOME_PAGES) {
        currentCoroutineContext().ensureActive()
        if (!visitedPages.add(request.cursor to request.blockCodeOrderList)) {
            throw IOException("Home pagination did not advance")
        }

        val response = fetch(request)
        currentCoroutineContext().ensureActive()
        if (response.code != 200) {
            throw IOException("Home request failed (${response.code})")
        }
        val page = response.data
        page.blocks.forEach { block -> blocks.putIfAbsent(block.positionCode, block) }
        if (!page.hasMore) return blocks.values.toList()

        // The server returns the remaining module order separately from the cursor.
        // Mobile requests need both; sending only the cursor fails with business code 500.
        val orderJson = when (val order = page.blockCodeOrderList) {
            is String -> order
            null -> ""
            else -> gson.toJson(order)
        }
        val order = runCatching { JsonParser.parseString(orderJson) }.getOrNull()
        if (order == null || !order.isJsonArray || order.asJsonArray.isEmpty ||
            order.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }
        ) {
            throw IOException("Home pagination is missing its block order")
        }
        val ext = JsonParser.parseString(request.extJson).asJsonObject.apply {
            addProperty("refreshAction", "loadMore")
            addProperty("firstRequestPerLaunch", false)
        }
        request = request.copy(
            isFirstScreen = "false",
            cursor = page.cursor.toString(),
            refresh = "false",
            blockCodeOrderList = orderJson,
            loadedPositionCodes = gson.toJson(blocks.keys),
            callbackParameters = page.callbackParameters.orEmpty(),
            extJson = gson.toJson(ext),
            reqTimeStamp = System.currentTimeMillis().toString(),
            clientTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        )
    }
    throw IOException("Home pagination exceeded its page limit")
}
