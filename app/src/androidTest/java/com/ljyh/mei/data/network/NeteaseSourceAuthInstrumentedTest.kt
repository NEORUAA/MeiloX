package com.ljyh.mei.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseSourceAuthInstrumentedTest {
    @Test fun assetsContainNoExecutableDex() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        fun inspect(directory: String) {
            for (name in assets.list(directory).orEmpty()) {
                val path = if (directory.isEmpty()) name else "$directory/$name"
                val children = assets.list(path).orEmpty()
                if (children.isNotEmpty()) inspect(path)
                else assets.open(path).use { input ->
                    val magic = ByteArray(8)
                    val count = input.read(magic)
                    assertFalse("Executable asset: $path", name.endsWith(".dex", true) ||
                        (count >= 4 && magic.take(4).toByteArray().contentEquals(byteArrayOf(100, 101, 120, 10))))
                }
            }
        }
        inspect("")
    }

    @Test fun nativeDeviceIdentityUsesCompiledSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = NeteaseOfficialDeviceId.create(context, "0123456789abcdef")
        val second = NeteaseOfficialDeviceId.create(context, "0123456789abcdef")
        assertEquals(first.encoded, second.encoded)
        assertEquals(16, first.localIdLength)
        val nativeId = com.netease.`is`.deviceid.NEDeviceID
            .getLocalID(OfficialNeteaseSecurityContext(context)).orEmpty().trim()
        android.util.Log.i("NeteaseSourceAuthTest", "Native localIdLength=${nativeId.length} fallback=${first.usedLocalIdFallback}")
        // The original native API may return no hardware identity on an emulator.
        assertEquals(nativeId.length < 16, first.usedLocalIdFallback)
    }

    @Test fun ursWireEncryptionInitializesARealInstallation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("netease_urs_auth", android.content.Context.MODE_PRIVATE)
        val original = preferences.getString("appId", null)
        try {
            // Force a real encrypted request even when a previous test cached an installation.
            preferences.edit().remove("appId").commit()
            val fresh = NeteaseUrsSmsLogin(context)
            val id = fresh.appId()
            assertTrue(id.length >= 128)
            assertEquals(id, fresh.appId())
        } finally {
            preferences.edit().apply {
                if (original == null) remove("appId") else putString("appId", original)
            }.commit()
        }
    }

    @Test fun proofOfWorkMatchesSha256AndModularArithmetic() = runBlocking {
        suspend fun answer(algorithm: String, args: JSONObject) = NeteaseUrsPuzzle.solve(JSONObject()
            .put("compQues", JSONArray().put(JSONObject().put("hashFunc", algorithm)
                .put("sid", "fixture").put("minTime", 0).put("maxTime", 1000).put("args", args))))
        val target = "f".repeat(64)
        val sequential = answer("SEQ_HASHCASH", JSONObject().put("puzzle", "fixture").put("target", target))
        val n = sequential.values.getValue("n")
        assertEquals((1L..(n as Long)).minOf { candidate -> MessageDigest.getInstance("SHA-256")
            .digest("fixture$candidate".toByteArray()).toHex() }, sequential.values["pow"])
        val recursive = answer("RECUR_HASHCASH", JSONObject().put("puzzle", "fixture").put("target", target))
        assertEquals(MessageDigest.getInstance("SHA-256")
            .digest(("fixture" + recursive.values.getValue("n1")).toByteArray()).toHex(), recursive.values["pow"])
        val vdf = answer("VDF_FUNCTION", JSONObject().put("puzzle", "fixture")
            .put("mod", "11").put("x", "3").put("t", "3"))
        assertEquals(BigInteger.valueOf(3).modPow(BigInteger.valueOf(8), BigInteger.valueOf(17)).toString(16),
            vdf.values["x"])
        assertEquals(3L, vdf.values["t"])
    }
}
