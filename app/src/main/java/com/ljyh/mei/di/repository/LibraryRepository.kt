package com.ljyh.mei.di.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.ktx.themeColorOrNull
import com.ljyh.mei.data.model.room.AlbumEntity
import com.ljyh.mei.data.model.room.AlbumWithArtists
import com.ljyh.mei.data.model.room.ArtistEntity
import com.ljyh.mei.data.model.room.CacheColor
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.HistoryItem
import com.ljyh.mei.data.model.room.Like
import com.ljyh.mei.data.model.room.PlaybackHistory
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.di.dao.AlbumsDao
import com.ljyh.mei.di.dao.CachedLyricDao
import com.ljyh.mei.di.dao.ColorDao
import com.ljyh.mei.di.dao.HistoryDao
import com.ljyh.mei.di.dao.LikeDao
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.utils.color.CoverBrightness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ColorRepository @Inject constructor(private val colorDao: ColorDao) {
    private val memoryCache = ConcurrentHashMap<String, Color>()
    private val coverBrightnessCache = ConcurrentHashMap<String, Boolean>()

    fun getFromMemory(url: String): Color? {
        if (url.isEmpty()) return null
        return memoryCache[themeColorCacheKey(url)]
    }

    fun getCachedCoverIsDark(url: String): Boolean? {
        if (url.isEmpty()) return null
        return coverBrightnessCache[coverBrightnessCacheKey(url)]
    }

    suspend fun getCoverIsDarkOrExtract(context: Context, url: String): Boolean? =
        withContext(Dispatchers.IO) {
            if (url.isEmpty()) return@withContext null
            val cacheKey = coverBrightnessCacheKey(url)
            coverBrightnessCache[cacheKey]?.let { return@withContext it }
            try {
                colorDao.getColor(cacheKey)?.color?.let(::decodeCoverBrightness)?.let {
                    coverBrightnessCache[cacheKey] = it
                    return@withContext it
                }
                val bitmap = loadCoverBitmap(context, url) ?: return@withContext null
                cacheCoverBrightness(url, bitmap)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "Failed to classify cover brightness")
                null
            }
        }

    suspend fun getColorOrExtract(context: Context, url: String): Color = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext Color.Black
        val cacheKey = themeColorCacheKey(url)
        memoryCache[cacheKey]?.let { return@withContext it }
        val dbEntity = colorDao.getColor(cacheKey)
        if (dbEntity != null) {
            val color = Color(dbEntity.color)
            memoryCache[cacheKey] = color
            return@withContext color
        }
        val bitmap = loadCoverBitmap(context, url)
        val finalColor = bitmap?.let(::extractMaterialThemeColor) ?: Color.Black
        memoryCache[cacheKey] = finalColor
        colorDao.insertColor(CacheColor(url = cacheKey, color = finalColor.toArgb()))
        if (bitmap != null) {
            try {
                cacheCoverBrightness(url, bitmap)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Brightness is optional metadata; preserve the seed extraction's result.
                Timber.w(exception, "Failed to cache cover brightness")
            }
        }
        finalColor
    }

    private suspend fun loadCoverBitmap(context: Context, url: String): Bitmap? {
        // Identical requests share Coil's existing memory and disk caches.
        val request = ImageRequest.Builder(context)
            .data(url).allowHardware(false).size(128).build()
        val result = context.imageLoader.execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    private suspend fun cacheCoverBrightness(url: String, bitmap: Bitmap): Boolean? {
        val cacheKey = coverBrightnessCacheKey(url)
        coverBrightnessCache[cacheKey]?.let { return it }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val isDark = CoverBrightness.isDark(pixels, bitmap.width, bitmap.height) ?: return null
        colorDao.insertColor(CacheColor(url = cacheKey, color = if (isDark) 1 else 0))
        coverBrightnessCache[cacheKey] = isDark
        return isDark
    }

    private fun extractMaterialThemeColor(bitmap: Bitmap): Color =
        bitmap.asImageBitmap().themeColorOrNull() ?: Color.Black

    private fun themeColorCacheKey(url: String): String = "$THEME_COLOR_CACHE_VERSION$url"
    private fun coverBrightnessCacheKey(url: String): String = "$COVER_BRIGHTNESS_CACHE_VERSION$url"

    private fun decodeCoverBrightness(value: Int): Boolean? = when (value) {
        0 -> false
        1 -> true
        else -> null
    }

    fun getDbColor(url: String): Color? = colorDao.getColor(url)?.let { Color(it.color) }
    suspend fun insertColor(color: CacheColor) = colorDao.insertColor(color)

    private companion object {
        const val THEME_COLOR_CACHE_VERSION = "md3-v1:"
        // Separate cache namespace: color stores 0 for a light cover, 1 for a dark cover.
        const val COVER_BRIGHTNESS_CACHE_VERSION = "cover-brightness-v1:"
    }
}

