package com.ljyh.mei.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.model.melox.ShareResourceKind
import com.ljyh.mei.data.network.api.MeloXDirectService
import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

/** Real Repository/platform JSON, substitute transports only; never sends to contacts. */
class SocialRequestDeviceTest {
    private var identity = SessionIdentity(17, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val eapi = Transport()
    private val weapi = Transport()
    private val resource = ShareResource(ShareResourceKind.Song, 11, "Song", null, null)
    private val repository = MeloXRepository(
        eapi, weapi, InstrumentationRegistry.getInstrumentation().targetContext, sessions,
        CloudUploadCoordinator(eapi, weapi, sessions, object : CloudBinaryUploader {
            override suspend fun upload(file: CloudUploadFile, authorization: CloudUploadAuthorization,
                owner: SessionStamp, onProgress: (Long, Long) -> Unit) = error("Unrelated upload")
        }),
        CloudLibraryBackend(weapi, sessions, eapi),
    )

    private data class Request(val path: String, val body: Map<String, Any>, val headers: Map<String, String>, val owner: SessionStamp?)
    private class Transport : MeloXDirectService {
        val requests = mutableListOf<Request>()
        var result: suspend (Request) -> JsonObject = { json("""{"code":200,"msgs":[],"follow":[],"more":false}""") }
        override suspend fun post(path: String, body: Map<String, Any>, headers: Map<String, String>, expectedSession: SessionStamp?): JsonObject {
            val request = Request(path, body, headers, expectedSession)
            requests += request
            return result(request)
        }
        override suspend fun postPlaybackRaw(path: String, body: Map<String, Any>, headers: Map<String, String>): Response<ResponseBody> =
            error("Unrelated playback report")
    }

    @Test fun conversationAndHistoryReadsCarryIdentityAndPreserveMessageMapping() = runBlocking {
        weapi.result = { request ->
            if (request.path.endsWith("users")) json("""{
                "code":200,"msgs":[{"fromUser":{"userId":17,"nickname":"Self"},
                "toUser":{"userId":9,"nickname":"Contact"},"lastMsgTime":10,
                "lastMsg":"{\"msg\":\"Text\"}","newMsgCount":2}]
            }""") else json("""{
                "code":200,"msgs":[{"id":2,"time":20,"fromUser":{"userId":9},"msg":"{\"msg\":\"Later\"}"},
                {"id":1,"time":10,"fromUser":{"userId":17},"msg":"{\"msg\":\"Earlier\"}"}]
            }""")
        }
        val owner = sessions.snapshot()
        val conversations = repository.privateConversations(owner)
        val messages = repository.privateMessages(owner, 9)
        assertEquals(17L, conversations.single().fromUser?.id)
        assertEquals(9L, conversations.single().toUser?.id)
        assertEquals("Text", conversations.single().summary)
        assertEquals(listOf(1L, 2L), messages.map { it.id })
        assertEquals("Earlier", messages.first().payload.text)
        assertEquals(mapOf("userId" to 9L, "time" to -1L, "limit" to 100, "total" to "true"), weapi.requests.last().body)
        assertTrue(weapi.requests.all { it.owner == owner && it.headers.isEmpty() })
        assertTrue(eapi.requests.isEmpty())
    }

    @Test fun contactPagesUseTheCapturedIdentityAndOneOwner() = runBlocking {
        weapi.result = { request ->
            if (request.body["offset"] == 0) json("""{"code":200,"follow":[{"userId":9},{"userId":8}],"more":true}""")
            else json("""{"code":200,"follow":[{"userId":7}],"more":false}""")
        }
        val owner = sessions.snapshot()
        val contacts = repository.messageContacts(owner, pageSize = 2)
        assertEquals(listOf(9L, 8L, 7L), contacts.map { it.id })
        assertEquals(listOf(0, 2), weapi.requests.map { it.body["offset"] })
        assertTrue(weapi.requests.all { it.path == "/api/user/getfollows/17" && it.owner == owner })
    }

    @Test fun sendsKeepExistingPayloadsButCarryAnExplicitSession() = runBlocking {
        val owner = sessions.snapshot()
        repository.sendPrivateText(owner, "Text", listOf(9, 8, 9))
        repository.sendPrivateResource(owner, resource, listOf(9), "Resource text")
        repository.shareToTimeline(owner, resource, "Timeline text")
        assertEquals(listOf("/api/msg/private/send", "/api/msg/private/send", "/api/share/friends/resource"), eapi.requests.map { it.path })
        assertEquals(mapOf("type" to "text", "msg" to "Text", "userIds" to "[8,9]"), eapi.requests[0].body)
        assertEquals(mapOf("id" to 11L, "type" to "song", "msg" to "Resource text", "userIds" to "[9]"), eapi.requests[1].body)
        assertEquals(mapOf("id" to 11L, "type" to "song", "msg" to "Timeline text"), eapi.requests[2].body)
        assertTrue(eapi.requests.all { it.owner == owner && it.headers.isEmpty() })
        assertTrue(weapi.requests.isEmpty())
    }

    @Test fun staleOwnerNeverDispatchesAReadOrWrite() = runBlocking {
        val old = sessions.snapshot()
        sessions.invalidate()
        assertTrue(runCatching { repository.privateConversations(old) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { repository.sendPrivateText(old, "Text", listOf(9)) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { repository.sendPrivateResource(old, resource, listOf(9)) }.exceptionOrNull() is SessionChangedException)
        assertTrue(runCatching { repository.shareToTimeline(old, resource) }.exceptionOrNull() is SessionChangedException)
        assertTrue(weapi.requests.isEmpty())
        assertTrue(eapi.requests.isEmpty())
    }

    @Test fun accountChangeDuringAContactPageRejectsTheResultAndNextPage() = runBlocking {
        weapi.result = {
            identity = SessionIdentity(18, true, false)
            sessions.invalidate()
            json("""{"code":200,"follow":[{"userId":9}],"more":true}""")
        }
        val old = sessions.snapshot()
        assertTrue(runCatching { repository.messageContacts(old) }.exceptionOrNull() is SessionChangedException)
        assertEquals(1, weapi.requests.size)
        assertEquals("/api/user/getfollows/17", weapi.requests.single().path)
    }

    @Test fun lateSendAcknowledgementDoesNotBecomeANewAccountsSuccess() = runBlocking {
        eapi.result = {
            identity = SessionIdentity(18, true, false)
            sessions.invalidate()
            json("""{"code":200}""")
        }
        assertTrue(runCatching { repository.sendPrivateText(sessions.snapshot(), "Text", listOf(9)) }
            .exceptionOrNull() is SessionChangedException)
        assertEquals(1, eapi.requests.size)
        assertEquals(17L, eapi.requests.single().owner?.identity?.userId)
    }

    @Test fun guestsAndRecoveryCannotReachTheTransport() = runBlocking {
        identity = SessionIdentity(0, false, true)
        val guest = sessions.snapshot()
        assertTrue(runCatching { repository.privateMessages(guest, 9) }.isFailure)
        assertTrue(runCatching { repository.messageContacts(guest) }.isFailure)
        assertTrue(runCatching { repository.sendPrivateText(guest, "Text", listOf(9)) }.isFailure)
        identity = SessionIdentity(17, true, false)
        sessions.setRecoveryRequired(true)
        assertTrue(runCatching { repository.privateConversations(sessions.snapshot()) }.isFailure)
        assertTrue(runCatching { repository.shareToTimeline(sessions.snapshot(), resource) }.isFailure)
        assertTrue(eapi.requests.isEmpty())
        assertTrue(weapi.requests.isEmpty())
    }

    @Test fun businessFailureRemainsFailure() = runBlocking {
        eapi.result = { json("""{"code":403,"message":"Denied fixture"}""") }
        val failure = runCatching { repository.sendPrivateResource(sessions.snapshot(), resource, listOf(9)) }.exceptionOrNull()
        assertEquals("Denied fixture", failure?.message)
    }

    @Test fun canceledRequestRejectsANonCancellableLateTransportResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JsonObject>()
        weapi.result = { started.complete(Unit); withContext(NonCancellable) { response.await() } }
        val request = async { repository.privateConversations(sessions.snapshot()) }
        started.await()
        request.cancel()
        response.complete(json("""{"code":200,"msgs":[]}"""))
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, weapi.requests.size)
    }

    companion object {
        private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    }
}
