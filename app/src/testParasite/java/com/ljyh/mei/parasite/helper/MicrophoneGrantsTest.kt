package com.ljyh.mei.parasite.helper

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MicrophoneGrantsTest {
    private var time = 100L
    private val grants = MicrophoneGrants { time }
    private val token = UUID.randomUUID().toString()

    @Test fun authorizationIsConsumedOnlyOnce() {
        grants.authorize(token, 10312)
        assertEquals(10312, grants.consume(token))
        assertNull(grants.consume(token))
    }

    @Test fun expiredAndUnknownGrantsFailClosed() {
        assertNull(grants.consume(token))
        grants.authorize(token, 10312)
        time += 20_000
        assertNull(grants.consume(token))
    }

    @Test fun replacementAuthorizationCannotReviveAnExpiredDifferentToken() {
        grants.authorize(token, 10312)
        time += 20_001
        val next = UUID.randomUUID().toString()
        grants.authorize(next, 10312)
        assertNull(grants.consume(token))
        assertEquals(10312, grants.consume(next))
    }

    @Test fun malformedTokensAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { grants.authorize("invalid", 10312) }
    }
}
