package com.ljyh.mei.source.api

import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

object ProviderIds {
    const val NETEASE = "netease"
    const val LOCAL = "local"
}

data class TrackRef(
    @field:SerializedName("providerId") override val providerId: String,
    @field:SerializedName("trackId") val trackId: String,
) : ContentRef {
    override val contentId: String get() = trackId
    init { require(providerId.isNotBlank() && trackId.isNotEmpty()) }
}

data class AlbumRef(
    @field:SerializedName("providerId") override val providerId: String,
    @field:SerializedName("albumId") val albumId: String,
) : ContentRef {
    override val contentId: String get() = albumId
    init { require(providerId.isNotBlank() && albumId.isNotEmpty()) }
}

data class ArtistRef(
    @field:SerializedName("providerId") override val providerId: String,
    @field:SerializedName("artistId") val artistId: String,
) : ContentRef {
    override val contentId: String get() = artistId
    init { require(providerId.isNotBlank() && artistId.isNotEmpty()) }
}

data class PlaylistRef(
    @field:SerializedName("providerId") override val providerId: String,
    @field:SerializedName("playlistId") val playlistId: String,
) : ContentRef {
    override val contentId: String get() = playlistId
    init { require(providerId.isNotBlank() && playlistId.isNotEmpty()) }
}

@JvmInline
value class ProviderInstanceId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class ProviderAccountId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** A reversible transport identity. The legacy Room keys remain valid during migration. */
object TrackRefCodec {
    private const val PREFIX = "mei:track:v1:"
    private val legacyNumber = Regex("-?[0-9]+")
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    fun encode(ref: TrackRef): String = PREFIX + component(ref.providerId) + ":" + component(ref.trackId)

    fun decode(value: String, isLocal: Boolean = false, isPodcast: Boolean = false): TrackRef? {
        if (value.startsWith(PREFIX)) {
            val components = value.removePrefix(PREFIX).split(':')
            if (components.size != 2) return null
            return runCatching {
                val ref = TrackRef(readComponent(components[0]), readComponent(components[1]))
                // Reject non-canonical encodings instead of allowing aliases for the same identity.
                ref.takeIf { encode(it) == value }
            }.getOrNull()
        }
        if (value.startsWith("mei:")) return null
        if (value.startsWith("local_") && value.length > "local_".length) {
            return TrackRef(ProviderIds.LOCAL, value)
        }
        if (!legacyNumber.matches(value)) return null
        val number = value.toLongOrNull() ?: return null
        return when {
            isLocal || number < 0L && !isPodcast -> {
                if (number == Long.MIN_VALUE) null
                else TrackRef(ProviderIds.LOCAL, "local_${kotlin.math.abs(number)}")
            }
            else -> TrackRef(ProviderIds.NETEASE, value)
        }
    }

    /** Existing database relationships and offline files are deliberately not rekeyed. */
    fun storageKey(ref: TrackRef): String = when {
        ref.providerId == ProviderIds.NETEASE && legacyNumber.matches(ref.trackId) && ref.trackId.toLongOrNull() != null -> ref.trackId
        ref.providerId == ProviderIds.LOCAL && ref.trackId.startsWith("local_") -> ref.trackId
        else -> encode(ref)
    }

    /** Preserve old numeric Media3 IDs where their meaning is already unambiguous. */
    fun mediaId(ref: TrackRef): String = when {
        ref.providerId == ProviderIds.NETEASE && legacyNumber.matches(ref.trackId) && ref.trackId.toLongOrNull()?.let { it >= 0L } == true -> ref.trackId
        ref.providerId == ProviderIds.LOCAL -> ref.trackId.removePrefix("local_")
            .takeIf { ref.trackId.startsWith("local_") && it.matches(Regex("[1-9][0-9]*")) }
            ?.toLongOrNull()?.let { (-it).toString() } ?: encode(ref)
        else -> encode(ref)
    }

    private fun component(value: String): String = base64.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun readComponent(value: String): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString()
}
