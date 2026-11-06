package com.ljyh.mei.source.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.google.gson.Gson
import com.ljyh.mei.source.api.*
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SourceSettings(
    val autoFallbackEnabled: Boolean = false,
    val preferOriginalProvider: Boolean = true,
    val fallbackOrder: List<String> = emptyList(),
    val preferredBrowseProvider: String = ProviderIds.NETEASE,
    val preferredSearchProvider: String = ProviderIds.NETEASE,
    val disabledTrackFallbacks: Set<String> = emptySet(),
    val manualMatches: Map<String, String> = emptyMap(),
)

@Singleton
class SourcePreferences internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.dataStore)
    private val gson = Gson()
    private val key = stringPreferencesKey("source_settings_v1")
    val settings: Flow<SourceSettings> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { gson.fromJson(it, SourceSettings::class.java) }.getOrNull() } ?: SourceSettings()
    }
    suspend fun snapshot(): SourceSettings = settings.first()
    suspend fun update(transform: (SourceSettings) -> SourceSettings) {
        store.edit { prefs ->
            val old = prefs[key]?.let { runCatching { gson.fromJson(it, SourceSettings::class.java) }.getOrNull() } ?: SourceSettings()
            prefs[key] = gson.toJson(transform(old))
        }
    }
    suspend fun bind(origin: TrackRef, target: TrackRef) = update {
        it.copy(manualMatches = it.manualMatches + (TrackRefCodec.encode(origin) + "|" + target.providerId to TrackRefCodec.encode(target)))
    }
}
