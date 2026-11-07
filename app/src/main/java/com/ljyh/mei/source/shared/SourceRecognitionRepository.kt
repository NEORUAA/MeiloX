package com.ljyh.mei.source.shared

import com.ljyh.mei.data.model.melox.RecognizedSong
import com.ljyh.mei.source.api.TrackRef
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceErrorCode
import com.ljyh.mei.source.core.SourceRouter
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toMediaItem
import androidx.media3.common.MediaItem
import com.ljyh.mei.source.core.BoundSourceFeatures
import com.ljyh.mei.source.core.SourceFeatureClient
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceRecognitionRepository @Inject constructor(private val client: SourceFeatureClient, private val router: SourceRouter) {
    suspend fun open(providerId: String?): SourceRecognitionSession {
        val scopeId = "recognition:${UUID.randomUUID()}"
        val bound = if (providerId == null) client.current(scopeId) else client.bind(providerId, scopeId)
        if (!bound.supports("recognition", "match")) throw com.ljyh.mei.source.api.SourceException(com.ljyh.mei.source.api.SourceErrorCode.UNSUPPORTED)
        return SourceRecognitionSession(bound, router)
    }
}
class SourceRecognitionSession internal constructor(val owner: BoundSourceFeatures, private val router: SourceRouter) {
    suspend fun media(song: RecognizedSong): MediaItem {
        if (song.ref.providerId != owner.providerId) invalidFeature()
        val track = owner.whileCurrent { router.getTrack(song.ref) } ?: throw SourceException(SourceErrorCode.NOT_FOUND)
        return track.toMediaMetadata().toMediaItem()
    }
    suspend fun match(fingerprint: String, duration: Int): List<RecognizedSong> {
        require(fingerprint.isNotBlank() && duration in 1..15)
        return owner.invoke("recognition", "match", featureParameters { addProperty("fingerprint", fingerprint); addProperty("duration", duration) })
            .featureList(100).map { element ->
                val row = element.featureObject()
                RecognizedSong(TrackRef(owner.providerId, row.featureText("id")!!), row.featureText("name", maximum = 1000)!!,
                    row.featureList("artists", 50).map { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString.length > 1000) invalidFeature() else it.asString },
                    row.featureText("album", true, 1000).orEmpty(), row.featureText("coverUrl", true, 8192), row.featureLong("durationMs"),
                    row.get("startTimeMs")?.takeUnless { it.isJsonNull }?.let { row.featureLong("startTimeMs") })
            }.distinctBy { it.ref }
    }
}
