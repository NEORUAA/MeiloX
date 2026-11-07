package com.ljyh.mei.data.network

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.NeteaseCsrfKey
import com.ljyh.mei.constants.NeteaseMusicAKey
import com.ljyh.mei.constants.NeteaseSessionTypeKey
import com.ljyh.mei.constants.NeteaseUrsAppIdKey
import com.ljyh.mei.constants.NeteaseWebSessionNoticeDismissedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseAccountSessionTest {
    @Test fun emptyCookieNeverEnablesMobileAuthorization() {
        val preferences = mutablePreferencesOf(
            NeteaseSessionTypeKey to "Mobile", NeteaseUrsAppIdKey to "urs-id",
        )
        assertEquals(NeteaseSessionType.None, preferences.neteaseSessionType())
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun recognizesMobileSessionsFromBeforeTheMerge() {
        val preferences = mutablePreferencesOf(CookieKey to "session", NeteaseUrsAppIdKey to "urs-id")
        assertEquals(NeteaseSessionType.Mobile, preferences.neteaseSessionType())
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun musicAOrCsrfAloneDoesNotProveMobileAuthentication() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseMusicAKey to "anonymous", NeteaseCsrfKey to "csrf",
        )
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
        assertTrue(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun explicitWebSourceWinsOverStaleMobileFields() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseSessionTypeKey to "Web", NeteaseUrsAppIdKey to "stale-urs-id",
        )
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun explicitMobileSourceDoesNotRequireOptionalRefreshToken() {
        val preferences = mutablePreferencesOf(CookieKey to "session", NeteaseSessionTypeKey to "Mobile")
        assertFalse(preferences.shouldExplainLegacyWebSession())
    }

    @Test fun unknownSourceFailsClosed() {
        val preferences = mutablePreferencesOf(
            CookieKey to "session", NeteaseSessionTypeKey to "unknown", NeteaseUrsAppIdKey to "urs-id",
        )
        assertEquals(NeteaseSessionType.Web, preferences.neteaseSessionType())
    }

    @Test fun reminderIsOnlyForUpgradedWebSessions() {
        val preferences = mutablePreferencesOf(CookieKey to "web-session")
        assertTrue(preferences.shouldExplainLegacyWebSession())
        preferences[NeteaseWebSessionNoticeDismissedKey] = true
        assertFalse(preferences.shouldExplainLegacyWebSession())
        assertEquals("web-session", preferences[CookieKey])
    }

}
