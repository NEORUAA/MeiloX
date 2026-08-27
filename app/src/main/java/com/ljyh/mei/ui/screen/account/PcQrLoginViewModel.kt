package com.ljyh.mei.ui.screen.account

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PcQrLoginException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class PcQrLoginPhase {
    Preparing,
    Scanning,
    MarkingScanned,
    AwaitingConfirmation,
    Authorizing,
    Cancelling,
    Success,
    Exit,
}

enum class PcQrLoginErrorKind {
    InvalidQr,
    Expired,
    Request,
    CancelFailed,
    Camera,
}

data class PcQrLoginError(
    val kind: PcQrLoginErrorKind,
    val message: String? = null,
)

data class PcQrLoginSession(
    val key: String,
    val clientTraceId: String?,
    val platform: String,
    val rawValue: String,
)

data class PcQrLoginUiState(
    val phase: PcQrLoginPhase = PcQrLoginPhase.Scanning,
    val session: PcQrLoginSession? = null,
    val error: PcQrLoginError? = null,
) {
    val isAnalyzing: Boolean get() = phase == PcQrLoginPhase.Scanning
    val isDialogVisible: Boolean get() = phase in setOf(
        PcQrLoginPhase.AwaitingConfirmation,
        PcQrLoginPhase.Authorizing,
        PcQrLoginPhase.Cancelling,
    )
}

internal data class PcQrLoginPayload(
    val key: String,
    val clientTraceId: String?,
    val rawValue: String,
)

