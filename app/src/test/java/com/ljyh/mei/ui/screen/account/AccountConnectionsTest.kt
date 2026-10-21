package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.data.model.melox.AccountConnectionPage
import com.ljyh.mei.data.model.melox.AccountConnectionType
import com.ljyh.mei.data.model.melox.AccountProfile
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountConnectionsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pagesDeduplicateUsersAndContinueFromRawOffsetsUntilProgressStops() = runTest(dispatcher) {
        val offsets = mutableListOf<Int>()
        val viewModel = AccountConnectionsViewModel { userId, type, offset ->
            assertEquals(42L, userId)
            assertEquals(AccountConnectionType.Following, type)
            offsets += offset
            when (offset) {
                0 -> page(listOf(1, 1), more = true, nextOffset = 4)
                4 -> page(listOf(1, 2), more = true, nextOffset = 7)
                else -> page(emptyList(), more = true, nextOffset = 7)
            }
        }
        viewModel.load(42, AccountConnectionType.Following)
        runCurrent()
        viewModel.loadMore()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(1L, 2L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertEquals(7, viewModel.state.value.page!!.nextOffset)

        viewModel.loadMore()
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0, 4, 7), offsets)
        assertFalse(viewModel.state.value.page!!.hasMore)
        assertFalse(viewModel.state.value.loadingMore)
    }

    @Test
    fun refreshedFirstPageRejectsAnOlderPaginationResult() = runTest(dispatcher) {
        lateinit var append: Continuation<AccountConnectionPage>
        var refreshed = false
        val viewModel = AccountConnectionsViewModel { _, _, offset ->
            if (offset > 0) suspendCoroutine { append = it }
            else page(listOf(if (refreshed) 3 else 1), more = true, nextOffset = 4)
        }
        viewModel.load(42, AccountConnectionType.Following)
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        refreshed = true
        viewModel.refresh()
        runCurrent()
        append.resume(page(listOf(2), more = false, nextOffset = 8))
        runCurrent()

        assertEquals(listOf(3L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertEquals(4, viewModel.state.value.page!!.nextOffset)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.loadingMore)
        assertNull(viewModel.state.value.loadMoreError)
    }

    @Test
    fun failuresPreserveExistingUsersAndAllowRetryAtTheSameOffset() = runTest(dispatcher) {
        val offsets = mutableListOf<Int>()
        var fail = false
        val viewModel = AccountConnectionsViewModel { _, _, offset ->
            offsets += offset
            if (fail) error("Page unavailable")
            page(listOf(if (offset == 0) 1 else 2), more = offset == 0, nextOffset = offset + 4)
        }
        viewModel.load(42, AccountConnectionType.Following)
        runCurrent()
        fail = true
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(1L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertEquals(4, viewModel.state.value.page!!.nextOffset)
        assertNotNull(viewModel.state.value.loadMoreError)

        fail = false
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0, 4, 4), offsets)
        assertEquals(listOf(1L, 2L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertNull(viewModel.state.value.loadMoreError)

        fail = true
        viewModel.refresh()
        runCurrent()
        assertEquals(listOf(1L, 2L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertNotNull(viewModel.state.value.error)
        fail = false
        viewModel.refresh()
        runCurrent()
        assertEquals(listOf(1L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun switchingRelationshipTypeClearsRowsAndIgnoresThePreviousSelection() = runTest(dispatcher) {
        lateinit var oldAppend: Continuation<AccountConnectionPage>
        val requests = mutableListOf<Pair<AccountConnectionType, Int>>()
        val viewModel = AccountConnectionsViewModel { _, type, offset ->
            requests += type to offset
            when {
                type == AccountConnectionType.Followers -> page(listOf(3), more = false, nextOffset = 1)
                offset == 0 -> page(listOf(1), more = true, nextOffset = 4)
                else -> suspendCoroutine { oldAppend = it }
            }
        }
        viewModel.load(42, AccountConnectionType.Following)
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        viewModel.load(42, AccountConnectionType.Followers)
        assertNull(viewModel.state.value.page)
        assertTrue(viewModel.state.value.loading)
        runCurrent()
        oldAppend.resume(page(listOf(2), more = false, nextOffset = 8))
        runCurrent()
        viewModel.load(42, AccountConnectionType.Followers)
        runCurrent()

        assertEquals(listOf(3L), viewModel.state.value.page!!.users.map(AccountProfile::id))
        assertEquals(1, viewModel.state.value.page!!.nextOffset)
        assertEquals(listOf(
            AccountConnectionType.Following to 0,
            AccountConnectionType.Following to 4,
            AccountConnectionType.Followers to 0,
        ), requests)
        assertFalse(viewModel.state.value.loadingMore)
        assertNull(viewModel.state.value.error)
    }

    private fun page(ids: List<Int>, more: Boolean, nextOffset: Int) = AccountConnectionPage(
        users = ids.map { id -> AccountProfile(
            id = id.toLong(), nickname = "User $id", avatarUrl = null, backgroundUrl = null,
            signature = null, follows = null, followers = null, eventCount = null,
            playlistCount = null, playlistSubscribedCount = null,
        ) },
        hasMore = more,
        nextOffset = nextOffset,
    )
}
