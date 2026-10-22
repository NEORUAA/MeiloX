package com.ljyh.mei.ui.screen.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.utils.dataStore
import java.util.Locale

internal val SearchHistoryKey = stringPreferencesKey("search.history")
private const val MAX_SEARCH_HISTORY = 20

internal fun decodeSearchHistory(value: String?): List<String> = runCatching {
    JsonParser.parseString(value.orEmpty()).asJsonArray.mapNotNull { item ->
        item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.map(String::trim).filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_SEARCH_HISTORY)
}.getOrDefault(emptyList())

internal fun updatedSearchHistory(history: List<String>, query: String): List<String> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return history
    return (listOf(keyword) + history.filterNot { it.equals(keyword, ignoreCase = true) })
        .take(MAX_SEARCH_HISTORY)
}

internal suspend fun Context.recordSearchHistory(query: String) {
    if (query.isBlank()) return
    dataStore.edit { preferences ->
        preferences[SearchHistoryKey] = Gson().toJson(
            updatedSearchHistory(decodeSearchHistory(preferences[SearchHistoryKey]), query),
        )
    }
}

internal suspend fun Context.removeSearchHistory(query: String) {
    dataStore.edit { preferences ->
        preferences[SearchHistoryKey] = Gson().toJson(
            decodeSearchHistory(preferences[SearchHistoryKey]).filterNot { it.equals(query, ignoreCase = true) },
        )
    }
}

internal suspend fun Context.clearSearchHistory() {
    dataStore.edit { it.remove(SearchHistoryKey) }
}
