package com.ljyh.mei.playback

import com.ljyh.mei.playback.DownloadNotificationState.Outcome
import org.junit.Assert.*
import org.junit.Test

class DownloadNotificationStateTest {
    @Test fun concurrentDownloadsHaveOneAggregateAndOnlyAllSuccessCompletesIt() {
        val state = DownloadNotificationState()
        val first = state.begin("first", 17, 0)
        val second = state.begin("second", 17, 0)
        state.update(first, "progress", 80)
        state.update(second, "progress", 20)
        assertEquals(50, state.snapshot()!!.progress)
        assertEquals("正在下载 (0/2)", state.snapshot()!!.title)
        state.finish(first, Outcome.SUCCESS)
        assertEquals(1, state.snapshot()!!.active)
        assertEquals(60, state.snapshot()!!.progress)
        assertEquals("正在下载 (1/2)", state.snapshot()!!.title)
        state.finish(second, Outcome.SUCCESS)
        assertEquals(0, state.snapshot()!!.active)
        assertEquals(100, state.snapshot()!!.progress)
        assertEquals("全部下载完成", state.snapshot()!!.title)
    }

    @Test fun failedOrCanceledWorkCannotBeReportedAsAllSuccessful() {
        listOf(Outcome.FAILED, Outcome.CANCELED).forEach { outcome ->
            val state = DownloadNotificationState()
            val first = state.begin("first", 17, 0)
            val second = state.begin("second", 17, 0)
            state.finish(first, outcome, "denied")
            assertEquals(1, state.snapshot()!!.active)
            state.finish(second, Outcome.SUCCESS)
            assertNotEquals("全部下载完成", state.snapshot()!!.title)
            assertEquals(0, state.snapshot()!!.progress)
        }
    }

    @Test fun singleFailurePreservesTheSpecificStageMessage() {
        val state = DownloadNotificationState()
        state.finish(state.begin("first", 17, 0), Outcome.FAILED, "permission denied")
        assertEquals("permission denied", state.snapshot()!!.title)
        assertEquals("first", state.snapshot()!!.lastRequestId)
    }

    @Test fun replacedExecutionCannotChangeOrFinishTheNewExecution() {
        val state = DownloadNotificationState()
        val old = state.begin("first", 17, 0)
        val current = state.begin("first", 17, 0)
        assertFalse(state.update(old, "old", 99))
        assertFalse(state.finish(old, Outcome.FAILED, "old"))
        assertEquals(1, state.snapshot()!!.total)
        assertTrue(state.update(current, "current", 11))
        assertEquals("current", state.snapshot()!!.title)
    }

    @Test fun newAccountOrGenerationRejectsOldResults() {
        listOf(18L to 0L, 17L to 1L).forEach { (account, generation) ->
            val state = DownloadNotificationState()
            val old = state.begin("old", 17, 0)
            val current = state.begin("new", account, generation)
            assertFalse(state.finish(old, Outcome.FAILED, "old"))
            assertEquals(1, state.snapshot()!!.active)
            state.finish(current, Outcome.SUCCESS)
            assertEquals("全部下载完成", state.snapshot()!!.title)
            assertEquals(1, state.snapshot()!!.total)
        }
    }

    @Test fun aNewBatchCannotInheritEarlierCompletionAndSilentRemovalIsNotFailure() {
        val state = DownloadNotificationState()
        val old = state.begin("old", 17, 0)
        state.finish(old, Outcome.SUCCESS)
        val revision = state.revision
        val current = state.begin("new", 17, 0)
        assertTrue(state.revision > revision)
        assertEquals(0, state.snapshot()!!.progress)
        assertFalse(state.finish(old, Outcome.FAILED))
        state.finish(current, null)
        assertNull(state.snapshot())
    }

    @Test fun invalidProgressCannotBecomeCompletionAndDuplicateFinishIsIgnored() {
        val state = DownloadNotificationState()
        val lease = state.begin("first", 17, 0)
        state.update(lease, "progress", 100)
        assertEquals(99, state.snapshot()!!.progress)
        state.update(lease, "progress", -20)
        assertEquals(0, state.snapshot()!!.progress)
        assertTrue(state.finish(lease, Outcome.SUCCESS))
        val result = state.snapshot()
        assertFalse(state.finish(lease, Outcome.FAILED))
        assertEquals(result, state.snapshot())
    }
}
