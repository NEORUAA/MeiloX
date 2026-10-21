package com.ljyh.mei.ui.screen.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.melox.ListeningStatsPeriod
import com.ljyh.mei.data.model.melox.ListeningStatsReport
import com.ljyh.mei.data.model.melox.ListeningStatsSummary
import com.ljyh.mei.data.model.melox.ListeningStatsYear
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ListeningStatsState(
    val period: ListeningStatsPeriod = ListeningStatsPeriod.Week,
    val summary: ListeningStatsSummary? = null,
    val report: ListeningStatsReport? = null,
    val years: List<ListeningStatsYear> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val summaryError: String? = null,
    val isLatest: Boolean = true,
    val canGoNext: Boolean = false,
) {
    val canGoPrevious: Boolean
        get() = !loading && period != ListeningStatsPeriod.Year && (report?.startTimeMs ?: 0) > 0
}

@HiltViewModel
class ListeningStatsViewModel internal constructor(
    private val loadSummary: suspend () -> ListeningStatsSummary,
    private val loadReport: suspend (ListeningStatsPeriod, Long?) -> ListeningStatsReport,
    private val loadYears: suspend () -> List<ListeningStatsYear>,
    sessionChanges: Flow<Unit> = flowOf(Unit),
) : ViewModel() {
    @Inject
    constructor(repository: MeloXRepository, @ApplicationContext context: Context) : this(
        loadSummary = { repository.listeningStatsSummary() },
        loadReport = { period, endTimeMs -> repository.listeningStatsReport(period, endTimeMs) },
        loadYears = { repository.listeningStatsYears() },
        sessionChanges = context.dataStore.data
            .map { it[UserIdKey] to it[CookieKey] }
            .distinctUntilChanged()
            .map { Unit },
    )

    private val _state = MutableStateFlow(ListeningStatsState())
    val state = _state.asStateFlow()
    private val anchors = mutableListOf<Long?>(null)
    private var loadJob: Job? = null
    private var revision = 0L

    init {
        viewModelScope.launch {
            sessionChanges.collect {
                anchors.clear()
                anchors += null
                _state.value = ListeningStatsState(period = _state.value.period)
                readSelection(forceSummary = true)
            }
        }
    }

    fun selectPeriod(period: ListeningStatsPeriod) {
        if (period == _state.value.period) return
        anchors.clear()
        anchors += null
        _state.value = ListeningStatsState(
            period = period,
            summary = _state.value.summary,
            summaryError = _state.value.summaryError,
        )
        readSelection()
    }

    fun refresh() = readSelection(forceSummary = true)

    fun previous() {
        val state = _state.value
        if (!state.canGoPrevious) return
        val endTimeMs = state.report!!.startTimeMs - 1
        if (anchors.last()?.let { endTimeMs >= it } == true) return
        anchors += endTimeMs
        changeAnchor()
    }

    fun next() {
        if (_state.value.loading || anchors.size <= 1) return
        anchors.removeAt(anchors.lastIndex)
        changeAnchor()
    }

    fun current() {
        if (anchors.size == 1) return
        anchors.clear()
        anchors += null
        changeAnchor()
    }

    private fun changeAnchor() {
        _state.value = _state.value.copy(
            report = null,
            loading = true,
            refreshing = false,
            error = null,
            isLatest = anchors.size == 1,
            canGoNext = anchors.size > 1,
        )
        readSelection()
    }

    private fun readSelection(forceSummary: Boolean = false) {
        loadJob?.cancel()
        val expected = ++revision
        val selected = _state.value
        val anchor = anchors.last()
        _state.value = selected.copy(
            loading = true,
            refreshing = selected.report != null || (selected.period == ListeningStatsPeriod.Year && selected.years.isNotEmpty()),
            error = null,
        )
        loadJob = viewModelScope.launch {
            coroutineScope {
                val summary = async {
                    if (forceSummary || selected.summary == null) readResult { loadSummary() } else null
                }
                val report = if (selected.period != ListeningStatsPeriod.Year) {
                    readResult { loadReport(selected.period, anchor) }
                } else null
                val years = if (selected.period == ListeningStatsPeriod.Year) readResult { loadYears() } else null
                ensureActive()
                if (expected != revision) return@coroutineScope
                _state.value = _state.value.copy(
                    report = report?.getOrNull() ?: _state.value.report,
                    years = years?.getOrNull() ?: _state.value.years,
                    loading = false,
                    refreshing = false,
                    error = (report?.exceptionOrNull() ?: years?.exceptionOrNull())?.displayMessage(),
                )
                val summaryResult = summary.await()
                ensureActive()
                if (expected != revision) return@coroutineScope
                if (summaryResult != null) {
                    _state.value = _state.value.copy(
                        summary = summaryResult.getOrNull() ?: _state.value.summary,
                        summaryError = summaryResult.exceptionOrNull()?.displayMessage(),
                    )
                }
            }
        }
    }

    private suspend fun <T> readResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun Throwable.displayMessage(): String = message?.takeIf(String::isNotBlank) ?: toString()
}
