package com.ljyh.mei.ui.screen.cloud

import androidx.lifecycle.ViewModelStore
import com.ljyh.mei.data.model.melox.CloudMusicPage
import com.ljyh.mei.data.model.melox.CloudSong
import com.ljyh.mei.data.repository.CloudMusicSource
import com.ljyh.mei.data.session.SessionIdentity
import com.ljyh.mei.data.session.SessionStamp
import com.ljyh.mei.data.session.SessionStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
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
class CloudMusicSessionTest {
    private var identity = SessionIdentity(1, true, false)
    private var sessionUnavailable = false
    private val sessions = SessionStore().apply { bind {
        if (sessionUnavailable) throw IOException("Session unavailable")
        identity
    } }
    private val source = Source()

    private class Source : CloudMusicSource {
        var load: suspend (SessionStamp) -> CloudMusicPage = { page(it.identity.userId) }
        var delete: suspend () -> Unit = {}
        var upload: suspend ((Long, Long) -> Unit) -> Unit = {}
        val reads = mutableListOf<SessionStamp>()
        val deletes = mutableListOf<Pair<SessionStamp, Long>>()
        val uploads = mutableListOf<Pair<SessionStamp, String>>()
        override suspend fun cloudSongs(session: SessionStamp): CloudMusicPage {
            reads += session
            return load(session)
        }
        override suspend fun deleteCloudSong(session: SessionStamp, id: Long) {
            deletes += session to id
            delete()
        }
        override suspend fun uploadCloudSong(session: SessionStamp, uri: String, onProgress: (Long, Long) -> Unit) {
            uploads += session to uri
            upload(onProgress)
        }
    }

