package com.ljyh.mei.data.model.melox

data class PodcastHost(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
)

data class Podcast(
    val id: Long,
    val name: String,
    val picUrl: String?,
    val description: String?,
    val recommendation: String?,
    val categoryId: Long?,
    val category: String?,
    val secondCategory: String?,
    val programCount: Int,
    val subscriberCount: Long,
    val playCount: Long,
    val host: PodcastHost?,
    val isSubscribed: Boolean,
    val feeType: Int?,
)

data class PodcastCategory(
    val id: Long,
    val name: String,
    val picUrl: String?,
)

data class PodcastProgram(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val description: String?,
    val createTime: Long?,
    val durationMs: Long,
    val listenerCount: Long,
    val likedCount: Long,
    val commentCount: Long,
    val serialNumber: Int?,
    val radioId: Long,
    val radioName: String,
    val host: PodcastHost?,
    val mainSongId: Long?,
)

data class PodcastHome(
    val categories: List<PodcastCategory>,
    val featured: List<Podcast>,
    val personalized: List<Podcast>,
)

data class PodcastPage(
    val podcasts: List<Podcast>,
    val hasMore: Boolean,
    val totalCount: Int,
)

data class PodcastProgramPage(
    val programs: List<PodcastProgram>,
    val hasMore: Boolean,
    val totalCount: Int,
)

data class PodcastDetail(
    val podcast: Podcast,
    val programs: List<PodcastProgram>,
    val hasMore: Boolean,
    val totalCount: Int,
)

data class CloudSong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val fileSize: Long,
    val bitrate: Int,
    val addTime: Long,
)

data class CloudMusicPage(
    val songs: List<CloudSong>,
    val count: Int,
    val usedSize: Long,
    val maxSize: Long,
    val hasMore: Boolean,
)

data class MessageContact(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
    val signature: String?,
    val remarkName: String?,
) {
    val displayName: String get() = remarkName?.takeIf(String::isNotBlank) ?: nickname
}

enum class ShareResourceKind(val wireValue: String) { Song("song"), Playlist("playlist"), Album("album") }

data class ShareResource(
    val kind: ShareResourceKind,
    val id: Long,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
)

data class PrivateMessagePayload(
    val text: String,
    val resource: ShareResource?,
) {
    val summary: String get() = text.takeIf(String::isNotBlank)
        ?: resource?.let { "[${it.kind.wireValue}] ${it.title}" }
        ?: "Private message"
}

data class PrivateConversation(
    val id: String,
    val fromUser: MessageContact?,
    val toUser: MessageContact?,
    val lastMessageTime: Long,
    val summary: String,
    val unreadCount: Int,
)

data class PrivateMessage(
    val id: Long,
    val fromUser: MessageContact?,
    val toUser: MessageContact?,
    val time: Long,
    val payload: PrivateMessagePayload,
)

data class ListenTogetherUser(
    val id: String,
    val nickname: String,
    val avatarUrl: String?,
)

data class ListenTogetherRoom(
    val id: String,
    val creatorId: String,
    val users: List<ListenTogetherUser>,
    val createTime: Long?,
    val effectiveDurationMs: Long?,
)

data class ListenTogetherInvitation(
    val roomId: String,
    val inviterId: String,
    val songId: Long?,
) {
    companion object {
        fun parse(text: String): ListenTogetherInvitation? {
            val normalized = text.trim().replace("&amp;", "&")
            if (normalized.isEmpty()) return null
            val candidate = normalized
                .split(Regex("\\s+"))
                .firstOrNull { it.contains("roomId=", ignoreCase = true) }
                ?: normalized
            val values = Regex("(?:[?&#]|^)([A-Za-z][A-Za-z0-9]*)=([^&#\\s]+)")
                .findAll(candidate)
                .associate { match ->
                    match.groupValues[1].lowercase() to java.net.URLDecoder.decode(
                        match.groupValues[2],
                        Charsets.UTF_8.name(),
                    ).trim()
                }
            val roomId = values["roomid"]?.takeIf(String::isNotBlank) ?: return null
            val inviterId = (values["inviterid"] ?: values["inviteruid"])
                ?.takeIf(String::isNotBlank) ?: return null
            return ListenTogetherInvitation(roomId, inviterId, values["songid"]?.toLongOrNull())
        }
    }
}

