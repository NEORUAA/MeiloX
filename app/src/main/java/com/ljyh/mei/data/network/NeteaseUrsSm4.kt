package com.ljyh.mei.data.network

import org.bouncycastle.crypto.engines.SM4Engine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/** Direct SM4/CBC implementation; does not depend on reflective provider registration. */
internal object NeteaseUrsSm4 {
    fun transform(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16 && iv.size == 16) { "SM4 requires a 128-bit key and IV" }
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(SM4Engine()), PKCS7Padding())
        cipher.init(encrypt, ParametersWithIV(KeyParameter(key), iv))
        val output = ByteArray(cipher.getOutputSize(data.size))
        val count = cipher.processBytes(data, 0, data.size, output, 0)
        val finalCount = cipher.doFinal(output, count)
        return output.copyOf(count + finalCount)
    }
}
