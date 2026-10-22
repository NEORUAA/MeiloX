package com.ljyh.mei.utils

import com.ljyh.mei.utils.ImageUtils.downloadImageBytes
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import timber.log.Timber
import java.io.File

object SongMate {

    data class TagStatus(
        val hasTitle: Boolean,
        val hasArtist: Boolean,
        val hasAlbum: Boolean,
        val hasCover: Boolean,
        val hasLyric: Boolean
    ) {
        val isComplete get() = hasTitle && hasArtist && hasAlbum && hasCover && hasLyric
        val isBasicComplete get() = hasTitle && hasArtist && hasAlbum && hasCover
    }

    fun checkTags(filePath: String): TagStatus? {
        return try {
            val file = File(filePath)
            val format = detectAudioFileFormat(file)?.takeIf { it.supportsTagWriting } ?: return null
            val audioFile = AudioFileIO.readAs(file, format.extension)
            val tag = audioFile.tagOrCreateAndSetDefault
            TagStatus(
                hasTitle = !tag.getFirst(FieldKey.TITLE).isNullOrBlank(),
                hasArtist = !tag.getFirst(FieldKey.ARTIST).isNullOrBlank(),
                hasAlbum = !tag.getFirst(FieldKey.ALBUM).isNullOrBlank(),
                hasCover = tag.firstArtwork != null,
                hasLyric = !tag.getFirst(FieldKey.LYRICS).isNullOrBlank()
            )
        } catch (e: Exception) {
            Timber.tag("SongMate").w(e, "checkTags failed for $filePath")
            null
        }
    }

    suspend fun writeTags(
        title: String,
        artist: String,
        album: String,
        coverUrl: String,
        filePath: String,
        lyric: String? = null
    ) {
        val coverBytes = if (coverUrl.isNotBlank()) downloadImageBytes(coverUrl) else null
        writeTagsWithCoverBytes(title, artist, album, coverBytes, filePath, lyric)
    }

    suspend fun writeTagsWithCoverBytes(
        title: String,
        artist: String,
        album: String,
        coverBytes: ByteArray?,
        filePath: String,
        lyric: String? = null
    ) {
        try {
            val file = File(filePath)
            val format = detectAudioFileFormat(file)?.takeIf { it.supportsTagWriting } ?: return
            // API file types and HTTP MIME types can disagree with the actual audio container.
            val audioFile = AudioFileIO.readAs(file, format.extension)
            var tag = audioFile.tagOrCreateAndSetDefault
            val isFlac = tag is FlacTag

            if (tag is AbstractID3v2Tag && tag !is ID3v24Tag) {
                val v24Tag = ID3v24Tag(tag)
                audioFile.tag = v24Tag
                tag = v24Tag
            }

            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            tag.setField(FieldKey.ALBUM, album)
            tag.setField(FieldKey.ALBUM_ARTIST, artist)

            if (!lyric.isNullOrBlank()) {
                try {
                    tag.setField(FieldKey.LYRICS, lyric)
                } catch (e: Exception) {
                    Timber.tag("SongMate").w(e, "Failed to write lyric for $title")
                }
            }

            if (coverBytes != null) {
                try {
                    val cover = readCoverImageInfo(coverBytes) ?: error("Unsupported or malformed cover image")
                    tag.deleteArtworkField()
                    if (isFlac) {
                        tag.setField(
                            (tag as FlacTag).createArtworkField(
                                coverBytes, 3,
                                cover.mimeType, "Cover",
                                cover.width, cover.height, cover.bitsPerPixel, 0
                            )
                        )
                    } else {
                        val artwork = ArtworkFactory.getNew()
                        artwork.mimeType = cover.mimeType
                        artwork.binaryData = coverBytes
                        artwork.pictureType = 3
                        artwork.description = "Cover"
                        tag.setField(artwork)
                    }
                } catch (e: Exception) {
                    Timber.tag("SongMate").w(e, "Failed to write cover for $title, skipping")
                }
            }

            audioFile.commit()
        } catch (e: Exception) {
            Timber.tag("SongMate").e(e, "writeTags failed for $filePath")
        }
    }
}

internal data class CoverImageInfo(val mimeType: String, val width: Int, val height: Int, val bitsPerPixel: Int)

/** Reads headers only; tag writing does not need to allocate or recompress a bitmap. */
internal fun readCoverImageInfo(bytes: ByteArray): CoverImageInfo? {
    fun uint16(offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    fun uint32(offset: Int): Int = (uint16(offset) shl 16) or uint16(offset + 2)
    val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if (bytes.size >= 33 && pngSignature.indices.all { bytes[it] == pngSignature[it] } &&
        uint32(8) == 13 && bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))) {
        val channels = when (bytes[25].toInt() and 0xff) {
            0, 3 -> 1
            2 -> 3
            4 -> 2
            6 -> 4
            else -> return null
        }
        val depth = bytes[24].toInt() and 0xff
        if (depth !in setOf(1, 2, 4, 8, 16)) return null
        return CoverImageInfo("image/png", uint32(16), uint32(20), depth * channels)
            .takeIf { it.width > 0 && it.height > 0 }
    }
    if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return null
    var offset = 2
    while (offset + 3 < bytes.size) {
        if (bytes[offset] != 0xff.toByte()) return null
        val marker = bytes[offset + 1].toInt() and 0xff
        if (marker == 0xff) {
            offset++
            continue
        }
        if (marker == 0xd9 || marker == 0xda) return null
        if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) {
            offset += 2
            continue
        }
        val length = uint16(offset + 2)
        if (length < 2 || length > bytes.size - offset - 2) return null
        if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
            if (length < 8) return null
            val depth = bytes[offset + 4].toInt() and 0xff
            val components = bytes[offset + 9].toInt() and 0xff
            return CoverImageInfo("image/jpeg", uint16(offset + 7), uint16(offset + 5), depth * components)
                .takeIf { it.width > 0 && it.height > 0 && it.bitsPerPixel > 0 }
        }
        offset += 2 + length
    }
    return null
}
