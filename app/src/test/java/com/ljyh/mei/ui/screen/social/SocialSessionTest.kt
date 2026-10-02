package com.ljyh.mei.ui.screen.social

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.AccountProfile
import com.ljyh.mei.data.model.melox.MessageContact
import com.ljyh.mei.data.model.melox.PrivateConversation
import com.ljyh.mei.data.model.melox.PrivateMessage
import com.ljyh.mei.data.model.melox.PrivateMessagePayload
import com.ljyh.mei.data.model.melox.ShareResource
import com.ljyh.mei.data.model.melox.ShareResourceKind
import com.ljyh.mei.data.repository.SocialSource
import com.ljyh.mei.data.session.AccountStore
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialSessionTest {
    private var identity = SessionIdentity(1, true, false)
    private val sessions = SessionStore().apply { bind { identity } }
    private val source = Source()
    private val resource = ShareResource(ShareResourceKind.Song, 11, "Song", null, null)

    private class Source : SocialSource {
        var conversations: suspend (SessionStamp) -> List<PrivateConversation> = { listOf(conversation(it.identity.userId)) }
        var messages: suspend (SessionStamp, Long) -> List<PrivateMessage> = { owner, _ -> listOf(message(owner.identity.userId)) }
        var contacts: suspend (SessionStamp) -> List<MessageContact> = { listOf(contact(9)) }
        var write: suspend () -> Unit = {}
        val reads = mutableListOf<SessionStamp>()
        val writes = mutableListOf<Pair<SessionStamp, List<Long>>>()
        override suspend fun privateConversations(session: SessionStamp, offset: Int, limit: Int): List<PrivateConversation> {
            reads += session
            return conversations(session)
        }
        override suspend fun privateMessages(session: SessionStamp, userId: Long, before: Long, limit: Int): List<PrivateMessage> {
            reads += session
            return messages(session, userId)
        }
        override suspend fun messageContacts(session: SessionStamp, pageSize: Int, maximumCount: Int): List<MessageContact> {
            reads += session
            return contacts(session)
        }
        override suspend fun sendPrivateText(session: SessionStamp, message: String, userIds: List<Long>) {
            writes += session to userIds
            write()
        }
        override suspend fun sendPrivateResource(session: SessionStamp, resource: ShareResource, userIds: List<Long>, message: String) {
            writes += session to userIds
            write()
        }
        override suspend fun shareToTimeline(session: SessionStamp, resource: ShareResource, message: String) {
            writes += session to emptyList<Long>()
            write()
        }
    }

    private data class Models(
        val list: ConversationsViewModel,
        val chat: ConversationViewModel,
        val contacts: MessageContactsViewModel,
        val share: NeteaseShareViewModel,
        val store: ViewModelStore,
    )

    private fun checkModels(check: suspend TestScope.(Models) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = AccountStore(sessions, {
            AccountProfile(identity.userId, "Account", null, null, null, null, null, null, null, null)
        }, backgroundScope)
        val store = ViewModelStore()
        try {
            val models = Models(ConversationsViewModel(source, accounts), ConversationViewModel(source, accounts),
                MessageContactsViewModel(source, accounts), NeteaseShareViewModel(source, accounts), store)
            store.put("list", models.list)
            store.put("chat", models.chat)
            store.put("contacts", models.contacts)
            store.put("share", models.share)
            check(models)
        } finally {
            store.clear()
            accounts.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun officialIdentityOwnsReadsAndMessageDirectionWithoutCookiePreferences() = checkModels { model ->
        model.chat.load(9)
        model.share.loadContacts()
        runCurrent()
        assertEquals(sessions.snapshot(), model.list.state.value.session)
        assertEquals(9L, model.list.state.value.conversations.single().participant(1).id)
        assertEquals(1L, model.chat.state.value.messages.single().fromUser?.id)
        assertEquals(1L, model.chat.state.value.session?.identity?.userId)
        assertEquals(listOf(9L), model.contacts.state.value.contacts.map { it.id })
        assertEquals(listOf(9L), model.share.state.value.contacts.map { it.id })
        assertTrue(source.reads.all { it == sessions.snapshot() })
    }

    @Test fun guestsCannotReadPrivateDataOrSend() {
        identity = SessionIdentity(0, false, true)
        checkModels { model ->
            runCurrent()
            model.chat.load(9)
            model.share.loadContacts()
            model.chat.send(model.chat.state.value, "Text") {}
            model.share.sendPrivate(model.share.state.value, resource, setOf(9), "Text") {}
            model.share.shareTimeline(model.share.state.value, resource, "Text") {}
            runCurrent()
            assertTrue(source.reads.isEmpty())
            assertTrue(source.writes.isEmpty())
            assertEquals("Sign-in required", model.list.state.value.error)
            assertFalse(model.list.state.value.isLoading)
        }
    }

    @Test fun accountTransitionImmediatelyClearsEveryPrivateConsumer() = checkModels { model ->
        model.chat.load(9)
        model.share.loadContacts()
        runCurrent()
        sessions.beginTransition().use {
            identity = SessionIdentity(2, true, false)
            assertNull(model.list.state.value.session)
            assertTrue(model.list.state.value.conversations.isEmpty())
            assertTrue(model.chat.state.value.messages.isEmpty())
            assertTrue(model.contacts.state.value.contacts.isEmpty())
            assertTrue(model.share.state.value.contacts.isEmpty())
            assertFalse(model.share.state.value.isSending)
        }
        runCurrent()
        assertEquals(2L, model.list.state.value.session?.identity?.userId)
        assertEquals(2L, model.chat.state.value.messages.single().fromUser?.id)
        assertTrue(source.reads.takeLast(4).all { it == sessions.snapshot() })
    }

    private fun checkRetainedListNavigation(changeAccount: Boolean) = checkModels { model ->
        runCurrent()
        val conversations = model.list.state.value
        val contacts = model.contacts.state.value
        var navigations = 0
        model.list.withCurrent(conversations) { navigations++ }
        model.contacts.withCurrent(contacts) { navigations++ }
        assertEquals(2, navigations)
        sessions.beginTransition().use { if (changeAccount) identity = SessionIdentity(2, true, false) }
        runCurrent()
        model.list.withCurrent(conversations) { navigations++ }
        model.contacts.withCurrent(contacts) { navigations++ }
        assertEquals(2, navigations)
        model.list.withCurrent(model.list.state.value) { navigations++ }
        model.contacts.withCurrent(model.contacts.state.value) { navigations++ }
        assertEquals(4, navigations)
    }

    @Test fun retainedListNavigationCannotAdoptAReplacementAccount() = checkRetainedListNavigation(true)

    @Test fun retainedListNavigationCannotAdoptSameAccountReauthorization() = checkRetainedListNavigation(false)

    @Test fun recoveryImmediatelyRejectsRenderedListNavigationEvenWithoutGenerationChange() = checkModels { model ->
        runCurrent()
        val conversations = model.list.state.value
        val contacts = model.contacts.state.value
        var navigations = 0
        sessions.setRecoveryRequired(true)
        model.list.withCurrent(conversations) { navigations++ }
        model.contacts.withCurrent(contacts) { navigations++ }
        assertEquals(0, navigations)
        runCurrent()
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(conversations.session, model.list.state.value.session)
        model.list.withCurrent(conversations) { navigations++ }
        model.contacts.withCurrent(contacts) { navigations++ }
        assertEquals(0, navigations)
        model.list.withCurrent(model.list.state.value) { navigations++ }
        model.contacts.withCurrent(model.contacts.state.value) { navigations++ }
        assertEquals(2, navigations)
    }

    @Test fun refreshedListsRejectCallbacksFromThePreviousRenderedRows() = checkModels { model ->
        runCurrent()
        val conversations = model.list.state.value
        val contacts = model.contacts.state.value
        source.conversations = { emptyList() }
        source.contacts = { emptyList() }
        model.list.refresh()
        model.contacts.refresh()
        runCurrent()
        model.list.withCurrent(conversations) { fail("Removed conversation navigation") }
        model.contacts.withCurrent(contacts) { fail("Removed contact navigation") }
    }

    @Test fun latePreviousAccountListsAndContactsCannotRestoreOldData() {
        val oldList = CompletableDeferred<List<PrivateConversation>>()
        val oldContacts = CompletableDeferred<List<MessageContact>>()
        source.conversations = { if (it.identity.userId == 1L) withContext(NonCancellable) { oldList.await() } else listOf(conversation(2)) }
        source.contacts = { if (it.identity.userId == 1L) withContext(NonCancellable) { oldContacts.await() } else listOf(contact(8)) }
        checkModels { model ->
            try {
                model.share.loadContacts()
                runCurrent()
                identity = SessionIdentity(2, true, false)
                sessions.invalidate()
                runCurrent()
            } finally {
                oldList.complete(listOf(conversation(1)))
                oldContacts.complete(listOf(contact(9)))
                runCurrent()
            }
            assertEquals(2L, model.list.state.value.conversations.single().fromUser?.id)
            assertEquals(listOf(8L), model.contacts.state.value.contacts.map { it.id })
            assertEquals(listOf(8L), model.share.state.value.contacts.map { it.id })
            assertNull(model.share.state.value.error)
        }
    }

    @Test fun sameAccountReauthorizationInvalidatesThePreviousGeneration() = checkModels { model ->
        model.chat.load(9)
        runCurrent()
        val old = model.chat.state.value
        sessions.invalidate()
        assertNull(model.chat.state.value.session)
        runCurrent()
        model.chat.send(old, "Stale draft") {}
        runCurrent()
        assertTrue(source.writes.isEmpty())
        assertNotEquals(old.session, model.chat.state.value.session)
        assertEquals(old.session?.identity, model.chat.state.value.session?.identity)
    }

    @Test fun obsoleteRecipientHistoryAndFailureCannotReplaceTheNewConversation() {
        val old = CompletableDeferred<Unit>()
        source.messages = { _, user ->
            if (user == 8L) withContext(NonCancellable) { old.await(); error("Old failure") }
            else listOf(message(user))
        }
        checkModels { model ->
            try {
                runCurrent()
                model.chat.load(8)
                runCurrent()
                model.chat.load(9)
                runCurrent()
            } finally {
                old.complete(Unit)
                runCurrent()
            }
            assertEquals(9L, model.chat.state.value.userId)
            assertEquals(9L, model.chat.state.value.messages.single().fromUser?.id)
            assertNull(model.chat.state.value.error)
        }
    }

    @Test fun olderRefreshCannotOverwriteTheLatestSameAccountResult() {
        val old = CompletableDeferred<List<PrivateConversation>>()
        var loads = 0
        source.conversations = { if (loads++ == 0) withContext(NonCancellable) { old.await() } else listOf(conversation(2)) }
        checkModels { model ->
            try {
                runCurrent()
                model.list.refresh()
                runCurrent()
            } finally {
                old.complete(listOf(conversation(1)))
                runCurrent()
            }
            assertEquals(2L, model.list.state.value.conversations.single().fromUser?.id)
        }
    }

    @Test fun failedRefreshKeepsCurrentDataAndCanRecover() = checkModels { model ->
        runCurrent()
        source.conversations = { error("Offline") }
        model.list.refresh()
        runCurrent()
        assertEquals("Offline", model.list.state.value.error)
        assertEquals(1, model.list.state.value.conversations.size)
        source.conversations = { emptyList() }
        model.list.refresh()
        runCurrent()
        assertNull(model.list.state.value.error)
        assertTrue(model.list.state.value.conversations.isEmpty())
    }

    @Test fun successfulSendUsesTheVisibleOwnerAndRefreshesOnlyThatConversation() = checkModels { model ->
        model.chat.load(9)
        runCurrent()
        var callbacks = 0
        val expected = model.chat.state.value
        model.chat.send(expected, " Text ") { callbacks++ }
        model.chat.send(expected, "Duplicate") { callbacks++ }
        runCurrent()
        assertEquals(listOf(sessions.snapshot() to listOf(9L)), source.writes)
        assertEquals(1, callbacks)
        assertFalse(model.chat.state.value.isSending)
        assertFalse(model.chat.state.value.isLoading)
    }

    @Test fun retainedConversationCallbackCannotSendToAnOldRecipient() = checkModels { model ->
        model.chat.load(8)
        runCurrent()
        val old = model.chat.state.value
        model.chat.load(9)
        runCurrent()
        model.chat.send(old, "Text") { fail("Obsolete callback") }
        runCurrent()
        assertTrue(source.writes.isEmpty())
    }

    @Test fun queuedSendCannotDispatchAfterInvalidation() = checkModels { model ->
        model.chat.load(9)
        runCurrent()
        model.chat.send(model.chat.state.value, "Text") { fail("Invalidated callback") }
        sessions.invalidate()
        runCurrent()
        assertTrue(source.writes.isEmpty())
    }

    @Test fun lateSendSuccessCannotClearAnotherAccountsDraftOrPublishSendingState() {
        val old = CompletableDeferred<Unit>()
        source.write = { withContext(NonCancellable) { old.await() } }
        checkModels { model ->
            var callbacks = 0
            try {
                model.chat.load(9)
                runCurrent()
                model.chat.send(model.chat.state.value, "Text") { callbacks++ }
                runCurrent()
                assertTrue(model.chat.state.value.isSending)
                identity = SessionIdentity(2, true, false)
                sessions.invalidate()
                runCurrent()
            } finally {
                old.complete(Unit)
                runCurrent()
            }
            assertEquals(0, callbacks)
            assertFalse(model.chat.state.value.isSending)
            assertEquals(2L, model.chat.state.value.session?.identity?.userId)
            assertNull(model.chat.state.value.error)
        }
    }

    @Test fun shareRecipientsMustBelongToTheVisibleContactsAndSession() = checkModels { model ->
        model.share.loadContacts()
        runCurrent()
        val old = model.share.state.value
        model.share.sendPrivate(old, resource, setOf(8), "") { fail("Unknown contact") }
        identity = SessionIdentity(2, true, false)
        sessions.invalidate()
        runCurrent()
        model.share.sendPrivate(old, resource, setOf(9), "") { fail("Old selection") }
        model.share.shareTimeline(old, resource, "") { fail("Old timeline draft") }
        runCurrent()
        assertTrue(source.writes.isEmpty())
    }

    @Test fun lateShareFailureCannotPolluteANewSession() {
        val old = CompletableDeferred<Unit>()
        source.write = { withContext(NonCancellable) { old.await(); error("Old failure") } }
        checkModels { model ->
            try {
                model.share.loadContacts()
                runCurrent()
                model.share.sendPrivate(model.share.state.value, resource, setOf(9), "") { fail("Unexpected callback") }
                runCurrent()
                identity = SessionIdentity(2, true, false)
                sessions.invalidate()
                runCurrent()
            } finally {
                old.complete(Unit)
                runCurrent()
            }
            assertFalse(model.share.state.value.isSending)
            assertNull(model.share.state.value.error)
            assertEquals(2L, model.share.state.value.session?.identity?.userId)
        }
    }

    @Test fun shareSuccessAndTimelineSuccessUseTheVisibleOwnerOnce() = checkModels { model ->
        model.share.loadContacts()
        runCurrent()
        var callbacks = 0
        model.share.sendPrivate(model.share.state.value, resource, setOf(9), "Text") { callbacks++ }
        model.share.sendPrivate(model.share.state.value, resource, setOf(9), "Duplicate") { callbacks++ }
        runCurrent()
        model.share.shareTimeline(model.share.state.value, resource, "Text") { callbacks++ }
        runCurrent()
        assertEquals(listOf(sessions.snapshot() to listOf(9L), sessions.snapshot() to emptyList<Long>()), source.writes)
        assertEquals(2, callbacks)
        assertFalse(model.share.state.value.isSending)
    }

    @Test fun lateShareSuccessCannotDismissANewAccountsSheet() {
        val old = CompletableDeferred<Unit>()
        source.write = { withContext(NonCancellable) { old.await() } }
        checkModels { model ->
            var callbacks = 0
            try {
                runCurrent()
                model.share.shareTimeline(model.share.state.value, resource, "Text") { callbacks++ }
                runCurrent()
                identity = SessionIdentity(2, true, false)
                sessions.invalidate()
                runCurrent()
            } finally {
                old.complete(Unit)
                runCurrent()
            }
            assertEquals(0, callbacks)
            assertFalse(model.share.state.value.isSending)
            assertEquals(2L, model.share.state.value.session?.identity?.userId)
        }
    }

    @Test fun sendFailureKeepsTheConversationAndAllowsAnExplicitRetry() = checkModels { model ->
        model.chat.load(9)
        runCurrent()
        source.write = { error("Offline") }
        model.chat.send(model.chat.state.value, "Text") { fail("Failed send") }
        runCurrent()
        assertEquals("Offline", model.chat.state.value.error)
        assertEquals(9L, model.chat.state.value.userId)
        assertEquals(1, model.chat.state.value.messages.size)
        assertFalse(model.chat.state.value.isSending)
        source.write = {}
        var callbacks = 0
        model.chat.send(model.chat.state.value, "Text") { callbacks++ }
        runCurrent()
        assertEquals(1, callbacks)
        assertNull(model.chat.state.value.error)
    }

    @Test fun recoveryClearsPrivateDataAndBlocksReadsAndWritesUntilRecovered() = checkModels { model ->
        model.chat.load(9)
        model.share.loadContacts()
        runCurrent()
        val old = model.chat.state.value
        val count = source.reads.size
        sessions.setRecoveryRequired(true)
        runCurrent()
        model.list.refresh()
        model.chat.send(old, "Text") { fail("Recovery callback") }
        model.share.shareTimeline(model.share.state.value, resource, "") { fail("Recovery share") }
        runCurrent()
        assertTrue(model.chat.state.value.messages.isEmpty())
        assertTrue(model.share.state.value.contacts.isEmpty())
        assertEquals("Session recovery is required", model.list.state.value.error)
        assertEquals(count, source.reads.size)
        assertTrue(source.writes.isEmpty())
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(1, model.chat.state.value.messages.size)
        assertNull(model.list.state.value.error)
    }

    @Test fun clearingViewModelsStopsAutomaticAccountReloads() = checkModels { model ->
        runCurrent()
        val count = source.reads.size
        model.store.clear()
        sessions.invalidate()
        runCurrent()
        assertEquals(count, source.reads.size)
    }

    companion object {
        private fun contact(id: Long) = MessageContact(id, "User $id", null, null, null)
        private fun conversation(owner: Long) = PrivateConversation("$owner-9", contact(owner), contact(9), 0, "Text", 0)
        private fun message(owner: Long) = PrivateMessage(owner, contact(owner), contact(9), 0, PrivateMessagePayload("Text", null))
    }
}
