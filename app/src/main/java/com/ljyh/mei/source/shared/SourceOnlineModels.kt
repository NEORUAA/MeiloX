package com.ljyh.mei.source.shared

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.source.api.*

enum class OnlineSessionKind { None, Web, Mobile, Other }
data class OnlineSessionState(val providerId: String, val authenticated: Boolean, val kind: OnlineSessionKind)

data class OnlineDailyRecommendation(val ref: TrackRef, val reason: String)
data class OnlineDailyRecommendations(
    val title: String, val tracks: List<Track>, val creatorName: String = "", val description: String? = null,
    val updatedAtMs: Long? = null, val recommendations: List<OnlineDailyRecommendation> = emptyList(),
)

data class OnlineAccountProfile(
    val id: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val backgroundUrl: String? = null,
    val signature: String? = null,
    val follows: Int? = null,
    val followers: Int? = null,
    val eventCount: Int? = null,
    val playlistCount: Int? = null,
    val playlistSubscribedCount: Int? = null,
    val artistId: String? = null,
    val hasVerifiedIdentity: Boolean = false,
    val providerId: String = ProviderIds.NETEASE,
    val instanceId: ProviderInstanceId = ProviderInstanceId(providerId),
) {
    val ref: SourceAccountRef get() = SourceAccountRef(instanceId, ProviderAccountId(id))
    val artistRef: ArtistRef? get() = artistId?.let { ArtistRef(providerId, it) }
}
data class OnlineAccountDetail(val profile: OnlineAccountProfile, val level: Int, val listenSongs: Int?, val createDays: Int?)
data class OnlineAccountConnectionPage(val users: List<OnlineAccountProfile>, val hasMore: Boolean, val nextOffset: Int)
data class OnlineAccountPlaylist(
    val id: String, val name: String, val coverUrl: String?, val trackCount: Int,
    val creatorId: String?, val creatorName: String?, val providerId: String = ProviderIds.NETEASE,
) { val ref: PlaylistRef get() = PlaylistRef(providerId, id) }
data class OnlineAccountSong(val track: Track, val playedAt: Long? = null) {
    val id: String get() = track.ref.trackId
    val name: String get() = track.title
    val artists: List<String> get() = track.artists.map { it.name }
    val album: String get() = track.album.title
    val coverUrl: String get() = track.artworkUrl
    val durationMs: Long get() = track.durationMs
}
data class OnlineUserPlayRecord(val song: OnlineAccountSong, val playCount: Int?, val score: Int?)

data class OnlineCloudSong(
    val id: String, val name: String, val artist: String, val album: String, val coverUrl: String?,
    val durationMs: Long, val fileSize: Long, val bitrate: Int, val addTime: Long,
    val providerId: String = ProviderIds.NETEASE,
) {
    val track: Track get() = Track(TrackRef(providerId, id), name, listOf(SourceArtist(artist)), SourceAlbum(album), durationMs, coverUrl.orEmpty())
}
data class OnlineCloudPage(
    val songs: List<OnlineCloudSong>, val count: Int, val usedSize: Long, val maxSize: Long,
    val hasMore: Boolean, val nextOffset: Int = songs.size,
)

data class OnlinePodcastHost(val id: String, val nickname: String, val avatarUrl: String?)
data class OnlinePodcast(
    val id: String, val name: String, val picUrl: String?, val description: String?, val recommendation: String?,
    val categoryId: String?, val category: String?, val secondCategory: String?, val programCount: Int,
    val subscriberCount: Long, val playCount: Long, val host: OnlinePodcastHost?, val isSubscribed: Boolean,
    val feeType: Int?, val providerId: String = ProviderIds.NETEASE,
)
data class OnlinePodcastCategory(val id: String, val name: String, val picUrl: String?)
data class OnlinePodcastProgram(
    val id: String, val name: String, val coverUrl: String?, val description: String?, val createTime: Long?,
    val durationMs: Long, val listenerCount: Long, val likedCount: Long, val commentCount: Long, val serialNumber: Int?,
    val radioId: String, val radioName: String, val host: OnlinePodcastHost?, val mainSongId: String?,
    val providerId: String = ProviderIds.NETEASE,
) {
    val mainTrackRef: TrackRef? get() = mainSongId?.let { TrackRef(providerId, it) }
    fun toMediaMetadata(): MediaMetadata = Track(
        // Unavailable episodes retain their real episode ID for display and never enter a playback queue.
        mainTrackRef ?: TrackRef(providerId, id), name,
        listOf(SourceArtist(host?.nickname ?: radioName)), SourceAlbum(radioName), durationMs, coverUrl.orEmpty(),
    ).toMediaMetadata().copy(isPodcast = true)
}
data class OnlinePodcastHome(val categories: List<OnlinePodcastCategory>, val featured: List<OnlinePodcast>, val personalized: List<OnlinePodcast>)
data class OnlinePodcastPage(val podcasts: List<OnlinePodcast>, val hasMore: Boolean, val totalCount: Int, val fetchedCount: Int = podcasts.size)
data class OnlinePodcastProgramPage(val programs: List<OnlinePodcastProgram>, val hasMore: Boolean, val totalCount: Int, val fetchedCount: Int = programs.size)
data class OnlinePodcastDetail(val podcast: OnlinePodcast, val programs: List<OnlinePodcastProgram>, val hasMore: Boolean, val totalCount: Int, val nextOffset: Int = programs.size)

data class OnlineWikiSong(val id: String, val title: String, val artist: String?, val note: String?, val artworkUrl: String?, val providerId: String) {
    val track: Track get() = Track(TrackRef(providerId, id), title, artist.orEmpty().split(" / ").filter(String::isNotBlank).map { SourceArtist(it) }, artworkUrl = artworkUrl.orEmpty())
}
data class OnlineWikiPlaylist(val id: String, val title: String, val artworkUrl: String?, val playCount: Long, val providerId: String) {
    val ref: PlaylistRef get() = PlaylistRef(providerId, id)
}
data class OnlineSongWiki(
    val memories: List<SongWikiMemoryItem>, val tagGroups: List<SongWikiTagGroup>, val attributes: List<SongWikiAttribute>,
    val associationGroups: List<SongWikiAssociationGroup>, val reviews: List<SongWikiReview>,
    val similarSongs: List<OnlineWikiSong>, val relatedPlaylists: List<OnlineWikiPlaylist>, val contributionUrl: String?,
) {
    val isEmpty: Boolean get() = memories.isEmpty() && tagGroups.isEmpty() && attributes.isEmpty() && associationGroups.isEmpty() &&
        reviews.isEmpty() && similarSongs.isEmpty() && relatedPlaylists.isEmpty()
}

data class OnlineCommentUser(val id: String, val nickname: String, val avatarUrl: String?)
data class OnlineComment(
    val commentId: String, val user: OnlineCommentUser, val content: String, val time: Long, val timeStr: String,
    val liked: Boolean, val likedCount: Int, val replyCount: Int,
)
data class OnlineCommentPage(val comments: List<OnlineComment>, val hasMore: Boolean, val cursor: String?, val totalCount: Int)
data class OnlineFloorPage(val comments: List<OnlineComment>, val hasMore: Boolean, val time: Long, val totalCount: Int)
