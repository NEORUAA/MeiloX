package com.ljyh.mei.data.repository

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesTest {
    @Test
    fun sharedMessagesPreserveOpaqueIdentifiersAndOwnedResources() {
        val source = "plugin.messages"
        val message = com.ljyh.mei.source.shared.parseSourceMessage(source, JsonParser.parseString("""
            {"id":"9223372036854775806/message","fromUser":{"id":"user/alpha","nickname":"Sender"},
             "toUser":{"id":"user/beta","nickname":"Recipient"},"time":1700000000000,
             "payload":{"text":"Hello","resource":{"kind":"Song","id":"track/string/id","title":"Shared song"}}}
        """.trimIndent()))
        assertEquals("9223372036854775806/message", message.id)
        assertEquals("user/alpha", message.fromUser?.id)
        assertEquals(source, message.fromUser?.providerId)
        assertEquals(com.ljyh.mei.source.api.TrackRef(source, "track/string/id"), message.payload.resource?.ref)
        assertEquals("Hello", message.payload.summary)
    }

    @Test
    fun sharedRoomPlaybackKeepsForeignLookingIdentifiersWithinItsOwner() {
        val snapshot = com.ljyh.mei.source.shared.parseTogetherPlayback("plugin.room", JsonParser.parseString("""
            {"songIds":["42","track/string/id","42"],"playMode":"LIST",
             "command":{"commandType":"GOTO","targetSongId":"track/string/id","formerSongId":null,
                        "progressMs":1234,"isPlaying":false,"clientSequence":2,"serverSequence":3}}
        """.trimIndent()))
        assertEquals(listOf("42", "track/string/id", "42"), snapshot.tracks.map { it.trackId })
        assertTrue(snapshot.tracks.all { it.providerId == "plugin.room" })
        assertEquals(com.ljyh.mei.source.api.TrackRef("plugin.room", "track/string/id"), snapshot.command?.target)
        assertEquals(false, snapshot.command?.isPlaying)
        assertEquals(1234L, snapshot.command?.progressMs)
    }

}
