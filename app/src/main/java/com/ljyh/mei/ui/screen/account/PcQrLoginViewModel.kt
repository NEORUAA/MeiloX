package com.ljyh.mei.ui.screen.account

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.source.shared.SharedOnlineRepository
import com.ljyh.mei.source.api.SourceException
import com.ljyh.mei.source.api.SourceErrorCode
import dagger.hilt.android.lifecycle.HiltViewModel
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
    onlineRepository: SharedOnlineRepository,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {
    private val repository = onlineRepository.screen("pc-qr-login", savedStateHandle["providerId"])
    private val _sourceSession = kotlinx.coroutines.flow.MutableStateFlow(com.ljyh.mei.source.shared.OnlineSessionState(savedStateHandle.get<String>("providerId").orEmpty(), false, com.ljyh.mei.source.shared.OnlineSessionKind.None))
    val sourceSession = _sourceSession.asStateFlow()
    private var sourceWatchJob: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        PcQrLoginUiState(phase = PcQrLoginPhase.Preparing),
    )
    val state = _state.asStateFlow()

    private var actionJob: Job? = null
    private var generation = 0L
    private var ignoredRawValues: Set<String> = emptySet()

    init { observeSession() }

    private fun observeSession() {
        sourceWatchJob?.cancel()
        sourceWatchJob = viewModelScope.launch { repository.sessionChanges.collect { owner ->
            _sourceSession.value = if (owner.isBlank()) com.ljyh.mei.source.shared.OnlineSessionState(_sourceSession.value.providerId, false, com.ljyh.mei.source.shared.OnlineSessionKind.None)
            else repository.sessionState()
        } }
    }

    suspend fun loginWithMobilePassword(
        phone: String,
        countryCode: String,
        password: String,
    ) {
        repository.loginWithMobilePassword(phone, countryCode, password)
        observeSession()
    }

    suspend fun requestMobileSmsCode(
        activity: Activity,
        phone: String,
        countryCode: String,
    ): Boolean {
        val authenticated = repository.requestMobileSmsCode(phone, countryCode)
        if (authenticated) observeSession()
        return authenticated
    }

    suspend fun loginWithMobileSms(
        phone: String,
        countryCode: String,
        code: String,
    ) {
        repository.loginWithMobileSms(phone, countryCode, code)
        observeSession()
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

    fun onQrValues(rawValues: List<String>) {
        if (!_state.value.isAnalyzing) return
        val candidates = decodedQrCandidates(rawValues).filterNot { it in ignoredRawValues }
        if (candidates.isEmpty()) return
        startAction {
            _state.value = PcQrLoginUiState(phase = PcQrLoginPhase.MarkingScanned)
            var accepted: PcQrLoginPayload? = null
            try {
                for (rawValue in candidates) {
                    val result = repository.invoke("login", "parsePcQr", com.google.gson.JsonObject().apply {
                        addProperty("value", rawValue)
                    })
                    if (!isCurrent()) return@startAction
                    accepted = sourcePcQrPayload(result, rawValue)
                    if (accepted != null) break
                }
                val payload = accepted
                if (payload == null) {
                    ignore(candidates)
                    _state.value = PcQrLoginUiState(error = PcQrLoginError(PcQrLoginErrorKind.InvalidQr))
                    return@startAction
                }
                _state.value = PcQrLoginUiState(phase = PcQrLoginPhase.MarkingScanned, session = payload.toSession())
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
                if (isCurrent()) {
                    if (accepted != null) handleScanFailure(checkNotNull(accepted), error)
                    else _state.value = PcQrLoginUiState(error = error.asPcQrLoginError())
                }
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
                ignore(listOf(session.rawValue))
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
        ignoredRawValues = emptySet()
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

    fun reportImageError(message: String) {
        if (_state.value.phase != PcQrLoginPhase.Scanning) return
        _state.value = _state.value.copy(
            error = PcQrLoginError(PcQrLoginErrorKind.Request, message),
        )
    }

    private fun handleScanFailure(payload: PcQrLoginPayload, error: Exception) {
        Log.e(PC_QR_LOG_TAG, "Scan action failed", error)
        ignore(listOf(payload.rawValue))
        _state.value = PcQrLoginUiState(error = error.asPcQrLoginError())
    }

    private fun handleAuthorizationFailure(session: PcQrLoginSession, error: Exception) {
        Log.e(PC_QR_LOG_TAG, "Authorize action failed", error)
        if (error is SourceException && error.code == SourceErrorCode.AUTH_EXPIRED) {
            ignore(listOf(session.rawValue))
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

    private fun ignore(values: List<String>) {
        ignoredRawValues = (ignoredRawValues + values).toList().takeLast(32).toSet()
    }

    private inner class ActionScope(private val actionGeneration: Long) {
        fun isCurrent(): Boolean = actionGeneration == generation
    }
}

private const val PC_QR_LOG_TAG = "PcQrLogin"

private fun Exception.asPcQrLoginError(): PcQrLoginError =
    if (this is SourceException && code == SourceErrorCode.AUTH_EXPIRED) {
        PcQrLoginError(PcQrLoginErrorKind.Expired, message)
    } else {
        PcQrLoginError(PcQrLoginErrorKind.Request, message)
    }

private fun PcQrLoginPayload.toSession(platform: String = ""): PcQrLoginSession =
    PcQrLoginSession(key, clientTraceId, platform, rawValue)

/** Decoder output is source-neutral. The selected source recognizes its own login formats. */
internal fun decodedQrCandidates(rawValues: List<String>): List<String> = rawValues.asSequence()
    .filter { it.length <= 8192 }.map(String::trim).filter(String::isNotEmpty).distinct().take(8).toList()

internal fun sourcePcQrPayload(result: com.google.gson.JsonElement, rawValue: String): PcQrLoginPayload? {
    if (result.isJsonNull) return null
    if (!result.isJsonObject) throw SourceException(SourceErrorCode.INVALID_RESPONSE)
    fun text(name: String, optional: Boolean = false): String? {
        val value = result.asJsonObject[name]
        if (value == null || value.isJsonNull) {
            if (optional) return null
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        }
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank() || value.asString.length > 2048)
            throw SourceException(SourceErrorCode.INVALID_RESPONSE)
        return value.asString
    }
    return PcQrLoginPayload(checkNotNull(text("key")), text("clientTraceId", true), rawValue)
}