data class ListenTogetherStatus(
    val isInRoom: Boolean,
    val room: ListenTogetherRoom?,
    val status: String?,
)

enum class ListenTogetherCommand(val wireValue: String) {
    Play("PLAY"), Pause("PAUSE"), Next("NEXT"), Previous("PREV"), GoTo("GOTO"), Progress("PROGRESS")
}

data class ListenTogetherPlaybackCommand(
    val commandType: String?,
    val targetSongId: Long?,
    val formerSongId: Long?,
    val progressMs: Long,
    val isPlaying: Boolean?,
    val clientSequence: Long,
    val serverSequence: Long,
)

data class ListenTogetherPlaybackSnapshot(
    val songIds: List<Long>,
    val playMode: String?,
    val command: ListenTogetherPlaybackCommand?,
)

data class RecognizedSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val startTimeMs: Long?,
)

enum class SongWikiMemoryKind { FirstListen, TotalPlay }

data class SongWikiMemoryItem(
    val id: String,
    val kind: SongWikiMemoryKind,
    val date: String? = null,
    val playCount: Long? = null,
    val durationMinutes: Long? = null,
    val text: String? = null,
)

data class SongWikiTagGroup(val id: String, val title: String?, val values: List<String>)
data class SongWikiAttribute(val id: String, val title: String?, val value: String)
data class SongWikiAssociationDetail(
    val id: String,
    val title: String?,
    val subtitle: String?,
    val body: String?,
)
data class SongWikiAssociationGroup(
    val id: String,
    val title: String?,
    val countText: String?,
    val details: List<SongWikiAssociationDetail>,
)
data class SongWikiReview(val id: String, val attribution: String?, val body: String)
data class SongWikiSongReference(
    val id: Long,
    val title: String,
    val artist: String?,
    val note: String?,
    val artworkUrl: String?,
)
data class SongWikiPlaylistReference(
    val id: Long,
    val title: String,
    val artworkUrl: String?,
    val playCount: Long,
)
data class SongWiki(
    val memories: List<SongWikiMemoryItem>,
    val tagGroups: List<SongWikiTagGroup>,
    val attributes: List<SongWikiAttribute>,
    val associationGroups: List<SongWikiAssociationGroup>,
    val reviews: List<SongWikiReview>,
    val similarSongs: List<SongWikiSongReference>,
    val relatedPlaylists: List<SongWikiPlaylistReference>,
    val contributionUrl: String?,
) {
    val isEmpty: Boolean get() = memories.isEmpty() && tagGroups.isEmpty() &&
        attributes.isEmpty() && associationGroups.isEmpty() && reviews.isEmpty() &&
        similarSongs.isEmpty() && relatedPlaylists.isEmpty() && contributionUrl == null
}

data class SearchDiscovery(
    val recommendations: List<SearchDiscoveryPlaylist>,
)

data class SearchDiscoveryPlaylist(
    val id: Long,
    val name: String,
    val artworkUrl: String?,
    val copywriter: String?,
    val creatorNickname: String?,
)

data class AccountProfile(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
    val backgroundUrl: String?,
    val signature: String?,
    val follows: Int?,
    val followers: Int?,
    val eventCount: Int?,
    val playlistCount: Int?,
    val playlistSubscribedCount: Int?,
)

data class AccountDetail(
    val profile: AccountProfile,
    val level: Int,
    val listenSongs: Int,
    val createDays: Int?,
)

data class AccountPlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val creatorId: Long?,
    val creatorName: String?,
)

data class AccountSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val artistIds: List<Long> = emptyList(),
    val albumId: Long = 0,
)

data class UserPlayRecord(
    val song: AccountSong,
    val playCount: Int,
    val score: Int?,
)
