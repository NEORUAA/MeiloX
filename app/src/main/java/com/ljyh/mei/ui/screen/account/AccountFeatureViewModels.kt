package com.ljyh.mei.ui.screen.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.model.melox.*
import com.ljyh.mei.data.repository.AccountFeatureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class AccountFeatureState<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class AccountSecurityViewModel @Inject constructor(private val repository: AccountFeatureRepository) : ViewModel() {
    private val _state = MutableStateFlow(AccountFeatureState<AccountSecuritySummary>())
    val state = _state.asStateFlow()
    private var job: Job? = null
    init { viewModelScope.launch { repository.sessionChanges.collect { session ->
        job?.cancel(); _state.value = AccountFeatureState()
        if (session.isNotBlank()) refresh()
    } } }
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try { _state.value = AccountFeatureState(data = repository.summary()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.value = _state.value.copy(loading = false, error = failure.message) }
        }
    }
}

@HiltViewModel
class MusicianAccountViewModel @Inject constructor(private val repository: AccountFeatureRepository) : ViewModel() {
    private val _state = MutableStateFlow(AccountFeatureState<MusicianAccount>())
    val state = _state.asStateFlow()
    private var job: Job? = null
    init { viewModelScope.launch { repository.sessionChanges.collect { session ->
        job?.cancel(); _state.value = AccountFeatureState()
        if (session.isNotBlank()) refresh()
    } } }
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try { _state.value = AccountFeatureState(data = repository.musician()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.value = _state.value.copy(loading = false, error = failure.message) }
        }
    }
}

enum class AccountDeviceTab { Devices, Authorizations, History }
data class AccountDevicesState(
    val tab: AccountDeviceTab = AccountDeviceTab.Devices,
    val devices: List<AccountDevice> = emptyList(),
    val applications: List<AccountAuthorization> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val cursor: String? = null,
    val more: Boolean = false,
)

@HiltViewModel
class AccountDevicesViewModel @Inject constructor(private val repository: AccountFeatureRepository) : ViewModel() {
    private val _state = MutableStateFlow(AccountDevicesState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    init { viewModelScope.launch { repository.sessionChanges.collect { session ->
        job?.cancel(); _state.value = AccountDevicesState(tab = _state.value.tab)
        if (session.isNotBlank()) refresh()
    } } }
    fun select(tab: AccountDeviceTab) {
        if (tab == _state.value.tab) return
        job?.cancel(); _state.value = AccountDevicesState(tab = tab); refresh()
    }
    fun refresh() = load(false)
    fun loadMore() { if (_state.value.more && !_state.value.loading) load(true) }
    private fun load(append: Boolean) {
        job?.cancel()
        val previous = _state.value
        job = viewModelScope.launch {
            _state.value = previous.copy(loading = true, error = null)
            try {
                if (previous.tab == AccountDeviceTab.Devices) {
                    _state.value = previous.copy(devices = repository.devices(), loading = false)
                } else {
                    val page = repository.authorizations(previous.tab == AccountDeviceTab.History,
                        if (append) previous.cursor.orEmpty() else "")
                    _state.value = previous.copy(
                        applications = ((if (append) previous.applications else emptyList()) + page.records).distinctBy { it.clientId },
                        cursor = page.cursor, more = page.more, loading = false,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _state.value = previous.copy(loading = false, error = failure.message) }
        }
    }
    suspend fun removeDevice(device: AccountDevice, captcha: String = "") {
        check(!device.current)
        repository.removeDevice(device.key, captcha)
        refresh()
    }
    suspend fun revoke(application: AccountAuthorization) {
        repository.revokeAuthorization(application.clientId)
        refresh()
    }
    suspend fun sendCode() = repository.sendDeviceVerificationCode()
}