    private fun checkModel(check: suspend TestScope.(CloudMusicViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = CloudMusicViewModel(source, sessions)
            store.put("cloud", model)
            check(model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun sharedPageLoadsOnlyForAnAuthenticatedOwner() = checkModel { model, _ ->
        runCurrent()
        assertEquals(sessions.snapshot(), model.state.value.session)
        assertEquals(listOf(1L), model.state.value.page!!.songs.map { it.id })
        assertFalse(model.state.value.isLoading)
        assertEquals(listOf(sessions.snapshot()), source.reads)
    }

    @Test fun guestDoesNotReadDeleteOrChooseAnUploadAccount() {
        identity = SessionIdentity(0, false, true)
        checkModel { model, _ ->
            runCurrent()
            val owner = sessions.snapshot()
            model.refresh()
            model.delete(song(1), owner)
            model.upload("content://fixture/audio", owner)
            assertFalse(model.beginUploadSelection(owner))
            runCurrent()
            assertTrue(source.reads.isEmpty() && source.deletes.isEmpty() && source.uploads.isEmpty())
            assertFalse(model.state.value.isLoading)
            assertEquals("Sign-in required", model.state.value.error)
        }
    }

    @Test fun latestRefreshWinsOverACanceledNonCooperativeRead() = checkModel { model, _ ->
        runCurrent()
        val late = CompletableDeferred<CloudMusicPage>()
        source.load = { withContext(NonCancellable) { late.await() } }
        model.refresh()
        runCurrent()
        source.load = { page(2) }
        model.refresh()
        runCurrent()
        late.complete(page(3))
        runCurrent()
        assertEquals(listOf(2L), model.state.value.page!!.songs.map { it.id })
        assertFalse(model.state.value.isLoading)
        assertNull(model.state.value.error)
    }

    @Test fun refreshKeepsConcurrentUploadAndDeleteFlags() = checkModel { model, _ ->
        runCurrent()
        val deleted = CompletableDeferred<Unit>()
        val uploaded = CompletableDeferred<Unit>()
        source.delete = { deleted.await() }
        source.upload = { progress -> progress(1, 4); uploaded.await() }
        val owner = sessions.snapshot()
        model.delete(song(1), owner)
        model.upload("content://fixture/audio", owner)
        runCurrent()
        model.refresh()
        runCurrent()
        assertEquals(setOf(1L), model.state.value.deletingIds)
        assertTrue(model.state.value.isUploading)
        assertEquals(0.25f, model.state.value.uploadProgress)
        deleted.complete(Unit)
        uploaded.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.deletingIds.isEmpty())
        assertFalse(model.state.value.isUploading)
    }

    @Test fun failedRefreshKeepsTheOwnedSnapshotAndDoesNotRetry() = checkModel { model, _ ->
        runCurrent()
        val before = model.state.value.page
        source.load = { error("Offline") }
        model.refresh()
        runCurrent()
        assertSame(before, model.state.value.page)
        assertEquals("Offline", model.state.value.error)
        assertFalse(model.state.value.isLoading)
        assertEquals(2, source.reads.size)
        source.load = { page(2) }
        model.refresh()
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals(2L, model.state.value.page!!.songs.single().id)
    }

    @Test fun repeatedDeleteIsReservedBeforeDispatchAndFailuresKeepTheSong() = checkModel { model, _ ->
        runCurrent()
        source.delete = { error("Rejected") }
        val owner = sessions.snapshot()
        model.delete(song(1), owner)
        model.delete(song(1), owner)
        model.delete(song(99), owner)
        runCurrent()
        assertEquals(listOf(owner to 1L), source.deletes)
        assertTrue(model.state.value.deletingIds.isEmpty())
        assertEquals(1L, model.state.value.page!!.songs.single().id)
        assertEquals("Rejected", model.state.value.error)
        assertEquals(1, source.reads.size)
    }

    @Test fun successfulDeleteInvalidatesAPreMutationReadAndRefreshes() = checkModel { model, _ ->
        runCurrent()
        val old = CompletableDeferred<CloudMusicPage>()
        source.load = { withContext(NonCancellable) { old.await() } }
        model.refresh()
        runCurrent()
        source.load = { page() }
        model.delete(song(1), sessions.snapshot())
        runCurrent()
        old.complete(page(1))
        runCurrent()
        assertTrue(model.state.value.page!!.songs.isEmpty())
        assertEquals(0, model.state.value.page!!.count)
        assertTrue(model.state.value.deletingIds.isEmpty())
    }

    @Test fun accountTransitionClearsImmediatelyAndRejectsLateReadsAndMutations() = checkModel { model, _ ->
        runCurrent()
        val owner = sessions.snapshot()
        val lateRead = CompletableDeferred<CloudMusicPage>()
        val lateWrite = CompletableDeferred<Unit>()
        var progress: ((Long, Long) -> Unit)? = null
        source.load = { if (it == owner) withContext(NonCancellable) { lateRead.await() } else page(2) }
        source.delete = { withContext(NonCancellable) { lateWrite.await(); error("Old delete failure") } }
        source.upload = { progress = it; withContext(NonCancellable) { lateWrite.await() } }
        model.refresh()
        model.delete(song(1), owner)
        model.upload("content://fixture/old", owner)
        runCurrent()
        sessions.beginTransition().use {
            identity = SessionIdentity(2, true, false)
            assertNull(model.state.value.page)
            assertNull(model.state.value.session)
            assertFalse(model.state.value.isUploading)
            assertTrue(model.state.value.deletingIds.isEmpty())
        }
        runCurrent()
        runCatching { progress!!(1, 2) }
        lateRead.complete(page(1))
        lateWrite.complete(Unit)
        runCurrent()
        assertEquals(2L, model.state.value.page!!.songs.single().id)
        assertEquals(sessions.snapshot(), model.state.value.session)
        assertEquals(0f, model.state.value.uploadProgress)
        assertNull(model.state.value.error)
        assertEquals(1, source.reads.count { it.identity.userId == 2L })
        model.delete(song(2), owner)
        model.upload("content://fixture/stale", owner)
        runCurrent()
        assertEquals(1, source.deletes.size)
        assertEquals(1, source.uploads.size)
    }

    @Test fun scheduledWritesNeverChooseTheNewAccount() = checkModel { model, _ ->
        runCurrent()
        val owner = sessions.snapshot()
        model.delete(song(1), owner)
        model.upload("content://fixture/audio", owner)
        sessions.beginTransition().use { identity = SessionIdentity(2, true, false) }
        runCurrent()
        assertTrue(source.deletes.isEmpty())
        assertTrue(source.uploads.isEmpty())
        assertEquals(2L, model.state.value.page!!.songs.single().id)
    }

    @Test fun pickerCompletionKeepsItsLaunchOwnerAndRejectsSameAccountReauthorization() = checkModel { model, _ ->
        runCurrent()
        val owner = sessions.snapshot()
        assertTrue(model.beginUploadSelection(owner))
        assertFalse(model.beginUploadSelection(owner))
        sessions.invalidate()
        assertNull(model.state.value.page)
        runCurrent()
        assertNull(model.takeUploadSelection())
        assertFalse(model.beginUploadSelection(owner))
        assertTrue(model.beginUploadSelection(sessions.snapshot()))
        assertEquals(sessions.snapshot(), model.takeUploadSelection())
        assertNull(model.takeUploadSelection())
    }

    @Test fun orphanAndCanceledPickerResultsDoNotUpload() = checkModel { model, store ->
        runCurrent()
        assertNull(model.takeUploadSelection())
        assertTrue(model.beginUploadSelection(sessions.snapshot()))
        assertNotNull(model.takeUploadSelection())
        assertTrue(model.beginUploadSelection(sessions.snapshot()))
        store.clear()
        assertNull(model.takeUploadSelection())
        assertFalse(model.beginUploadSelection(sessions.snapshot()))
        assertTrue(source.uploads.isEmpty())
    }

    @Test fun successfulUploadUsesTheCapturedOwnerAndRefreshesOnlyAfterCompletion() = checkModel { model, _ ->
        runCurrent()
        val pending = CompletableDeferred<Unit>()
        var progress: ((Long, Long) -> Unit)? = null
        source.upload = { progress = it; it(1, 4); pending.await() }
        val owner = sessions.snapshot()
        model.upload("content://fixture/audio", owner)
        model.upload("content://fixture/duplicate", owner)
        runCurrent()
        assertEquals(listOf(owner to "content://fixture/audio"), source.uploads)
        assertEquals(1, source.reads.size)
        assertTrue(model.state.value.isUploading)
        assertEquals(0.25f, model.state.value.uploadProgress)
        source.load = { page(1, 2) }
        pending.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.isUploading)
        assertEquals(1f, model.state.value.uploadProgress)
        assertEquals(listOf(1L, 2L), model.state.value.page!!.songs.map { it.id })
        val finished = model.state.value
        runCatching { progress!!(1, 2) }
        assertEquals(finished, model.state.value)
    }

    @Test fun uploadAndDeleteCancellationClearBusyFlagsWithoutSuccessOrRetry() = checkModel { model, _ ->
        runCurrent()
        source.upload = { throw CancellationException("Canceled") }
        source.delete = { throw CancellationException("Canceled") }
        model.upload("content://fixture/audio", sessions.snapshot())
        model.delete(song(1), sessions.snapshot())
        runCurrent()
        assertFalse(model.state.value.isUploading)
        assertTrue(model.state.value.deletingIds.isEmpty())
        assertEquals(0f, model.state.value.uploadProgress)
        assertNull(model.state.value.error)
        assertEquals(1, source.reads.size)
    }

    @Test fun uploadFailureKeepsTheLibraryAndDoesNotRefreshOrRetry() = checkModel { model, _ ->
        runCurrent()
        source.upload = { it(1, 4); error("Transfer failed") }
        model.upload("content://fixture/audio", sessions.snapshot())
        runCurrent()
        assertFalse(model.state.value.isUploading)
        assertEquals(0.25f, model.state.value.uploadProgress)
        assertEquals("Transfer failed", model.state.value.error)
        assertEquals(1, source.uploads.size)
        assertEquals(1, source.reads.size)
        assertEquals(1L, model.state.value.page!!.songs.single().id)
    }

    @Test fun recoveryRejectsCallbacksBeforeCollectionAndResumesAfterward() = checkModel { model, _ ->
        runCurrent()
        val owner = sessions.snapshot()
        sessions.setRecoveryRequired(true)
        model.delete(song(1), owner)
        model.upload("content://fixture/audio", owner)
        assertFalse(model.beginUploadSelection(owner))
        runCurrent()
        assertNull(model.state.value.page)
        assertFalse(model.state.value.isLoading)
        assertEquals("Session recovery is required", model.state.value.error)
        assertTrue(source.deletes.isEmpty() && source.uploads.isEmpty())
        sessions.setRecoveryRequired(false)
        runCurrent()
        assertEquals(1L, model.state.value.page!!.songs.single().id)
        assertNull(model.state.value.error)
    }

    @Test fun rowActionsRequireBothTheRenderedPageAndItsCurrentOwner() = checkModel { model, _ ->
        runCurrent()
        val rendered = model.state.value
        var actions = 0
        model.withCurrentPage(rendered.session, rendered.page) { actions++ }
        model.refresh()
        runCurrent()
        model.withCurrentPage(rendered.session, rendered.page) { actions++ }
        val refreshed = model.state.value
        sessions.invalidate()
        model.withCurrentPage(refreshed.session, refreshed.page) { actions++ }
        runCurrent()
        assertEquals(1, actions)
    }

    @Test fun unavailableSessionRejectsUiCallbacksWithoutDispatchOrCrashing() = checkModel { model, _ ->
        runCurrent()
        val rendered = model.state.value
        val owner = checkNotNull(rendered.session)
        assertTrue(model.beginUploadSelection(owner))
        sessionUnavailable = true
        assertNull(model.takeUploadSelection())
        assertFalse(model.beginUploadSelection(owner))
        model.refresh()
        model.delete(song(1), owner)
        model.upload("content://fixture/audio", owner)
        var actions = 0
        model.withCurrentPage(owner, rendered.page) { actions++ }
        runCurrent()
        assertEquals(0, actions)
        assertTrue(source.uploads.isEmpty() && source.deletes.isEmpty())
        assertEquals(1, source.reads.size)
        sessionUnavailable = false
        model.refresh()
        runCurrent()
        assertEquals(2, source.reads.size)
    }

    @Test fun clearingViewModelRejectsLateReadAndUploadProgress() = checkModel { model, store ->
        runCurrent()
        val pending = CompletableDeferred<CloudMusicPage>()
        var progress: ((Long, Long) -> Unit)? = null
        source.load = { withContext(NonCancellable) { pending.await() } }
        source.upload = { progress = it; withContext(NonCancellable) { pending.await() }; Unit }
        model.refresh()
        model.upload("content://fixture/audio", sessions.snapshot())
        runCurrent()
        store.clear()
        val before = model.state.value
        runCatching { progress!!(1, 2) }
        pending.complete(page(2))
        runCurrent()
        assertEquals(before, model.state.value)
    }

    @Test fun invalidationAlreadyInFlightCannotPublishAfterDisposal() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = sessions.onInvalidated {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            checkModel { model, store ->
                runCurrent()
                val before = model.state.value
                val invalidating = thread { sessions.invalidate() }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    store.clear()
                } finally {
                    release.countDown()
                    invalidating.join(5_000)
                }
                assertFalse(invalidating.isAlive)
                assertEquals(before, model.state.value)
            }
        } finally { first.close() }
    }

    companion object {
        private fun song(id: Long) = CloudSong(id, "Song $id", "Artist", "Album", null, 1000, 100, 320000, 0)
        private fun page(vararg ids: Long) = CloudMusicPage(ids.map(::song), ids.size, 20, 100, false)
    }
}
