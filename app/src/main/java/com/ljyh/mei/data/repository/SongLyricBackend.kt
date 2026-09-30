package com.ljyh.mei.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.SongSourceIdentity
import com.ljyh.mei.data.model.api.GetCloudLyric
import com.ljyh.mei.data.model.api.GetLyricV1
import com.ljyh.mei.data.network.api.ApiService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The selected runtime owns transport; private-cloud lyrics keep their file/account affinity. */
class SongLyricBackend @Inject constructor(private val api: ApiService, private val sessions: SessionStore) {
    suspend fun lyrics(sourceKey: String, owner: SessionStamp): Lyric {
        val source = SongSourceIdentity.fromKey(sourceKey)
        source.requireAccount(owner.identity)
        requireOwner(owner)
        val result = if (source.isCloud) {
            parseCloudLyric(api.getCloudLyric(GetCloudLyric(source.songId, source.cloudOwnerId), owner))
        } else {
            api.getLyricV1(GetLyricV1(source.songId.toString()), owner).also {
                if (it.code != 200) throw IOException("Catalog lyric request was not accepted")
            }
        }
        requireOwner(owner)
        return result
    }

    private suspend fun requireOwner(owner: SessionStamp) {
        currentCoroutineContext().ensureActive()
        sessions.requireCurrent(owner)
        if (sessions.recoveryRequired.value) throw SessionChangedException()
    }
}

internal fun parseCloudLyric(response: JsonObject): Lyric {
    val code = response.get("code")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.toIntOrNull()
        ?: throw IOException("Missing cloud lyric status")
    if (code != 200 && code != 404) throw IOException("Cloud lyric request was not accepted")
    fun text(key: String): String? {
        val field = response.get(key)?.takeUnless(JsonElement::isJsonNull) ?: return null
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) throw IOException("Invalid cloud lyric field")
        return field.asString.takeIf(String::isNotBlank)
    }
    val lrc = if (code == 200) text("lrc") else null
    val karaoke = if (code == 200) text("krc") else null
    // Native cloud KRC is retained as karaoke data, not mislabeled as catalog YRC.
    return Lyric(200, karaoke?.let { Lyric.Klyric(it, 1) }, lrc?.let { Lyric.Lrc(it, 1) },
        false, null, false, false, null, null, null, null)
}
