package com.ljyh.mei.playback

import com.ljyh.mei.utils.StringUtils.specialReplace
import java.util.Locale

/** Limits each generated path segment by UTF-8 bytes without changing the configured root. */
internal object DownloadFileNames {
    private val reservedNames = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun playlistName(raw: String): String = safeSegment(raw, "未分类", 120)

    fun songBaseName(title: String, artist: String): String {
        val safeTitle = safeSegment(title, "未命名歌曲", 100)
        val safeArtist = safeSegment(artist, "未知歌手", 70)
        return safeSegment("$safeTitle - $safeArtist", "未命名歌曲", 180)
    }

    private fun safeSegment(raw: String, fallback: String, maxBytes: Int): String {
        val replaced = specialReplace(raw)
        val cleaned = buildString(replaced.length) {
            var index = 0
            while (index < replaced.length) {
                val point = replaced.codePointAt(index)
                if (!Character.isISOControl(point) && point !in 0xd800..0xdfff) appendCodePoint(point)
                index += Character.charCount(point)
            }
        }.trim().trimEnd('.', ' ')
        val normalized = cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: fallback
        val limited = truncateUtf8(normalized, maxBytes).trimEnd('.', ' ').ifBlank { fallback }
        return if (limited.substringBefore('.').uppercase(Locale.ROOT) in reservedNames) {
            "_${truncateUtf8(limited, maxBytes - 1).trimEnd('.', ' ')}"
        } else limited
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String = buildString {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val text = String(Character.toChars(point))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            append(text)
            bytes += size
            index += Character.charCount(point)
        }
    }
}
