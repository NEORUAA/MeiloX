package com.ljyh.mei.standalone

import com.github.luben.zstd.Zstd
import com.ljyh.mei.data.network.netease.NcblCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native loading and encoding only; no account or network access. */
class StandaloneNcblDeviceTest {
    @Test fun packagedZstdLoadsAndEncodesTheOriginalClientLogEnvelope() {
        val record = "1000\u0001_plv\u0001{\"id\":123,\"time\":0}".toByteArray()
        val compressed = Zstd.compress(record)
        assertArrayEquals(record, Zstd.decompress(compressed, record.size))
        val envelope = NcblCodec.encode("{}".toByteArray(), record)
        assertArrayEquals(byteArrayOf(0x4e, 0x43, 0x42, 0x4c, 3, 0, 0, 0), envelope.copyOfRange(0, 8))
        assertTrue(envelope.size > 80)
    }
}
