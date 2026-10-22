package com.ljyh.mei.playback

import com.ljyh.mei.utils.AudioFileFormat
import com.ljyh.mei.utils.detectAudioFileFormat
import com.ljyh.mei.utils.SongMate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlinx.coroutines.runBlocking
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
            SongMate.writeTagsWithCoverBytes("Title", "Artist", "Album", null, file.path, "Lyrics")
            assertEquals(AudioFileFormat.FLAC, detectAudioFileFormat(file))
            val audio = AudioFileIO.readAs(file, "flac")
            assertTrue(audio.tag is FlacTag)
            assertEquals("Title", audio.tag.getFirst(FieldKey.TITLE))
            assertEquals("Lyrics", audio.tag.getFirst(FieldKey.LYRICS))
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
