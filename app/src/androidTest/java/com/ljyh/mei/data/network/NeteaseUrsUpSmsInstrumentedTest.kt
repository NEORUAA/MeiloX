package com.ljyh.mei.data.network

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Every HTTP request terminates locally. No SMS or account request reaches NetEase. */
@RunWith(AndroidJUnit4::class)
class NeteaseUrsUpSmsInstrumentedTest {
    private val appId = "i".repeat(192)
    private val phone = "13000000000"
    private val destination = "1069000000"
    private val content = "fixture-up-sms-challenge"

    @Test fun normalSmsRemainsTheDownstreamFlow() = mocked(200, JSONObject()) { urs, requests, payloads ->
        requestSms(urs)
        assertEquals("/uns/sdk/login/mob/sms/v1/send", requests.single().url.encodedPath)
        assertEquals(phone, payloads.single().getString("username"))
    }

    @Test fun requiredOutgoingSmsPreservesServerInstructionsAndIssuingInstallation() =
        mocked(12020, upData()) { urs, _, _ ->
            val challenge = requestChallenge(urs)
            assertEquals(phone, challenge.phone)
            assertEquals("86", challenge.countryCode)
            assertEquals(destination, challenge.destination)
            assertEquals(content, challenge.content)
            assertEquals(appId, challenge.appId)
        }

    @Test fun successfulEnvelopeWithUpSmsFlagStillRequiresOutgoingVerification() =
        mocked(200, upData().put("needUpMessage", true)) { urs, _, _ -> requestChallenge(urs) }

    @Test fun incompleteInstructionsCannotBePresentedAsAnSmsThatWasSent() =
        mocked(12020, JSONObject().put("upCode", destination)) { urs, _, _ ->
            try { requestSms(urs); fail("Missing SMS content was accepted") }
            catch (error: NeteaseUrsException) { assertEquals(12020, error.apiCode) }
        }

    @Test fun outgoingSmsUsesCheckRouteAndEncryptedUpMsgAndKeepsTokenAppIdPair() =
        mocked(200, JSONObject().put("token", "fixture-login-token")) { urs, requests, payloads ->
            val credentials = urs.verifyUpSms(challenge())
            assertEquals("/uns/sdk/login/mob/sms/v1/check", requests.single().url.encodedPath)
            assertEquals(phone, payloads.single().getString("username"))
            assertEquals(content, payloads.single().getString("upMsg"))
            assertFalse(payloads.single().has("smsCode"))
            val body = okio.Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
            assertEquals(appId, JSONObject(body).getString("utid"))
            assertEquals("fixture-login-token", credentials.token)
            assertEquals(appId, credentials.appId)
        }

    @Test fun outgoingSmsCannotBeCheckedUsingAnotherInstallation() =
        mocked(200, JSONObject().put("token", "fixture-login-token")) { urs, requests, _ ->
            try { urs.verifyUpSms(challenge("different-installation")); fail("Expired challenge accepted") }
            catch (_: NeteaseUrsException) { assertTrue(requests.isEmpty()) }
        }

    @Test fun rejectedInstallationDoesNotReplayTheUpMsgAfterAutomaticReinitialization() =
        mocked(12025, JSONObject()) { urs, requests, _ ->
            try { urs.verifyUpSms(challenge()); fail("Rejected installation accepted") }
            catch (error: NeteaseUrsException) {
                assertEquals(12025, error.apiCode)
                assertEquals(1, requests.size)
                assertEquals(appId, urs.appId())
            }
        }

    @Test fun unreceivedOutgoingSmsDoesNotReturnCredentials() =
        mocked(12018, JSONObject()) { urs, _, _ ->
            try { urs.verifyUpSms(challenge()); fail("Unverified SMS accepted") }
            catch (error: NeteaseUrsException) { assertEquals(12018, error.apiCode) }
        }

    private fun upData() = JSONObject().put("upCode", destination).put("upMessage", content)
    private fun challenge(id: String = appId) = NeteaseUrsUpSmsChallenge(phone, "86", destination, content, id)
    private suspend fun requestSms(urs: NeteaseUrsSmsLogin) = withContext(Dispatchers.Main) {
        urs.requestCode(Activity(), phone, "86")
    }
    private suspend fun requestChallenge(urs: NeteaseUrsSmsLogin): NeteaseUrsUpSmsChallenge {
        try { requestSms(urs); fail("Outgoing SMS step was skipped") }
        catch (error: NeteaseUrsUpSmsRequiredException) { return error.challenge }
        error("Unreachable")
    }

    private fun mocked(code: Int, data: JSONObject,
        test: suspend (NeteaseUrsSmsLogin, List<Request>, List<JSONObject>) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("netease_urs_auth", Context.MODE_PRIVATE)
        val saved = listOf("appId", "installationId").associateWith { preferences.getString(it, null) }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val requests = CopyOnWriteArrayList<Request>()
        val payloads = CopyOnWriteArrayList<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            // Independently decrypt the actual p1/p2 envelope to inspect wire fields.
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsa.init(Cipher.DECRYPT_MODE, pair.private)
            val keyJson = JSONObject(rsa.doFinal(Base64.decode(request.header("p2")!!.fromHex()
                .toString(Charsets.UTF_8), Base64.NO_WRAP)).toString(Charsets.UTF_8))
            val sm4 = Cipher.getInstance("SM4/CBC/PKCS5Padding", BouncyCastleProvider())
            sm4.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyJson.getString("smkey").fromHex(), "SM4"),
                IvParameterSpec(keyJson.getString("smIv").fromHex()))
            payloads.add(JSONObject(sm4.doFinal(request.header("p1")!!.fromHex()).toString(Charsets.UTF_8)))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("result", code).put("data", data).put("enc", false)
                    .put("msg", "fixture").toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            preferences.edit().putString("appId", appId).commit()
            val urs = NeteaseUrsSmsLogin(context, client)
            urs.appId()
            val lazy = NeteaseUrsSmsLogin::class.java.getDeclaredField("crypto\$delegate")
                .apply { isAccessible = true }.get(urs) as Lazy<*>
            NeteaseUrsCrypto::class.java.getDeclaredField("serverKey").apply { isAccessible = true }
                .set(lazy.value, pair.public)
            test(urs, requests, payloads)
        } finally {
            preferences.edit().apply {
                saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }.commit()
        }
    }
}