@HiltViewModel
class PcQrLoginViewModel @Inject constructor(
    private val repository: MeloXRepository,
) : ViewModel() {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        PcQrLoginUiState(phase = PcQrLoginPhase.Preparing),
    )
    val state = _state.asStateFlow()

    private var actionJob: Job? = null
    private var generation = 0L
    private var ignoredRawValue: String? = null

    suspend fun loginWithMobilePassword(
        phone: String,
        countryCode: String,
        password: String,
    ) {
        repository.loginWithMobilePassword(phone, countryCode, password)
    }

    suspend fun requestMobileSmsCode(
        activity: Activity,
        phone: String,
        countryCode: String,
    ) {
        repository.requestMobileSmsCode(activity, phone, countryCode)
    }

    suspend fun loginWithMobileSms(
        phone: String,
        countryCode: String,
        code: String,
    ) {
        repository.loginWithMobileSms(phone, countryCode, code)
    }

    fun prepare() {
        if (_state.value.phase != PcQrLoginPhase.Preparing || actionJob?.isActive == true) return
        startAction {
            _state.value = PcQrLoginUiState(phase = PcQrLoginPhase.Preparing)
            try {
                repository.preparePcQrLoginSecurity()
                if (isCurrent()) _state.value = PcQrLoginUiState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent()) {
                    Log.e(PC_QR_LOG_TAG, "Security prewarm failed", error)
                    _state.value = PcQrLoginUiState(
                        phase = PcQrLoginPhase.Preparing,
                        error = PcQrLoginError(PcQrLoginErrorKind.Request, error.message),
                    )
                }
            }
        }
    }

    fun onQrValue(rawValue: String) {
        val current = _state.value
        Log.i(PC_QR_LOG_TAG, "QR value received length=${rawValue.length} phase=${current.phase}")
        if (!current.isAnalyzing || rawValue == ignoredRawValue) {
            Log.i(
                PC_QR_LOG_TAG,
                "QR value ignored analyzing=${current.isAnalyzing} duplicate=${rawValue == ignoredRawValue}",
            )
            return
        }
        val payload = parseNeteasePcLoginQr(rawValue)
        if (payload == null) {
            Log.i(PC_QR_LOG_TAG, "QR value rejected as unsupported")
            ignoredRawValue = rawValue
            _state.value = _state.value.copy(
                error = PcQrLoginError(PcQrLoginErrorKind.InvalidQr),
            )
            return
        }
        Log.i(
            PC_QR_LOG_TAG,
            "QR value accepted keyLength=${payload.key.length} trace=${!payload.clientTraceId.isNullOrBlank()}",
        )

        startAction {
            _state.value = PcQrLoginUiState(
                phase = PcQrLoginPhase.MarkingScanned,
                session = payload.toSession(),
            )
            try {
                val platform = repository.markPcQrScanned(payload.key, payload.clientTraceId)
                if (isCurrent()) {
                    _state.value = PcQrLoginUiState(
                        phase = PcQrLoginPhase.AwaitingConfirmation,
                        session = payload.toSession(platform),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent()) handleScanFailure(payload, error)
            }
        }
    }

    fun authorize() {
        val session = _state.value.session
            ?.takeIf { _state.value.phase == PcQrLoginPhase.AwaitingConfirmation }
            ?: return
        startAction {
            _state.value = PcQrLoginUiState(
                phase = PcQrLoginPhase.Authorizing,
                session = session,
            )
            try {
                repository.authorizePcQrLogin(session.key, session.clientTraceId)
                if (isCurrent()) {
                    _state.value = PcQrLoginUiState(
                        phase = PcQrLoginPhase.Success,
                        session = session,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent()) handleAuthorizationFailure(session, error)
            }
        }
    }

    fun cancel() {
        val session = _state.value.session
            ?.takeIf { _state.value.phase == PcQrLoginPhase.AwaitingConfirmation }
            ?: return
        startAction {
            _state.value = PcQrLoginUiState(
                phase = PcQrLoginPhase.Cancelling,
                session = session,
            )
            val error = try {
                repository.cancelPcQrLogin(session.key, session.clientTraceId)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
            if (isCurrent()) {
                ignoredRawValue = session.rawValue
                _state.value = PcQrLoginUiState(
                    error = error?.let {
                        PcQrLoginError(PcQrLoginErrorKind.CancelFailed, it.message)
                    },
                )
            }
        }
    }

    fun requestExit() {
        val current = _state.value
        val session = current.session
        if (current.phase == PcQrLoginPhase.AwaitingConfirmation && session != null) {
            startAction {
                _state.value = current.copy(
                    phase = PcQrLoginPhase.Cancelling,
                    error = null,
                )
                withTimeoutOrNull(1_500) {
                    try {
                        repository.cancelPcQrLogin(session.key, session.clientTraceId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Exiting must stay responsive; an unconfirmed QR code cannot authorize itself.
                    }
                }
                if (isCurrent()) _state.value = PcQrLoginUiState(PcQrLoginPhase.Exit)
            }
        } else if (current.phase !in setOf(
                PcQrLoginPhase.Authorizing,
                PcQrLoginPhase.Cancelling,
            )
        ) {
            actionJob?.cancel()
            generation++
            _state.value = PcQrLoginUiState(PcQrLoginPhase.Exit)
        }
    }

    fun retry() {
        ignoredRawValue = null
        if (_state.value.phase == PcQrLoginPhase.Preparing) {
            prepare()
        } else {
            _state.value = PcQrLoginUiState()
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun reportCameraError(message: String?) {
        if (_state.value.phase != PcQrLoginPhase.Scanning) return
        _state.value = _state.value.copy(
            error = PcQrLoginError(PcQrLoginErrorKind.Camera, message),
        )
    }

    private fun handleScanFailure(payload: PcQrLoginPayload, error: Exception) {
        Log.e(PC_QR_LOG_TAG, "Scan action failed", error)
        ignoredRawValue = payload.rawValue
        _state.value = PcQrLoginUiState(error = error.asPcQrLoginError())
    }

    private fun handleAuthorizationFailure(session: PcQrLoginSession, error: Exception) {
        Log.e(PC_QR_LOG_TAG, "Authorize action failed", error)
        if (error is PcQrLoginException && error.code == 800) {
            ignoredRawValue = session.rawValue
            _state.value = PcQrLoginUiState(
                error = PcQrLoginError(PcQrLoginErrorKind.Expired, error.message),
            )
        } else {
            _state.value = PcQrLoginUiState(
                phase = PcQrLoginPhase.AwaitingConfirmation,
                session = session,
                error = error.asPcQrLoginError(),
            )
        }
    }

    private fun startAction(block: suspend ActionScope.() -> Unit) {
        actionJob?.cancel()
        val currentGeneration = ++generation
        actionJob = viewModelScope.launch {
            ActionScope(currentGeneration).block()
        }
    }

    private inner class ActionScope(private val actionGeneration: Long) {
        fun isCurrent(): Boolean = actionGeneration == generation
    }
}

private const val PC_QR_LOG_TAG = "PcQrLogin"

private fun Exception.asPcQrLoginError(): PcQrLoginError =
    if (this is PcQrLoginException && code == 800) {
        PcQrLoginError(PcQrLoginErrorKind.Expired, message)
    } else {
        PcQrLoginError(PcQrLoginErrorKind.Request, message)
    }

private fun PcQrLoginPayload.toSession(platform: String = ""): PcQrLoginSession =
    PcQrLoginSession(key, clientTraceId, platform, rawValue)

internal fun parseNeteasePcLoginQr(rawValue: String): PcQrLoginPayload? = runCatching {
    val trimmed = rawValue.trim()
    val uri = URI(trimmed)
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https")) return null
    if (!uri.host.equals("music.163.com", ignoreCase = true)) return null
    if (uri.userInfo != null) return null
    if (uri.port !in setOf(-1, 80, 443)) return null
    if (uri.path !in setOf("/login", "/login/")) return null

    val parameters = uri.rawQuery.orEmpty()
        .split('&')
        .mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val separator = entry.indexOf('=')
            val rawName = if (separator >= 0) entry.substring(0, separator) else entry
            val rawParameterValue = if (separator >= 0) entry.substring(separator + 1) else ""
            decodeQueryPart(rawName) to decodeQueryPart(rawParameterValue)
        }
        .groupBy({ it.first }, { it.second })
    val key = parameters["codekey"]?.firstOrNull(String::isNotBlank) ?: return null
    val clientTraceId = parameters["login_traceId"]?.firstOrNull(String::isNotBlank)
    PcQrLoginPayload(key, clientTraceId, trimmed)
}.getOrNull()

private fun decodeQueryPart(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
