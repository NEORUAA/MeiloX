package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.parasite.HostAccountState
import com.ljyh.mei.parasite.HostSessionIdentity
import com.ljyh.mei.parasite.HostSessionStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHomeStateTest {
    private val first = HostSessionStamp(1, HostSessionIdentity(1, true, false))
    private val profile = AccountProfile(1, "First", null, null, null, null, null, null, null, null)
    private val loaded = AccountHomeState(profile = profile, loading = false, session = first)

    @Test fun currentSessionKeepsTheLoadedPage() {
        assertSame(loaded, loaded.forAccount(HostAccountState(first, profile, loading = false)))
    }

    @Test fun differentAccountOrGenerationHidesThePreviousPageImmediately() {
        listOf(first.copy(generation = 2), first.copy(identity = HostSessionIdentity(2, true, false))).forEach { next ->
            val visible = loaded.forAccount(HostAccountState(next))
            assertNull(visible.profile)
            assertNull(visible.detail)
            assertTrue(visible.playlists.isEmpty())
            assertEquals(next, visible.session)
        }
    }

    @Test fun transitionAndLogoutNeverKeepThePreviousAccount() {
        assertNull(loaded.forAccount(HostAccountState()).profile)
        val guest = HostSessionStamp(2, HostSessionIdentity(0, false, true))
        val visible = loaded.forAccount(HostAccountState(guest, loading = false))
        assertNull(visible.profile)
        assertTrue(visible.requiresLogin)
    }
}
