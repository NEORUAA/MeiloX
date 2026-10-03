package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherRoomCheckTest {
    private fun check(json: String) = parseListenTogetherRoomCheck(JsonParser.parseString(json).asJsonObject)

    @Test fun availableRoomStillPreservesExplicitVersionRejection() {
        assertEquals(false to "The other participant must upgrade", check("""{
            "status":"AVAILABLE","joinable":false,"type":"HIGH_V_REJECTED",
            "copywriting":"The other participant must upgrade"
        }"""))
    }

    @Test fun availabilityWithoutJoinabilityDoesNotAuthorizeJoining() {
        assertEquals(false to "AVAILABLE", check("""{"status":"AVAILABLE"}"""))
    }

    @Test fun missingDataDoesNotAuthorizeJoining() {
        assertEquals(false to null, parseListenTogetherRoomCheck(null))
    }

    @Test fun rejectedRoomWithoutCopywritingKeepsTheStatusFallback() {
        assertEquals(false to "EXPIRED", check("""{"status":"EXPIRED","joinable":false}"""))
    }

    @Test fun blankCopywritingKeepsTheStatusFallback() {
        assertEquals(false to "EXPIRED", check("""{"status":"EXPIRED","joinable":false,"copywriting":"   "}"""))
    }

    @Test fun nullCopywritingKeepsTheStatusFallback() {
        assertEquals(false to "EXPIRED", check("""{"status":"EXPIRED","joinable":false,"copywriting":null}"""))
    }

    @Test fun malformedCopywritingKeepsTheStatusFallback() {
        assertEquals(false to "EXPIRED", check("""{"status":"EXPIRED","joinable":false,"copywriting":{}}"""))
    }

    @Test fun acceptedRoomKeepsTheExistingStatusContract() {
        assertEquals(true to "AVAILABLE", check("""{
            "status":"AVAILABLE","joinable":true,"copywriting":"Unused rejection text"
        }"""))
    }

    @Test fun rejectionExplanationDoesNotReplaceTheJoinabilityFlag() {
        assertEquals(false to "Invite another participant", check("""{"copywriting":"Invite another participant"}"""))
    }
}
