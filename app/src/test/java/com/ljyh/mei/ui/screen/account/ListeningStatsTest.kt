package com.ljyh.mei.ui.screen.account

import com.ljyh.mei.data.model.melox.ListeningStatsBin
import com.ljyh.mei.data.model.melox.ListeningStatsPeriod
import com.ljyh.mei.data.model.melox.ListeningStatsReport
import com.ljyh.mei.data.model.melox.ListeningStatsSummary
import com.ljyh.mei.data.model.melox.ListeningStatsYear
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class ListeningStatsTest {
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
    fun annualSelectionUsesPublishedYearSummariesWithoutRequestingAReport() = runTest(dispatcher) {
        val requested = mutableListOf<ListeningStatsPeriod>()
        val published = listOf(ListeningStatsYear(2025, 24_360, 64))
        val viewModel = ListeningStatsViewModel(
            loadSummary = { ListeningStatsSummary(48_660) },
            loadReport = { period, _ -> requested += period; report(period) },
            loadYears = { published },
        )
        runCurrent()
        assertEquals(48_660L, viewModel.state.value.summary!!.totalSeconds)
        assertEquals(ListeningStatsPeriod.Week, viewModel.state.value.report!!.period)

        viewModel.selectPeriod(ListeningStatsPeriod.Year)
        assertNull(viewModel.state.value.report)
        runCurrent()

        assertEquals(listOf(ListeningStatsPeriod.Week), requested)
        assertEquals(published, viewModel.state.value.years)
        assertNull(viewModel.state.value.report)
        assertFalse(viewModel.state.value.canGoPrevious)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun historyNavigationUsesServerBoundariesAndCanReturnToLatest() = runTest(dispatcher) {
        val anchors = mutableListOf<Long?>()
        val viewModel = ListeningStatsViewModel(
            loadSummary = { ListeningStatsSummary(60) },
            loadReport = { period, anchor ->
                anchors += anchor
                when (anchor) {
                    null -> report(period, start = 1_000, end = 1_999)
                    999L -> report(period, start = 100, end = 999)
                    99L -> report(period, start = 1, end = 99)
                    else -> error("Unexpected report anchor")
                }
            },
            loadYears = { emptyList() },
        )
        runCurrent()
        viewModel.previous()
        assertNull(viewModel.state.value.report)
        assertFalse(viewModel.state.value.isLatest)
        runCurrent()
        viewModel.previous()
        runCurrent()
        viewModel.next()
        runCurrent()
        viewModel.current()
        runCurrent()

        assertEquals(listOf(null, 999L, 99L, 999L, null), anchors)
        assertTrue(viewModel.state.value.isLatest)
        assertFalse(viewModel.state.value.canGoNext)
        assertEquals(1_000L, viewModel.state.value.report!!.startTimeMs)
    }

    @Test
    fun periodSwitchIgnoresASuspendedPreviousReportAndResetsTheAnchor() = runTest(dispatcher) {
        lateinit var oldWeek: Continuation<ListeningStatsReport>
        val requests = mutableListOf<Pair<ListeningStatsPeriod, Long?>>()
        val viewModel = ListeningStatsViewModel(
            loadSummary = { ListeningStatsSummary(60) },
            loadReport = { period, anchor ->
                requests += period to anchor
                if (period == ListeningStatsPeriod.Week) suspendCoroutine { oldWeek = it }
                else report(period, minutes = 120)
            },
            loadYears = { emptyList() },
        )
        runCurrent()
        viewModel.selectPeriod(ListeningStatsPeriod.Month)
        runCurrent()
        oldWeek.resume(report(ListeningStatsPeriod.Week, minutes = 500))
        runCurrent()

        assertEquals(listOf(ListeningStatsPeriod.Week to null, ListeningStatsPeriod.Month to null), requests)
        assertEquals(ListeningStatsPeriod.Month, viewModel.state.value.period)
        assertEquals(120L, viewModel.state.value.report!!.totalMinutes)
        assertTrue(viewModel.state.value.isLatest)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun refreshFailureKeepsDisplayedDataAndAllowsRetry() = runTest(dispatcher) {
        var fail = false
        var minutes = 60L
        val viewModel = ListeningStatsViewModel(
            loadSummary = { ListeningStatsSummary(3_600) },
            loadReport = { period, _ ->
                if (fail) error("Report unavailable")
                report(period, minutes = minutes)
            },
            loadYears = { emptyList() },
        )
        runCurrent()
        fail = true
        viewModel.refresh()
        assertTrue(viewModel.state.value.refreshing)
        assertEquals(60L, viewModel.state.value.report!!.totalMinutes)
        runCurrent()

        assertEquals(60L, viewModel.state.value.report!!.totalMinutes)
        assertEquals("Report unavailable", viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.refreshing)
        fail = false
        minutes = 120
        viewModel.refresh()
        runCurrent()
        assertEquals(120L, viewModel.state.value.report!!.totalMinutes)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun accountSessionChangeClearsSummaryHistoryAndSuspendedResults() = runTest(dispatcher) {
        val session = MutableStateFlow(1)
        var calls = 0
        lateinit var oldRefresh: Continuation<ListeningStatsReport>
        lateinit var newReport: Continuation<ListeningStatsReport>
        val anchors = mutableListOf<Long?>()
        val viewModel = ListeningStatsViewModel(
            loadSummary = { ListeningStatsSummary(session.value * 3_600L) },
            loadReport = { period, anchor ->
                anchors += anchor
                when (++calls) {
                    1 -> report(period, start = 1_000)
                    2 -> report(period, start = 100)
                    3 -> suspendCoroutine { oldRefresh = it }
                    else -> suspendCoroutine { newReport = it }
                }
            },
            loadYears = { emptyList() },
            sessionChanges = session.map { Unit },
        )
        runCurrent()
        viewModel.previous()
        runCurrent()
        viewModel.refresh()
        runCurrent()
        session.value = 2
        runCurrent()
        assertNull(viewModel.state.value.summary)
        assertNull(viewModel.state.value.report)
        assertTrue(viewModel.state.value.isLatest)
        assertFalse(viewModel.state.value.canGoNext)

        newReport.resume(report(ListeningStatsPeriod.Week, minutes = 120))
        oldRefresh.resume(report(ListeningStatsPeriod.Week, minutes = 999))
        runCurrent()

        assertEquals(listOf(null, 999L, 999L, null), anchors)
        assertEquals(7_200L, viewModel.state.value.summary!!.totalSeconds)
        assertEquals(120L, viewModel.state.value.report!!.totalMinutes)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun slowSummaryDoesNotDelayPeriodOrAnnualReports() = runTest(dispatcher) {
        val summary = CompletableDeferred<ListeningStatsSummary>()
        val published = listOf(ListeningStatsYear(2025, 3_600, 20))
        val viewModel = ListeningStatsViewModel(
            loadSummary = { summary.await() },
            loadReport = { period, _ -> report(period) },
            loadYears = { published },
        )
        runCurrent()
        assertNotNull(viewModel.state.value.report)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.summary)

        viewModel.selectPeriod(ListeningStatsPeriod.Year)
        runCurrent()
        assertEquals(published, viewModel.state.value.years)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.summary)

        summary.complete(ListeningStatsSummary(3_600))
        runCurrent()
        assertEquals(3_600L, viewModel.state.value.summary!!.totalSeconds)
        assertEquals(published, viewModel.state.value.years)
    }

    @Test
    fun summaryFailureDoesNotInventZeroOrHideAValidPeriodReport() = runTest(dispatcher) {
        var failSummary = true
        val viewModel = ListeningStatsViewModel(
            loadSummary = {
                if (failSummary) error("Summary unavailable")
                ListeningStatsSummary(3_600)
            },
            loadReport = { period, _ -> report(period) },
            loadYears = { emptyList() },
        )
        runCurrent()
        assertNull(viewModel.state.value.summary)
        assertEquals("Summary unavailable", viewModel.state.value.summaryError)
        assertNotNull(viewModel.state.value.report)
        assertNull(viewModel.state.value.error)

        failSummary = false
        viewModel.refresh()
        runCurrent()
        assertEquals(3_600L, viewModel.state.value.summary!!.totalSeconds)
        assertNull(viewModel.state.value.summaryError)
    }

    private fun report(
        period: ListeningStatsPeriod,
        start: Long = 1_000,
        end: Long = 1_999,
        minutes: Long = 60,
    ) = ListeningStatsReport(
        period = period,
        startTimeMs = start,
        endTimeMs = end,
        totalMinutes = minutes,
        listenedDays = 1,
        bins = listOf(ListeningStatsBin("2026-10-18", minutes)),
        isCurrent = end == 1_999L,
    )
}
