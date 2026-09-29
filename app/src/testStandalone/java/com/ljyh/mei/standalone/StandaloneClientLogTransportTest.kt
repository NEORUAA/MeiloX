package com.ljyh.mei.standalone

import com.ljyh.mei.data.network.netease.NCBL_UPLOAD_ENDPOINT
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionStamp
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class StandaloneClientLogTransportTest {
    @Test fun onlyTheCurrentAuthenticatedOwnerCanConstructAClientLogCall() = runTest {
        val sessions = store()
        val calls = StandaloneTransport(sessions).clientLogs
        assertThrows(SessionChangedException::class.java) { calls.newCall(request(sessions.snapshot())) }
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("fixture-cookie", 7))
        val owner = sessions.snapshot()
        val call = calls.newCall(request(owner))
        assertEquals(owner, call.request().tag(SessionStamp::class.java))
        assertFalse(call.isExecuted())
        sessions.invalidate()
        assertThrows(SessionChangedException::class.java) { call.clone() }
        assertThrows(SessionChangedException::class.java) { calls.newCall(request(owner)) }
    }

    @Test fun rejectsWrongCredentialOriginMethodAndRecoveryWithoutSendingAnything() = runTest {
        val sessions = store()
        sessions.commitLogin(sessions.beginLogin(), StoredAccount("fixture-cookie", 7))
        val owner = sessions.snapshot()
        val calls = StandaloneTransport(sessions).clientLogs
        val original = request(owner)
        for (request in listOf(
            original.newBuilder().header("X-Music-U", "other-cookie").build(),
            original.newBuilder().url("https://example.com/api/clientlog/encrypt/upload?multiupload=true").build(),
            original.newBuilder().url(NCBL_UPLOAD_ENDPOINT + "&extra=true").build(),
            original.newBuilder().get().build(),
        )) assertThrows(IllegalArgumentException::class.java) { calls.newCall(request) }
        sessions.setRecoveryRequired(true)
        assertThrows(SessionChangedException::class.java) { calls.newCall(original) }
    }

    private suspend fun store() = StandaloneSessionStore(object : StandaloneAccountPersistence {
        override suspend fun read() = StoredAccount("", 0)
        override suspend fun write(account: StoredAccount) = Unit
    }).also { it.initialize() }

    private fun request(owner: SessionStamp) = Request.Builder().url(NCBL_UPLOAD_ENDPOINT)
        .tag(SessionStamp::class.java, owner).header("X-Music-U", "fixture-cookie")
        .post("fixture".toRequestBody()).build()
}
