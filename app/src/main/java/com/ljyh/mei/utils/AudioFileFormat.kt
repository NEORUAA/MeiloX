package com.ljyh.mei.utils

import java.io.File
import java.io.RandomAccessFile

internal enum class AudioFileFormat(
    val extension: String,
    val mimeType: String,
    val supportsTagWriting: Boolean,
) {
    FLAC("flac", "audio/flac", true),
    MP3("mp3", "audio/mpeg", true),
    WAV("wav", "audio/wav", true),
    VORBIS("ogg", "audio/ogg", true),
    OPUS("opus", "audio/opus", false),
    M4A("m4a", "audio/mp4", true),
    AAC("aac", "audio/aac", false),
}

private const val MAX_LEADING_ID3_BYTES = 8 * 1024 * 1024L
private const val MAX_FRAME_SEARCH_BYTES = 4096

/** Identifies the bytes before a tag reader can mistake another format for MPEG audio. */
internal fun detectAudioFileFormat(file: File): AudioFileFormat? =
    RandomAccessFile(file, "r").use { input ->
        val offset = input.skipLeadingId3() ?: return@use null
        val magic = input.bytesAt(offset, 12) ?: return@use null
        when {
            magic.startsWithAscii("fLaC") -> {
                val blockSize = ((magic[5].unsigned() shl 16) or
                    (magic[6].unsigned() shl 8) or magic[7].unsigned())
                AudioFileFormat.FLAC.takeIf {
                    magic[4].unsigned() and 0x7f == 0 && blockSize == 34 &&
                        offset + 8 + blockSize <= input.length()
                }
            }
            magic.startsWithAscii("RIFF") && magic.startsWithAscii("WAVE", 8) ->
                AudioFileFormat.WAV.takeIf { input.hasWaveChunks(offset, magic) }
            magic.startsWithAscii("OggS") -> input.oggFormat(offset)
            magic.startsWithAscii("ftyp", 4) -> input.mp4Format(offset)
            else -> input.framedFormat(offset)
        }
    }

private fun RandomAccessFile.skipLeadingId3(): Long? {
    var offset = 0L
    repeat(4) {
        if (bytesAt(offset, 3)?.startsWithAscii("ID3") != true) return offset
        val header = bytesAt(offset, 10) ?: return null
        if (header[3].unsigned() !in 2..4 || header[4].unsigned() == 255 ||
            (6..9).any { header[it].unsigned() >= 128 }) return null
        val bodySize = (6..9).fold(0L) { size, index -> (size shl 7) or header[index].toLong() }
        val footerSize = if (header[3].unsigned() == 4 && header[5].unsigned() and 0x10 != 0) 10 else 0
        offset += 10 + bodySize + footerSize
        if (offset > MAX_LEADING_ID3_BYTES || offset >= length()) return null
        if (footerSize != 0 && bytesAt(offset - 10, 3)?.startsWithAscii("3DI") != true) return null
    }
    return offset.takeUnless { bytesAt(it, 3)?.startsWithAscii("ID3") == true }
}

private fun RandomAccessFile.hasWaveChunks(offset: Long, header: ByteArray): Boolean {
    val end = offset + 8 + header.littleEndianInt(4)
    if (end > length() || end < offset + 12) return false
    var position = offset + 12
    var hasFormat = false
    var hasData = false
    repeat(64) {
        if (position + 8 > end) return false
        val chunk = bytesAt(position, 8) ?: return false
        val size = chunk.littleEndianInt(4)
        if (position + 8 + size > end) return false
        if (chunk.startsWithAscii("fmt ")) hasFormat = size >= 16
        if (chunk.startsWithAscii("data")) hasData = size > 0
        if (hasFormat && hasData) return true
        position += 8 + size + (size and 1)
    }
    return false
}

private fun RandomAccessFile.oggFormat(offset: Long): AudioFileFormat? {
    val header = bytesAt(offset, 27) ?: return null
    if (header[4].unsigned() != 0 || header[5].unsigned() and 2 == 0) return null
    val segments = bytesAt(offset + 27, header[26].unsigned()) ?: return null
    if (segments.isEmpty()) return null
    val payloadOffset = offset + 27 + segments.size
    if (payloadOffset + segments.sumOf { it.unsigned() } > length()) return null
    var packetSize = 0
    for (segment in segments) {
        packetSize += segment.unsigned()
        if (segment.unsigned() < 255) {
            val packet = bytesAt(payloadOffset, minOf(packetSize, 30)) ?: return null
            return when {
                packetSize >= 19 && packet.startsWithAscii("OpusHead") && packet[9].unsigned() > 0 ->
                    AudioFileFormat.OPUS
                packetSize >= 30 && packet[0].unsigned() == 1 &&
                    packet.startsWithAscii("vorbis", 1) && packet[11].unsigned() > 0 ->
                    AudioFileFormat.VORBIS
                else -> null
            }
        }
    }
    return null
}

