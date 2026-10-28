package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AccountFeatureParserTest {
    @Test fun deviceParserUsesCloudMusicDeviceKeysAndMarksTheCurrentDevice() {
        val response = JsonParser.parseString("""{"code":200,"data":{"userDeviceList":[
          {"deviceKey":"example-other","deviceName":"Example desktop","lastActiveTime":1000,"ifLoginDevice":false,"os":"pc"},
          {"deviceKey":"example-current","deviceName":"Example phone","lastActiveTime":2000,"ifLoginDevice":true,"os":"android"}]}}""").asJsonObject
        val devices = parseAccountDevices(response)
        assertEquals("example-current", devices.first().key)
        assertTrue(devices.first().current)
        assertEquals(1000L, devices.last().lastActiveTime)
    }

    @Test fun authorizationParserRetainsPaginationAndOnlyPublicApplicationFields() {
        val response = JsonParser.parseString("""{"code":200,"data":{"records":[
          {"clientId":"example-app","clientName":"Example app","accessToken":"fixture-secret"}],
          "page":{"cursor":"example-cursor","more":true}}}""").asJsonObject
        val page = parseAccountAuthorizations(response)
        assertEquals("example-app", page.records.single().clientId)
        assertEquals("example-cursor", page.cursor)
        assertTrue(page.more)
        assertFalse(page.toString().contains("fixture-secret"))
    }

    @Test fun bindingModelsDoNotRetainOauthTokens() {
        val response = JsonParser.parseString("""{"code":200,"bindings":[{"type":5,"expired":false,
          "tokenJsonStr":"{\"nickname\":\"Example\",\"access_token\":\"fixture-secret\"}"}]}""").asJsonObject
        val binding = parseAccountBindings(response).single()
        assertEquals("Example", binding.displayName)
        assertTrue(binding.bound)
        assertFalse(binding.toString().contains("fixture-secret"))
    }

    @Test fun musicianStatusUsesAuthenticationInsteadOfAssumingAWebPageMeansRegistered() {
        val auth = JsonParser.parseString("""{"code":200,"data":{"musician":false,"daren":true,"dj":false,"live":false}}""").asJsonObject
        val info = JsonParser.parseString("""{"code":200,"data":{"userStatus":0,"certStatus":0,"leftProductionTimes":2}}""").asJsonObject
        val account = parseMusicianAccount(auth, info)
        assertFalse(account.musician)
        assertTrue(account.creator)
        assertEquals(2, account.remainingPublications)
    }

    @Test fun verificationResponsesAreNotTreatedAsSuccessfulActions() {
        val response = JsonParser.parseString("""{"code":401,"message":"Verification required"}""").asJsonObject
        try { validateAccountFeatureResponse(response); fail("Expected verification") }
        catch (failure: AccountFeatureException) { assertEquals(401, failure.code) }
    }
}
