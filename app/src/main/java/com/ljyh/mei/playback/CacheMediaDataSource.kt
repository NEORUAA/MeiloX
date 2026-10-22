package com.ljyh.mei.playback

import android.media.MediaDataSource
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException

/** Lets platform extractors share the exact compressed bytes used by playback. */
internal class CacheMediaDataSource(
    private val source: ResolvedMediaSource,
    private val factory: CacheDataSource.Factory,
    private val checkActive: () -> Unit,
) : MediaDataSource() {
    private val cache = requireNotNull(factory.cache)
    private val cacheKey = source.cacheKey ?: source.uri.toString()
    private val readAhead = ByteArray(READ_AHEAD_SIZE)
    private var bufferedStart = 0L
    private var bufferedLength = 0

    @Volatile
    private var closed = false

    init {
        val contentLength = source.contentLength ?: C.LENGTH_UNSET.toLong()
        if (contentLength > 0 &&
            ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey)) == C.LENGTH_UNSET.toLong()
        ) {
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, contentLength)
            cache.applyContentMetadataMutations(cacheKey, mutations)
        }
    }

    override fun getSize(): Long = source.contentLength?.takeIf { it > 0 }
        ?: ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        checkActive()
        if (closed) throw IOException("Analysis data source is closed")
        if (size == 0) return 0
        var totalRead = 0
        while (totalRead < size) {
            checkActive()
            if (closed) throw IOException("Analysis data source is closed")
            val readPosition = position + totalRead
            val contentLength = getSize()
            if (contentLength >= 0 && readPosition >= contentLength) break
            if (readPosition < bufferedStart || readPosition - bufferedStart >= bufferedLength) {
                bufferedLength = 0
                bufferedStart = readPosition
                bufferedLength = fillReadAhead(readPosition, contentLength)
                if (bufferedLength == 0) break
            }
            checkActive()
            if (closed) throw IOException("Analysis data source is closed")
            val bufferedOffset = (readPosition - bufferedStart).toInt()
            val bytesRead = minOf(size - totalRead, bufferedLength - bufferedOffset)
            readAhead.copyInto(buffer, offset + totalRead, bufferedOffset, bufferedOffset + bytesRead)
            totalRead += bytesRead
        }
        return if (totalRead > 0) totalRead else C.RESULT_END_OF_INPUT
    }

    private fun fillReadAhead(position: Long, contentLength: Long): Int {
        val length = if (contentLength >= 0) {
            minOf(READ_AHEAD_SIZE.toLong(), contentLength - position).toInt()
        } else READ_AHEAD_SIZE
        val dataSource = factory.createDataSource()
        var totalRead = 0
        try {
            // Batch tiny extractor reads without retaining a song or an open network request.
            // Never hold an open-ended cache hole while the extractor decodes or seeks.
            dataSource.open(
                DataSpec.Builder()
                    .setUri(source.uri)
                    .setKey(cacheKey)
                    .setPosition(position)
                    .setLength(length.toLong())
                    .build(),
            )
            while (totalRead < length) {
                checkActive()
                if (closed) throw IOException("Analysis data source is closed")
                val bytesRead = dataSource.read(readAhead, totalRead, length - totalRead)
                if (bytesRead == C.RESULT_END_OF_INPUT) break
                if (bytesRead == 0) throw IOException("Analysis data source made no progress")
                totalRead += bytesRead
            }
            return totalRead
        } catch (error: IOException) {
            if (contentLength < 0 && DataSourceException.isCausedByPositionOutOfRange(error)) {
                return totalRead
            }
            throw error
        } finally {
            dataSource.close()
        }
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val READ_AHEAD_SIZE = 64 * 1024
    }
}
