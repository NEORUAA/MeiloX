package com.ljyh.mei.data.network

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.*
import org.junit.Test

class NeteaseUrsSm4Test {
    private val key = hex("0123456789abcdeffedcba9876543210")
    private val iv = ByteArray(16)
    // Independently generated with OpenSSL's SM4/CBC + PKCS7 implementation.
    private val encrypted = hex("681edf34d206965e86b3e94f536e4246677d307e844d7aa24579d556490dc7aa")

    @Test fun encryptionMatchesIndependentCbcPaddingVector() {
        assertArrayEquals(encrypted, NeteaseUrsSm4.transform(true, key, iv, key))
    }
    @Test fun decryptsIndependentCbcPaddingVector() {
        assertArrayEquals(key, NeteaseUrsSm4.transform(false, key, iv, encrypted))
    }
    @Test fun rejectsCorruptedPadding() {
        val damaged = encrypted.copyOf()
        damaged[15] = (damaged[15].toInt() xor 1).toByte()
        assertThrows(InvalidCipherTextException::class.java) {
            NeteaseUrsSm4.transform(false, key, iv, damaged)
        }
    }
    private fun hex(value: String) = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
