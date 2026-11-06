package com.ljyh.mei.source.local

import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.toSourceTrack
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.source.api.*
import java.io.File
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalProvider @Inject constructor(private val songs: SongRepository) : MusicProvider, CatalogCapability, PlaybackCapability {
    override val id = ProviderIds.LOCAL
    override val displayName = "本地音乐"
    override val isBuiltIn = true
    override val capabilities = setOf(SourceCapability.SEARCH, SourceCapability.TRACK, SourceCapability.PLAYBACK)
    override suspend fun search(query: String, page: PageRequest): SourcePage<Track> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = songs.getLocalSongs().first().filter { song ->
            val text = (listOf(song.title, song.album) + song.artist).joinToString(" ")
            terms.all { text.contains(it, ignoreCase = true) }
        }
        return SourcePage(matches.drop(page.offset).take(page.limit).map { it.toMediaMetadata().toSourceTrack() }, matches.size > page.offset + page.limit)
    }
    override suspend fun getTrack(ref: TrackRef): Track? {
        if (ref.providerId != id) return null
        return findSong(ref)?.toMediaMetadata()?.toSourceTrack()
    }
    override suspend fun resolve(ref: TrackRef, requestedQuality: String): AudioResource {
        if (ref.providerId != id) throw SourceException(SourceErrorCode.NOT_FOUND)
        val song = findSong(ref)
            ?: throw SourceException(SourceErrorCode.NOT_FOUND)
        val path = song.path ?: throw SourceException(SourceErrorCode.NOT_FOUND)
        val file = File(path)
        if (!file.isFile) throw SourceException(SourceErrorCode.NOT_FOUND)
        return AudioResource(file.toURI().toString(), "local", contentLength = file.length(), contentId = song.fileHash, cachePolicy = CachePolicy.NO_STORE)
    }
    private suspend fun findSong(ref: TrackRef): com.ljyh.mei.data.model.room.Song? {
        val primary = songs.getSong(TrackRefCodec.storageKey(ref)).first()
        val legacy = if (primary == null && ref.trackId.startsWith("local_")) {
            ref.trackId.removePrefix("local_").toLongOrNull()?.takeIf { it > 0 }?.let { songs.getSong((-it).toString()).first() }
        } else null
        return (primary ?: legacy)?.takeIf { it.providerId == id }
    }
}
