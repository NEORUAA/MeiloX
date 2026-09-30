package com.ljyh.mei.data.model

import com.ljyh.mei.data.session.SessionChangedException
import com.ljyh.mei.data.session.SessionIdentity
import org.junit.Assert.*
import org.junit.Test

class SongSourceIdentityTest {
    private val cloud = SongSourceIdentity(999, 88, 7, 17)

    @Test fun catalogPayloadAndCloudTupleHaveDifferentContracts() {
        assertEquals("999", SongSourceIdentity(999).key)
        assertEquals("999_0", SongSourceIdentity(999).downloadId)
        assertEquals("[999]", SongSourceIdentity.playerIds(listOf(SongSourceIdentity(999))))
        assertEquals("[999,\"999_88\"]", SongSourceIdentity.playerIds(listOf(SongSourceIdentity(999), cloud)))
        assertEquals("meilox-cloud-v1:17:999:88:7", cloud.key)
        assertEquals(cloud, SongSourceIdentity.fromKey(cloud.key))
        assertEquals("999_88", cloud.downloadId)
    }

    @Test fun sourceOwnerAndLibraryAccountCannotBeInterchanged() {
        cloud.requireAccount(SessionIdentity(7, true, false))
        for (account in listOf(SessionIdentity(88, true, false), SessionIdentity(7, false, false),
            SessionIdentity(7, true, true), SessionIdentity(0, false, true))) {
            assertThrows(SessionChangedException::class.java) { cloud.requireAccount(account) }
        }
        assertNotEquals(cloud.key, cloud.copy(cloudOwnerId = 89).key)
        assertNotEquals(cloud.key, cloud.copy(accountId = 8).key)
        assertNotEquals(cloud.key, cloud.copy(entryId = 18).key)
        assertNotEquals(cloud.key, cloud.copy(songId = 998).key)
    }

    @Test fun malformedKeysNeverFallBackToOrdinarySongs() {
        for (key in listOf("", "0", "-1", "999_88", "[999]", "meilox-cloud-v1:17:999:88",
            "meilox-cloud-v1:17:999:88:7:8", "meilox-cloud-v1:17:999:0:7",
            "meilox-cloud-v1:17:999:88:-1", "meilox-cloud-v1:017:999:88:7",
            "meilox-cloud-v1:17:999:88:9223372036854775808")) {
            assertThrows(key, IllegalArgumentException::class.java) { SongSourceIdentity.fromKey(key) }
        }
    }

    @Test fun invalidTypedIdentitiesCannotBeSerializedOrAuthorized() {
        for (source in listOf(SongSourceIdentity(0), cloud.copy(cloudOwnerId = 0), cloud.copy(accountId = 0),
            cloud.copy(songId = -1), SongSourceIdentity(999, entryId = 17))) {
            assertThrows(IllegalArgumentException::class.java) { source.key }
            assertThrows(IllegalArgumentException::class.java) { source.downloadId }
            assertThrows(IllegalArgumentException::class.java) { source.requireAccount(SessionIdentity(7, true, false)) }
            assertThrows(IllegalArgumentException::class.java) { SongSourceIdentity.playerIds(listOf(source)) }
        }
    }
}