class QQSongRepository @Inject constructor(private val qqSongDao: com.ljyh.mei.di.dao.QQSongDao) {
    fun getQQSong(id: String): Flow<com.ljyh.mei.data.model.room.QQSong?> = qqSongDao.getSong(id)
    suspend fun insertSong(song: com.ljyh.mei.data.model.room.QQSong) = qqSongDao.insertSong(song)
    suspend fun deleteSongById(id: String) = qqSongDao.deleteSongById(id)
    suspend fun deleteAll() = qqSongDao.deleteAll()
}

class LikeRepository @Inject constructor(private val likeDao: LikeDao) {
    suspend fun getLike(id: String): Like? = likeDao.getLike(id)
    suspend fun getAllLike(): List<Like> = likeDao.getALlLike()
    suspend fun insertLike(like: Like) = likeDao.insertLike(like)
    suspend fun updateAllLike(likes: List<Like>) = likeDao.updateALlLike(likes)
    suspend fun deleteLike(id: String) = likeDao.deleteLike(id)
}

@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao,
    private val songDao: SongDao
) {
    suspend fun addToHistory(song: Song, playedAt: Long = System.currentTimeMillis()) {
        try {
            historyDao.addSongToHistory(song, playedAt)
        } catch (e: Exception) {
            if (e is SQLiteConstraintException) {
                songDao.insertSongs(listOf(song))
                historyDao.insertHistory(PlaybackHistory(songId = song.id, playedAt = playedAt))
            } else throw e
        }
    }

    fun getHistoryStream(): Flow<List<HistoryItem>> = historyDao.getHistory()
    suspend fun getHistory(): List<HistoryItem> = historyDao.getHistory().first()
    suspend fun clearHistory() = historyDao.clearHistory()
    suspend fun removeFromHistory(songId: String) = historyDao.deleteHistoryBySongId(songId)
    suspend fun addMultipleToHistory(songs: List<Song>) { songs.forEach { addToHistory(it) } }
    suspend fun getRecentSongs(limit: Int = 20): List<HistoryItem> = historyDao.getHistory().first().take(limit)
    suspend fun isSongInHistory(songId: String): Boolean = historyDao.getHistory().first().any { it.song.id == songId }
    suspend fun getHistoryCount(): Int = historyDao.getHistory().first().size
}

class AlbumsRepository @Inject constructor(private val dao: AlbumsDao) {
    suspend fun getAlbumWithArtists(id: Long): AlbumWithArtists = dao.getAlbumWithArtists(id)
    suspend fun getAlbumsByArtist(id: Long): List<AlbumEntity> = dao.getAlbumsByArtist(id)
    suspend fun insertAlbum(album: AlbumEntity, artists: List<ArtistEntity>) = dao.insertAlbumWithArtists(album, artists)
    suspend fun existsAlbum(albumId: Long): Boolean = dao.existsAlbum(albumId)
    suspend fun deleteAlbum(albumId: Long) { dao.deleteAlbumById(albumId); dao.deleteOrphanedArtists() }
    suspend fun deleteAlbums(albumIds: List<Long>) { dao.deleteAlbumsByIds(albumIds); dao.deleteOrphanedArtists() }
    suspend fun deleteArtistWithCleanup(artistId: Long) {
        dao.deleteArtistFromAllAlbums(artistId)
        if (!dao.isArtistUsed(artistId)) dao.deleteArtist(artistId)
    }
}

class CachedLyricRepository @Inject constructor(private val dao: CachedLyricDao) {
    fun get(songId: String): Flow<CachedLyric?> = dao.get(songId)
    suspend fun insert(lyric: CachedLyric) = dao.insert(lyric)
    suspend fun delete(songId: String) = dao.delete(songId)
    suspend fun deleteOld(before: Long) = dao.deleteOld(before)
    suspend fun deleteAll() = dao.deleteAll()
}
