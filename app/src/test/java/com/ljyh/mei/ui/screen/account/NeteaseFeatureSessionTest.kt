package com.ljyh.mei.ui.screen.account

import com.google.gson.JsonParser
import com.ljyh.mei.source.api.ProviderInstanceId
import com.ljyh.mei.source.shared.OnlineSessionKind
import com.ljyh.mei.source.shared.SourceOnlineParser
import org.junit.Assert.*
import org.junit.Test

class NeteaseFeatureSessionTest {
    @Test fun sessionStatusReadsOnlyTheNamedProviderResult() {
        val parser = SourceOnlineParser("fixture-source", ProviderInstanceId("installed:fixture"))
        val web = parser.session(JsonParser.parseString("""{"authenticated":true,"kind":"web"}"""))
        assertEquals("fixture-source", web.providerId)
        assertTrue(web.authenticated)
        assertEquals(OnlineSessionKind.Web, web.kind)
        assertEquals(OnlineSessionKind.Mobile, parser.session(JsonParser.parseString("""{"authenticated":true,"kind":"mobile"}""")).kind)
    }

    @Test fun signedOutAndOtherSessionKindsRemainDistinct() {
        val parser = SourceOnlineParser("fixture-source", ProviderInstanceId("installed:fixture"))
        val signedOut = parser.session(JsonParser.parseString("""{"authenticated":false,"kind":"mobile"}"""))
        assertFalse(signedOut.authenticated)
        assertEquals(OnlineSessionKind.None, signedOut.kind)
        val other = parser.session(JsonParser.parseString("""{"authenticated":true,"kind":"oauth"}"""))
        assertEquals(OnlineSessionKind.Other, other.kind)
    }
}