private fun RandomAccessFile.mp4Format(offset: Long): AudioFileFormat? {
    val header = bytesAt(offset, 16) ?: return null
    val size = header.bigEndianInt(0)
    if (size < 16 || size > 4096 || offset + size > length() || size % 4 != 0L) return null
    val brands = bytesAt(offset + 8, (size - 8).toInt()) ?: return null
    val knownBrands = setOf("M4A ", "M4B ", "M4P ", "isom", "iso2", "mp41", "mp42", "qt  ")
    return AudioFileFormat.M4A.takeIf {
        (brands.indices step 4).any { index ->
            index != 4 && knownBrands.any { brand -> brands.startsWithAscii(brand, index) }
        }
    }
}

private data class AudioFrame(val format: AudioFileFormat, val configuration: Int, val size: Int)

private fun RandomAccessFile.framedFormat(offset: Long): AudioFileFormat? {
    val searchSize = minOf(MAX_FRAME_SEARCH_BYTES.toLong(), length() - offset).toInt()
    val prefix = bytesAt(offset, searchSize) ?: return null
    for (index in 0 until (prefix.size - 3)) {
        if (prefix[index].unsigned() != 0xff) continue
        val start = offset + index
        val first = audioFrame(start) ?: continue
        var position = start
        var valid = true
        repeat(3) {
            val frame = audioFrame(position)
            if (frame == null || frame.format != first.format ||
                frame.configuration != first.configuration || position + frame.size > length()) {
                valid = false
            } else {
                position += frame.size
            }
        }
        if (valid) return first.format
    }
    return null
}

private fun RandomAccessFile.audioFrame(offset: Long): AudioFrame? {
    val bytes = bytesAt(offset, 4) ?: return null
    val header = bytes.bigEndianInt(0).toInt()
    if (header ushr 21 == 0x7ff && (header ushr 17) and 3 == 1) {
        val version = (header ushr 19) and 3
        val layer = (header ushr 17) and 3
        val bitrateIndex = (header ushr 12) and 15
        val rateIndex = (header ushr 10) and 3
        if (version == 1 || layer != 1 || bitrateIndex !in 1..14 || rateIndex == 3) return null
        val bitrates = if (version == 3) MPEG1_BITRATES else MPEG2_BITRATES
        val rate = SAMPLE_RATES[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val size = (if (version == 3) 144 else 72) * bitrates[bitrateIndex] * 1000 / rate +
            ((header ushr 9) and 1)
        return AudioFrame(AudioFileFormat.MP3, version * 4 + rateIndex, size)
    }
    val adts = bytesAt(offset, 7) ?: return null
    if (adts[0].unsigned() != 0xff || adts[1].unsigned() and 0xf6 != 0xf0) return null
    val rateIndex = (adts[2].unsigned() ushr 2) and 15
    val channels = ((adts[2].unsigned() and 1) shl 2) or (adts[3].unsigned() ushr 6)
    val size = ((adts[3].unsigned() and 3) shl 11) or
        (adts[4].unsigned() shl 3) or (adts[5].unsigned() ushr 5)
    val headerSize = if (adts[1].unsigned() and 1 != 0) 7 else 9
    if (rateIndex > 12 || size < headerSize) return null
    return AudioFrame(AudioFileFormat.AAC, (adts[2].unsigned() ushr 6) * 128 + rateIndex * 8 + channels, size)
}

private val MPEG1_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
private val MPEG2_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

private fun RandomAccessFile.bytesAt(offset: Long, count: Int): ByteArray? {
    if (offset < 0 || count < 0 || offset > length() - count) return null
    return ByteArray(count).also { seek(offset); readFully(it) }
}

private fun Byte.unsigned(): Int = toInt() and 0xff

private fun ByteArray.startsWithAscii(value: String, offset: Int = 0): Boolean =
    offset >= 0 && offset + value.length <= size && value.indices.all { this[offset + it].unsigned() == value[it].code }

private fun ByteArray.littleEndianInt(offset: Int): Long =
    (0..3).fold(0L) { value, index -> value or (this[offset + index].unsigned().toLong() shl (index * 8)) }

private fun ByteArray.bigEndianInt(offset: Int): Long =
    (0..3).fold(0L) { value, index -> (value shl 8) or this[offset + index].unsigned().toLong() }
