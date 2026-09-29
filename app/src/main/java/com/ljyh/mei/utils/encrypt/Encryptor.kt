package com.ljyh.mei.utils.encrypt

import korlibs.crypto.md5

private val CHARS = "0123456789ABCDEF"

fun encryptId(id: String): String {
    val keyBytes = "3go8&$8*3*3h0k(2)2".toByteArray()
    val idBytes = id.toByteArray()

    val xored = ByteArray(idBytes.size)
    for (i in idBytes.indices) {
        xored[i] = (idBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
    }
    return xored.md5().base64.replace("/", "_").replace("+", "-")

}


fun generateRandomMac(): String {
    val parts = Array(6) { i ->
        val high = CHARS.random()
        val low = CHARS.random()
        "$high$low"
    }
    val firstByte = parts[0].toInt(16) and 0xFE
    parts[0] = firstByte.toString(16).uppercase().padStart(2, '0')

    return parts.joinToString(":")
}
