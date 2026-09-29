package com.ljyh.mei.parasite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostIdentityTest {
    @Test
    fun acceptsOnlyThePinnedHost() {
        assertTrue(accepts())
        assertFalse(accepts(packageName = "com.netease.cloudmusic"))
        assertFalse(accepts(versionCode = HostIdentity.VERSION_CODE + 1))
        assertFalse(accepts(versionName = null))
        assertFalse(accepts(versionName = "1.1.81"))
        assertFalse(accepts(signers = emptyList()))
        assertFalse(accepts(signers = listOf("untrusted")))
        assertFalse(accepts(signers = listOf(HostIdentity.SIGNER_SHA256, "untrusted")))
    }

    private fun accepts(
        packageName: String = HostIdentity.PACKAGE,
        versionCode: Long = HostIdentity.VERSION_CODE,
        versionName: String? = HostIdentity.VERSION_NAME,
        signers: List<String> = listOf(HostIdentity.SIGNER_SHA256),
    ) = HostIdentity.accepts(packageName, versionCode, versionName, signers)
}
