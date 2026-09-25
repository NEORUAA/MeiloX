package com.ljyh.mei.data.network.netease

import com.github.luben.zstd.Zstd
import java.math.BigInteger
import java.security.SecureRandom

/** Encodes Netease's NCBL v3 client-log envelope. */
internal object NcblCodec {
    private const val FIXED_HEADER_SIZE = 70
    private const val META_BLOCK_TYPE = 0x4343
    private const val MAX_FRAME_SIZE = 0x8000
    private const val MAX_HEADER_SIZE = 0xffff
    private const val MAX_UINT32 = 0xffff_ffffL

    private val magic = byteArrayOf('N'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'L'.code.toByte())
    private val rsaExponent = BigInteger.valueOf(65_537L)
    private val rsaModulus = BigInteger(
        "fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d",
        16,
    )
    private val secureRandom = SecureRandom()

    /**
     * Encodes UTF-8 metadata and client-log records using a fresh NCBL key, UUID, and sequence.
     */
    fun encode(meta: ByteArray, body: ByteArray): ByteArray =
        encode(meta, body, secureRandom, ::compressZstd)

    /** Test seam for deterministic entropy and independent compression fixtures. */
    internal fun encode(
        meta: ByteArray,
        body: ByteArray,
        random: SecureRandom,
        compressor: (ByteArray) -> ByteArray,
    ): ByteArray {
        require(meta.size <= MAX_HEADER_SIZE - FIXED_HEADER_SIZE - 4) {
            "NCBL metadata exceeds the v3 header length field"
        }

        val keyA = ByteArray(32).also(random::nextBytes)
        if ((keyA[0].toInt() and 0xff) >= 0xa3) keyA[0] = 0xa2.toByte()
        val keyB = rsaWrap(keyA)

        val uuid = ByteArray(16).also(random::nextBytes)
        uuid[6] = ((uuid[6].toInt() and 0x0f) or 0x40).toByte()
        uuid[8] = ((uuid[8].toInt() and 0x3f) or 0x80).toByte()
        val nonce = uuid.copyOfRange(0, 12)
        val counter = readInt32LittleEndian(uuid, 12) ushr 2

        val sequenceBytes = ByteArray(2).also(random::nextBytes)
        val baseSequence =
            (sequenceBytes[0].toInt() and 0xff) or
                ((sequenceBytes[1].toInt() and 0xff) shl 8)

        val encryptedMeta = chacha20(keyB, counter, nonce, meta)
        val headerLength = FIXED_HEADER_SIZE + 4 + encryptedMeta.size
        val compressedBody = compressor(body)
        val frameCount = maxOf(1L, (compressedBody.size.toLong() + MAX_FRAME_SIZE - 1) / MAX_FRAME_SIZE)
        val trailingSize = compressedBody.size.toLong() + frameCount * 6L
        require(trailingSize <= MAX_UINT32) { "NCBL payload exceeds the v3 body length field" }
        val totalSize = headerLength.toLong() + trailingSize
        require(totalSize <= Int.MAX_VALUE) { "NCBL payload is too large to encode" }

        val output = ByteArray(totalSize.toInt())
        magic.copyInto(output, destinationOffset = 0)
        writeInt32LittleEndian(output, 4, 3)
        writeUInt16LittleEndian(output, 8, headerLength)
        uuid.copyInto(output, destinationOffset = 10)
        keyB.copyInto(output, destinationOffset = 26)
        writeInt32LittleEndian(output, 58, baseSequence)
        writeInt32LittleEndian(output, 62, baseSequence + frameCount.toInt() - 1)
        writeInt32LittleEndian(output, 66, trailingSize.toInt())

        writeUInt16LittleEndian(output, FIXED_HEADER_SIZE, META_BLOCK_TYPE)
        writeUInt16LittleEndian(output, FIXED_HEADER_SIZE + 2, encryptedMeta.size)
        encryptedMeta.copyInto(output, destinationOffset = FIXED_HEADER_SIZE + 4)

        var inputOffset = 0
        var outputOffset = headerLength
        var sequence = baseSequence
        var firstFrame = true
        while (inputOffset < compressedBody.size || firstFrame) {
            firstFrame = false
            val frameLength = minOf(MAX_FRAME_SIZE, compressedBody.size - inputOffset)
            writeUInt16LittleEndian(output, outputOffset, frameLength)
            writeInt32LittleEndian(output, outputOffset + 2, sequence)

            val frame = compressedBody.copyOfRange(inputOffset, inputOffset + frameLength)
            chacha20(keyA, counter, nonce, frame).copyInto(output, destinationOffset = outputOffset + 6)
            inputOffset += frameLength
            outputOffset += 6 + frameLength
            sequence++
        }

        check(outputOffset == output.size) { "NCBL frame accounting did not match its header" }
        return output
    }

    /** Raw ChaCha20 stream transform used by NCBL v3 (not the AEAD construction). */
    internal fun chacha20(key: ByteArray, initialCounter: Int, nonce: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 requires a 32-byte key" }
        require(nonce.size == 12) { "ChaCha20 requires a 12-byte nonce" }

        val output = ByteArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val block = chachaBlock(key, initialCounter + (offset ushr 6), nonce)
            val blockLength = minOf(64, input.size - offset)
            repeat(blockLength) { index ->
                output[offset + index] = (input[offset + index].toInt() xor block[index].toInt()).toByte()
            }
            offset += blockLength
        }
        return output
    }

    private fun compressZstd(input: ByteArray): ByteArray = Zstd.compress(input)

    private fun rsaWrap(keyA: ByteArray): ByteArray {
        val value = BigInteger(1, keyA)
        require(value < rsaModulus) { "NCBL RSA input must be less than the public modulus" }
        val encoded = value.modPow(rsaExponent, rsaModulus).toByteArray()
        val sourceOffset = if (encoded.size > 1 && encoded[0] == 0.toByte()) 1 else 0
        val sourceLength = encoded.size - sourceOffset
        require(sourceLength <= 32) { "NCBL RSA result exceeds its fixed-width field" }
        return ByteArray(32).also { encoded.copyInto(it, 32 - sourceLength, sourceOffset) }
    }

    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val initial = IntArray(16)
        initial[0] = 0x61707865
        initial[1] = 0x3320646e
        initial[2] = 0x79622d32
        initial[3] = 0x6b206574
        repeat(8) { index -> initial[4 + index] = readInt32LittleEndian(key, index * 4) }
        initial[12] = counter
        initial[13] = readInt32LittleEndian(nonce, 0)
        initial[14] = readInt32LittleEndian(nonce, 4)
        initial[15] = readInt32LittleEndian(nonce, 8)

        val state = initial.copyOf()
        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        return ByteArray(64) { index ->
            val word = state[index / 4] + initial[index / 4]
            (word ushr ((index % 4) * 8)).toByte()
        }
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]
        state[d] = Integer.rotateLeft(state[d] xor state[a], 16)
        state[c] += state[d]
        state[b] = Integer.rotateLeft(state[b] xor state[c], 12)
        state[a] += state[b]
        state[d] = Integer.rotateLeft(state[d] xor state[a], 8)
        state[c] += state[d]
        state[b] = Integer.rotateLeft(state[b] xor state[c], 7)
    }

    private fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeUInt16LittleEndian(bytes: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff)
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeInt32LittleEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
