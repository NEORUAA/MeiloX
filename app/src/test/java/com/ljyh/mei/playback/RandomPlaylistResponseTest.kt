package com.ljyh.mei.playback

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ljyh.mei.data.model.api.GetRandomPlaylist
import com.ljyh.mei.data.model.api.RandomPlaylistPrivilege
import com.ljyh.mei.data.model.api.RandomPlaylistResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomPlaylistResponseTest {
    private val gson = Gson()

    @Test
    fun requestUsesOfficialFieldNamesAndKeepsLargeIds() {
        val json = JsonParser.parseString(gson.toJson(GetRandomPlaylist(9007199254740993L, 42L))).asJsonObject
        assertEquals(setOf("playlistId", "songId", "alg"), json.keySet())
        assertEquals(9007199254740993L, json["playlistId"].asLong)
        assertEquals(42L, json["songId"].asLong)
        assertEquals("", json["alg"].asString)
    }

    @Test
    fun serverSongOrderAndAlgorithmsAreNotReorderedByParsing() {
        val response = decode("""{"code":200,"data":{"songIds":[{"id":30,"alg":"a"},{"id":10},{"id":20}]}}""")
        val data = requireNotNull(response.data)
        val songs = requireNotNull(data.songIds)
        assertEquals(listOf(30L, 10L, 20L), songs.map { it.id })
        assertEquals("a", songs.first().alg)
        assertNull(data.songData)
        assertNull(data.privileges)
    }

    @Test
    fun songDetailsAndPrivilegesDecodeAlongsideOrder() {
        val response = decode("""{"code":200,"data":{"songIds":[{"id":30}],
            "songData":[{"id":30,"name":"Track","dt":123000,"al":{"id":1,"name":"Album"},"ar":[]}],
            "privileges":[{"id":30,"st":0,"pl":320000}]}}""")
        val data = requireNotNull(response.data)
        val track = requireNotNull(data.songData).single()
        assertEquals("Track", track.name)
        assertEquals(123000L, track.dt)
        assertFalse(requireNotNull(data.privileges).single().isUnavailable)
    }

    @Test
    fun missingNullAndEmptyDataRemainDistinguishableFromUsableOrder() {
        assertNull(decode("""{"code":500}""").data)
        assertNull(decode("""{"code":200,"data":null}""").data)
        assertNull(decode("""{"code":200,"data":{}}""").data!!.songIds)
        assertTrue(decode("""{"code":200,"data":{"songIds":[]}}""").data!!.songIds!!.isEmpty())
    }

    @Test
    fun absentPrivilegeFieldsDoNotInventAnUnavailableVerdict() {
        assertFalse(RandomPlaylistPrivilege(id = 1).isUnavailable)
        val privilege = decode("""{"data":{"privileges":[{"id":1}]}}""").data!!.privileges!!.single()
        assertNull(privilege.pl)
        assertNull(privilege.st)
        assertFalse(privilege.isUnavailable)
    }

    @Test
    fun negativeStatusAndExplicitNoPlaybackAreUnavailable() {
        assertTrue(RandomPlaylistPrivilege(st = -200, pl = 320000).isUnavailable)
        listOf(null, "", "none").forEach { level ->
            assertTrue(RandomPlaylistPrivilege(st = 0, pl = 0, playMaxBrLevel = level).isUnavailable)
        }
    }

    @Test
    fun validPlaybackLevelOrBitrateRemainEligible() {
        assertFalse(RandomPlaylistPrivilege(pl = 0, playMaxBrLevel = "standard").isUnavailable)
        assertFalse(RandomPlaylistPrivilege(pl = 128000, st = 0).isUnavailable)
    }

    private fun decode(json: String) = gson.fromJson(json, RandomPlaylistResponse::class.java)
}
