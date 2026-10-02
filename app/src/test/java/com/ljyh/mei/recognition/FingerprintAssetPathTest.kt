package com.ljyh.mei.recognition

import org.junit.Assert.*
import org.junit.Test

class FingerprintAssetPathTest {
    @Test fun onlyTheThreeBundledFingerprintAssetsAreServed() {
        for (file in listOf("index.html", "afp.js", "afp.wasm.js")) {
            assertEquals("audio_fingerprint/$file", fingerprintAssetPath("https", "meilox.invalid", "/audio_fingerprint/$file"))
        }
    }

    @Test fun otherOriginsAndTraversalCannotReadModuleAssets() {
        assertNull(fingerprintAssetPath("http", "meilox.invalid", "/audio_fingerprint/index.html"))
        assertNull(fingerprintAssetPath("https", "music.163.com", "/audio_fingerprint/index.html"))
        for (path in listOf("/audio_fingerprint/../secret", "/audio_fingerprint/%2e%2e/secret", "/secret", "/audio_fingerprint/unknown.js")) {
            assertNull(fingerprintAssetPath("https", "meilox.invalid", path))
        }
        assertNull(fingerprintAssetPath(null, null, null))
    }
}
