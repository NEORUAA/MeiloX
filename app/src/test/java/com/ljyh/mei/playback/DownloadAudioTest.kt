package com.ljyh.mei.playback

import com.ljyh.mei.utils.AudioFileFormat
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.utils.DownloadLyrics
import com.ljyh.mei.utils.ImageUtils
import com.ljyh.mei.utils.readCoverImageInfo
import com.ljyh.mei.utils.detectAudioFileFormat
import com.ljyh.mei.utils.SongMate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAudioTest {
    @Test
    fun embeddedLyricsPreferReadableSourceLrcAndKeepTranslations() {
        val lyric = lyric(
            lrc = "{\"t\":-1,\"c\":[{\"tx\":\"Credits\"}]}\n[00:01.00]First line",
            tlyric = "[00:01.00]第一句",
            yrc = "[1000,1000](1000,500,0)Word (1500,500,0)timing",
        )
        assertEquals("[00:01.00]First line\n[00:01.00]第一句", DownloadLyrics.select(lyric, ttml))
        assertEquals("[00:00.00]纯音乐，请欣赏", DownloadLyrics.select(lyric.copy(pureMusic = true), ttml))
        assertNull(DownloadLyrics.select(lyric(lrc = "{\"t\":-1,\"c\":[{\"tx\":\"Credits\"}]}"), null))
    }

    @Test
    fun wordSyncedFallbacksProduceReadableLrcWithTranslation() {
        assertEquals(
            "[00:01.000]Down for\n[00:01.000]翻译",
            DownloadLyrics.select(lyric(yrc = "[1000,2000](1000,500,0)Down (1500,500,0)for",
                tlyric = "[00:01.00]翻译"), null),
        )
        assertEquals("[00:01.000]Hello world\n[00:01.000]你好世界", DownloadLyrics.select(null, ttml))
        assertNull(DownloadLyrics.select(null, "<html>upstream error</html>"))
    }

    @Test
    fun downloadNamesLimitUtf8WithoutSplittingEmojiOrLosingTheArtist() {
        val playlist = DownloadFileNames.playlistName("专辑🎵".repeat(100))
        val song = DownloadFileNames.songBaseName("歌曲🎵".repeat(100), "歌手🎤".repeat(100))
        assertTrue(playlist.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(song.toByteArray(Charsets.UTF_8).size <= 180)
        assertTrue(song.contains(" - 歌手"))
        assertEquals(playlist, playlist.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals(song, song.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals("未分类", DownloadFileNames.playlistName(".. \n\u0000"))
        assertEquals("_CON", DownloadFileNames.playlistName("CON"))
        assertEquals("A／B＼C", DownloadFileNames.playlistName(" A/B\\C\r\n\u0000. "))
        assertEquals("未命名歌曲 - 未知歌手", DownloadFileNames.songBaseName("", ""))
    }

    @Test
    fun coverReadKeepsOriginalBytesAndBoundsKnownAndUnknownLengths() {
        val png = coverPng()
        png.toResponseBody().use { assertArrayEquals(png, ImageUtils.readCoverBytes(it)) }
        ByteArray(0).toResponseBody().use { assertNull(ImageUtils.readCoverBytes(it)) }
        val limit = ImageUtils.MAX_COVER_BYTES.toInt()
        ByteArray(limit + 1).toResponseBody().use { assertNull(ImageUtils.readCoverBytes(it)) }
        Buffer().write(ByteArray(limit + 1)).asResponseBody(contentLength = -1).use {
            assertNull(ImageUtils.readCoverBytes(it))
        }
        ByteArray(limit).toResponseBody().use { assertEquals(limit, ImageUtils.readCoverBytes(it)?.size) }
    }

    @Test
    fun coverHeadersUseActualDimensionsAndRejectTruncatedMetadata() {
        val png = coverPng()
        assertEquals("image/png", readCoverImageInfo(png)?.mimeType)
        assertEquals(16, readCoverImageInfo(png)?.width)
        assertEquals(16, readCoverImageInfo(png)?.height)
        assertNull(readCoverImageInfo(png.copyOf(20)))
        assertNull(readCoverImageInfo(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 8)))
        assertNull(readCoverImageInfo("not an image".toByteArray()))
    }

    @Test
    fun writingTagsToFlacNamedMp3PreservesTheAudioAndNativeTags() = runBlocking {
        // A complete 20 ms silent FLAC stream with a small writable padding block.
        val stream = Base64.getDecoder().decode(
            "ZkxhQwAAACIDwAPAAAAQAAAQC7gC8AAAA8DyWIZSruCEmFuFjvrDA1K5hAAADgYAAABmZm1wZWcAAAAA//h6GAADv6sAAAAAAAAZQw==",
        )
        val original = stream.copyOfRange(0, 42) + byteArrayOf(0x81.toByte(), 0, 4, 0) +
            ByteArray(1024) + flacAudioPayload(stream)
        val file = File.createTempFile("download-tag-", ".mp3")
        try {
            file.writeBytes(original)
            val cover = coverPng()
            SongMate.writeTagsWithCoverBytes("Title", "Artist", "Album", cover, file.path, "Lyrics")
            assertEquals(AudioFileFormat.FLAC, detectAudioFileFormat(file))
            val audio = AudioFileIO.readAs(file, "flac")
            assertTrue(audio.tag is FlacTag)
            assertEquals("Title", audio.tag.getFirst(FieldKey.TITLE))
            assertEquals("Lyrics", audio.tag.getFirst(FieldKey.LYRICS))
            assertArrayEquals(cover, audio.tag.firstArtwork?.binaryData)
            assertEquals("image/png", audio.tag.firstArtwork?.mimeType)
            assertEquals(3, audio.tag.firstArtwork?.pictureType)
            assertEquals(16, audio.tag.firstArtwork?.width)
            assertEquals(16, audio.tag.firstArtwork?.height)
            assertArrayEquals(flacAudioPayload(original), flacAudioPayload(file.readBytes()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun downloadFormatUsesBytesInsteadOfTheFilename() {
        withAudioFile(flac(), "mp3") { file ->
            assertEquals(AudioFileFormat.FLAC, detectAudioFileFormat(file))
            assertEquals("flac", detectAudioFileFormat(file)?.extension)
            assertEquals("audio/flac", detectAudioFileFormat(file)?.mimeType)
            assertTrue(flac().contentEquals(file.readBytes()))
        }
    }

    @Test
    fun boundedLeadingId3CanPrecedeFlacOrMp3() {
        withAudioFile(id3(32) + flac()) {
            assertEquals(AudioFileFormat.FLAC, detectAudioFileFormat(it))
        }
        withAudioFile(id3(32) + mp3Frames()) {
            assertEquals(AudioFileFormat.MP3, detectAudioFileFormat(it))
        }
    }

    @Test
    fun oversizedOrTruncatedTagsAreRejectedBeforeScanningAudio() {
        withAudioFile(id3Header(55_052_323)) { file ->
            RandomAccessFile(file, "rw").use { it.setLength(121_818_790L) }
            assertNull(detectAudioFileFormat(file))
        }
        withAudioFile(id3Header(1024) + flac()) {
            assertNull(detectAudioFileFormat(it))
        }
        withAudioFile(id3Header(16).also { it[6] = 0x80.toByte() } + flac()) {
            assertNull(detectAudioFileFormat(it))
        }
    }

    @Test
    fun mp3RequiresThreeCompleteCoherentFrames() {
        withAudioFile(mp3Frames()) {
            assertEquals(AudioFileFormat.MP3, detectAudioFileFormat(it))
        }
        withAudioFile(mp3Frames().dropLast(1).toByteArray()) {
            assertNull(detectAudioFileFormat(it))
        }
        val mismatched = mp3Frames().also { it[417 + 2] = 0x94.toByte() }
        withAudioFile(mismatched) { assertNull(detectAudioFileFormat(it)) }
        val falseHeaders = ByteArray(4096).also {
            byteArrayOf(0xff.toByte(), 0xe6.toByte(), 0xe4.toByte(), 0x50).copyInto(it)
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x24, 0x16).copyInto(it, 1024)
        }
        withAudioFile(falseHeaders) { assertNull(detectAudioFileFormat(it)) }
    }

    @Test
    fun supportedContainersHaveTheirOwnFormatAndTagSupport() {
        withAudioFile(wave()) { assertEquals(AudioFileFormat.WAV, detectAudioFileFormat(it)) }
        withAudioFile(ogg("OpusHead".toByteArray() + ByteArray(11).also { it[1] = 2 })) {
            assertEquals(AudioFileFormat.OPUS, detectAudioFileFormat(it))
            assertFalse(detectAudioFileFormat(it)!!.supportsTagWriting)
        }
        val vorbis = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23).also { it[4] = 2 }
        withAudioFile(ogg(vorbis)) { assertEquals(AudioFileFormat.VORBIS, detectAudioFileFormat(it)) }
        val m4a = bigEndian(24) + "ftypM4A ".toByteArray() + ByteArray(4) + "isommp42".toByteArray()
        withAudioFile(m4a) { assertEquals(AudioFileFormat.M4A, detectAudioFileFormat(it)) }
        withAudioFile(aacFrames()) {
            assertEquals(AudioFileFormat.AAC, detectAudioFileFormat(it))
            assertFalse(detectAudioFileFormat(it)!!.supportsTagWriting)
        }
    }

    @Test
    fun unsupportedAndTruncatedContainersAreRejected() {
        val m4a = bigEndian(24) + "ftypM4A ".toByteArray() + ByteArray(4) + "isommp42".toByteArray()
        for (bytes in listOf(
            ByteArray(100),
            "<html>not audio</html>".toByteArray(),
            flac().dropLast(1).toByteArray(),
            wave().dropLast(1).toByteArray(),
            ogg("OpusHead".toByteArray()),
            m4a.dropLast(1).toByteArray(),
            aacFrames().dropLast(1).toByteArray(),
        )) {
            withAudioFile(bytes) { assertNull(detectAudioFileFormat(it)) }
        }
    }

    private fun flac(): ByteArray = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34)

    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:01.500">Hello  </span><span begin="00:01.500" end="00:02.000">world</span><span ttm:role="x-translation">你好世界</span></p></div></body></tt>"""

    private fun lyric(lrc: String? = null, yrc: String? = null, tlyric: String? = null) = Lyric(
        code = 200, klyric = null, lrc = lrc?.let { Lyric.Lrc(it, 1) }, qfy = false,
        romalrc = null, sfy = false, sgc = false, tlyric = tlyric?.let { Lyric.Tlyric(it, 1) },
        ytlrc = null, yrc = yrc?.let { Lyric.Yrc(it, 1) }, pureMusic = false,
    )

    private fun coverPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAACXBIWXMAAAABAAAAAQBPJcTWAAAAGUlEQVR4nGOsZ6hjIAWwkKR6VMOohiGlAQBnBAE7Sw4CqQAAAABJRU5ErkJggg==",
    )

    private fun flacAudioPayload(bytes: ByteArray): ByteArray {
        var offset = 4
        do {
            val type = bytes[offset].toInt() and 0xff
            val size = (1..3).fold(0) { value, index ->
                (value shl 8) or (bytes[offset + index].toInt() and 0xff)
            }
            offset += 4 + size
        } while (type and 0x80 == 0)
        return bytes.copyOfRange(offset, bytes.size)
    }

    private fun id3(bodySize: Int): ByteArray = id3Header(bodySize) + ByteArray(bodySize)

    private fun id3Header(bodySize: Int): ByteArray =
        "ID3".toByteArray() + byteArrayOf(4, 0, 0) + ByteArray(4) { index ->
            ((bodySize ushr ((3 - index) * 7)) and 0x7f).toByte()
        }

    private fun mp3Frames(): ByteArray = ByteArray(417 * 3).also { bytes ->
        repeat(3) { index ->
            byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0).copyInto(bytes, index * 417)
        }
    }

    private fun aacFrames(): ByteArray = ByteArray(20 * 3).also { bytes ->
        repeat(3) { index ->
            byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 2, 0x9f.toByte(), 0xfc.toByte())
                .copyInto(bytes, index * 20)
        }
    }

    private fun wave(): ByteArray {
        val chunks = ByteArrayOutputStream().apply {
            write("fmt ".toByteArray())
            write(littleEndian(16))
            write(ByteArray(16))
            write("data".toByteArray())
            write(littleEndian(4))
            write(ByteArray(4))
        }.toByteArray()
        return "RIFF".toByteArray() + littleEndian(4 + chunks.size) + "WAVE".toByteArray() + chunks
    }

    private fun ogg(packet: ByteArray): ByteArray = ByteArray(27).also {
        "OggS".toByteArray().copyInto(it)
        it[5] = 2
        it[26] = 1
    } + byteArrayOf(packet.size.toByte()) + packet

    private fun littleEndian(value: Int): ByteArray = ByteArray(4) { (value ushr (it * 8)).toByte() }

    private fun bigEndian(value: Int): ByteArray = ByteArray(4) { (value ushr ((3 - it) * 8)).toByte() }

    private fun withAudioFile(bytes: ByteArray, suffix: String = "bin", block: (File) -> Unit) {
        val file = File.createTempFile("download-audio-", ".$suffix")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
