package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.session.AccountState
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHomeStateTest {
    private val first = SessionStamp(1, SessionIdentity(1, true, false))
    private val profile = AccountProfile(1, "First", null, null, null, null, null, null, null, null)
    private val loaded = AccountHomeState(profile = profile, loading = false, session = first)

    @Test fun currentSessionKeepsTheLoadedPage() {
        assertSame(loaded, loaded.forAccount(AccountState(first, profile, loading = false)))
    }

    @Test fun differentAccountOrGenerationHidesThePreviousPageImmediately() {
        listOf(first.copy(generation = 2), first.copy(identity = SessionIdentity(2, true, false))).forEach { next ->
            val visible = loaded.forAccount(AccountState(next))
            assertNull(visible.profile)
            assertNull(visible.detail)
            assertTrue(visible.playlists.isEmpty())
            assertEquals(next, visible.session)
        }
    }

    @Test fun transitionAndLogoutNeverKeepThePreviousAccount() {
        assertNull(loaded.forAccount(AccountState()).profile)
        val guest = SessionStamp(2, SessionIdentity(0, false, true))
        val visible = loaded.forAccount(AccountState(guest, loading = false))
        assertNull(visible.profile)
        assertTrue(visible.requiresLogin)
    }

    @Test fun failedAuthorizationRecoveryKeepsTheSignInRouteAvailable() {
        val visible = loaded.forAccount(AccountState(loading = false, recoveryRequired = true))
        assertNull(visible.profile)
        assertTrue(visible.requiresLogin)
        assertEquals(false, visible.loading)
    }
}
