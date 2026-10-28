package com.ljyh.mei.playback

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Recommendation and scene modes exposed by NetEase Cloud Music's private roaming player. */
enum class PrivateFmMode(val code: String) {
    DEFAULT("DEFAULT"),
    EXPLORE("EXPLORE"),
    FAMILIAR("FAMILIAR"),
    SCENE("SCENE_RCMD");

    fun requestBody(unplayedIds: List<String> = emptyList(), scene: PrivateFmScene? = null): Map<String, String> {
        require(this != SCENE || scene != null) { "请选择漫游场景" }
        return buildMap {
            put("mode", code)
            put("entranceType", "newframework_daily_recommend_mix")
            put("limit", "3")
            put("openAidj", "false")
            put("unplaySongIds", unplayedIds.mapNotNull(String::toLongOrNull).filter { it > 0 }
                .distinct().takeLast(200).joinToString(prefix = "[", postfix = "]", separator = ","))
            if (this@PrivateFmMode == SCENE) {
                put("subMode", scene!!.code)
                scene.cascadeMode.takeIf(String::isNotBlank)?.let { put("fmCascadeModeStr", it) }
            }
        }
    }
}

data class PrivateFmScene(val code: String, val title: String, val cascadeMode: String = "") {
    fun encode(): String = JsonObject().apply {
        addProperty("code", code); addProperty("title", title); addProperty("cascadeMode", cascadeMode)
    }.toString()

    companion object {
        fun decode(value: String?): PrivateFmScene? = runCatching {
            val item = JsonParser.parseString(value ?: return null).asJsonObject
            fun text(key: String) = item.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val code = text("code"); val title = text("title")
            if (code.isBlank() || title.isBlank()) null else PrivateFmScene(code, title, text("cascadeMode"))
        }.getOrNull()
    }
}

internal fun parsePrivateFmScenes(response: JsonObject): List<PrivateFmScene> {
    val code = response.get("code")?.asInt ?: 200
    check(code == 200) { response.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "漫游场景加载失败 ($code)" }
    val data = response.getAsJsonObject("data")?.getAsJsonObject("crossPlatformResource")
        ?.getAsJsonObject("dslData") ?: return emptyList()
    val scenes = data.getAsJsonArray("currentSceneList")
        ?: data.entrySet().firstNotNullOfOrNull { (_, value) ->
            value.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("currentSceneList")
        } ?: return emptyList()
    return scenes.mapNotNull { value ->
        val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        fun text(key: String) = item.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val sceneCode = text("code")
        val title = text("title")
        if (sceneCode.isBlank() || title.isBlank()) null
        else PrivateFmScene(sceneCode, title, text("fmCascadeModeStr"))
    }.distinctBy(PrivateFmScene::code)
}
