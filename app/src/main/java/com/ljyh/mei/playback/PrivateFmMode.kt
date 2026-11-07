package com.ljyh.mei.playback

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Shared recommendation choices. A source maps these semantics to its own service protocol. */
enum class PrivateFmMode {
    DEFAULT, EXPLORE, FAMILIAR, SCENE;

    // Preserve selection values written by earlier application versions.
    val storageKey: String get() = if (this == SCENE) "SCENE_RCMD" else name

    fun parameters(recentIds: List<String> = emptyList(), scene: PrivateFmScene? = null): Map<String, String> {
        require(this != SCENE || scene != null) { "请选择漫游场景" }
        return buildMap {
            put("mode", name.lowercase())
            put("recentTrackIds", com.google.gson.JsonArray().apply {
                recentIds.filter { it.isNotBlank() && it.length <= 2048 }.distinct().takeLast(200).forEach(::add)
            }.toString())
            if (this@PrivateFmMode == SCENE) {
                put("sceneCode", scene!!.code)
                scene.cascadeMode.takeIf(String::isNotBlank)?.let { put("cascadeMode", it) }
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
