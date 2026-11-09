package com.ljyh.mei.source.api

import java.io.IOException

enum class SourceCapability { SEARCH, TRACK, PLAYBACK, LYRICS, ALBUM, ARTIST, PLAYLIST, ACCOUNT, LIBRARY, RECOMMENDATIONS, PODCAST, SOCIAL, FEED, LIKE, COLLECTIONS, PLAYLIST_EDIT, FEATURES, LINKS }
enum class SourceStatus { READY, DISABLED, NEEDS_CONFIGURATION, INCOMPATIBLE, FAILED }
enum class SourceErrorCode { AUTH_REQUIRED, AUTH_EXPIRED, UNAVAILABLE, NOT_FOUND, NETWORK, TIMEOUT, INVALID_RESPONSE, PLUGIN, UNSUPPORTED, MISSING_PROVIDER, AMBIGUOUS_MATCH, CANCELLED, PERMISSION_DENIED, VERIFICATION_REQUIRED }

/** Messages contain host-owned descriptions, never remote response bodies or credentials. */
open class SourceException(val code: SourceErrorCode, message: String = code.name,
    val outcomeUnknown: Boolean = false) : IOException(message)

internal class SourceResponseSizeException : SourceException(
    SourceErrorCode.INVALID_RESPONSE, "Source response exceeded its size limit",
)

data class SourceArtist(val name: String, val ref: ArtistRef? = null)
data class SourceAlbum(val title: String, val ref: AlbumRef? = null)
data class Track(
    val ref: TrackRef,
    val title: String,
    val artists: List<SourceArtist>,
    val album: SourceAlbum = SourceAlbum(""),
    val durationMs: Long = 0,
    val artworkUrl: String = "",
    val isrc: String? = null,
    val version: String? = null,
    val explicit: Boolean = false,
)
data class PageRequest(val offset: Int = 0, val limit: Int = 30, val cursor: String? = null, val refresh: Boolean = false) {
    init { require(offset >= 0 && limit in 1..100 && (cursor == null || cursor.length <= 4096)) }
}
data class SourcePage<T>(val items: List<T>, val hasMore: Boolean = false, val nextCursor: String? = null)
enum class CachePolicy { ALLOW, NO_STORE }

/** A resource describes the returned bytes, independently of the queue's content identity. */
data class AudioResource(
    val uri: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtMs: Long? = null,
    val contentLength: Long? = null,
    val contentId: String? = null,
    val mimeType: String? = null,
    val cachePolicy: CachePolicy = CachePolicy.ALLOW,
) {
    override fun toString(): String = "AudioResource(quality=$quality, cachePolicy=$cachePolicy)"
}
data class SourceLyrics(val text: String, val translation: String? = null)

interface MusicProvider {
    val id: String
    val instanceId: ProviderInstanceId get() = ProviderInstanceId(id)
    val displayName: String
    val version: String get() = "1"
    val capabilities: Set<SourceCapability>
    val status: SourceStatus get() = SourceStatus.READY
    val isBuiltIn: Boolean get() = false
}
interface CatalogCapability {
    suspend fun search(query: String, page: PageRequest = PageRequest()): SourcePage<Track>
    suspend fun getTrack(ref: TrackRef): Track?
}
interface PlaybackCapability {
    suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource
}
interface LyricsCapability { suspend fun getLyrics(ref: TrackRef): SourceLyrics? }
interface QualityCapability { suspend fun availableQualities(ref: TrackRef): List<String> }
/** Opaque host-mediated resources must be registered by their owning provider. */
interface ResourceValidationCapability { fun acceptsResource(resource: AudioResource): Boolean }
interface FeatureContribution { val featureRoutes: Set<String> }

data class ResolutionAttempt(val instanceId: ProviderInstanceId, val trackRef: TrackRef?, val error: SourceErrorCode? = null)
data class PlaybackResolution(
    val originTrackRef: TrackRef,
    val resolvedTrackRef: TrackRef,
    val actualAudioProviderId: String,
    val instanceId: ProviderInstanceId,
    val resource: AudioResource,
    val attempts: List<ResolutionAttempt>,
)

/** Providers can hydrate queues in one request without losing their requested identities. */
interface BatchCatalogCapability {
    val supportsBatchTrackRequests: Boolean get() = true
    suspend fun getTracks(refs: List<TrackRef>): List<Track>
}

data class SourceTrackIndex(val playlistId: String, val ids: List<TrackRef>)

/** Complete source identities are independent of metadata pagination. */
interface CatalogIndexCapability {
    suspend fun completeTrackIds(playlistId: String, collection: LibraryCollection? = null): SourceTrackIndex?
}
