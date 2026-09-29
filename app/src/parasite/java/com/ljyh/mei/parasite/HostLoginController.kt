package com.ljyh.mei.parasite

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostLoginStatus { IDLE, LOADING, WAITING, CONFIRMING, EXPIRED, ERROR, SUCCESS }

data class HostLoginState<Q>(val status: HostLoginStatus = HostLoginStatus.IDLE, val qr: Q? = null)

internal interface HostLoginBackend<Q> {
    fun open(emit: (HostLoginState<Q>) -> Unit): Closeable
    fun logout()
}

/** One screen owns one attempt. All entry points and callbacks use the owning UI thread. */
class HostLoginController<Q> internal constructor(
    private val open: ((HostLoginState<Q>) -> Unit) -> Closeable,
    private val authenticated: () -> Boolean,
) : Closeable {
    private val owner = Thread.currentThread()
    private var epoch = 0L
    private var active = false
    private var handle: Closeable? = null
    private val mutableState = MutableStateFlow(HostLoginState<Q>())
    val state = mutableState.asStateFlow()

    fun start() {
        checkThread()
        if (!active && state.value.status != HostLoginStatus.SUCCESS) refresh()
    }

    fun refresh() {
        checkThread()
        retire()
        val attempt = epoch
        active = true
        mutableState.value = HostLoginState(HostLoginStatus.LOADING)
        try {
            val opened = open { event ->
                checkThread()
                if (active && epoch == attempt) {
                    val next = if (event.status == HostLoginStatus.SUCCESS && !authenticated()) {
                        HostLoginState<Q>(HostLoginStatus.ERROR)
                    } else event
                    mutableState.value = next
                    if (next.status in terminalStates) retire()
                }
            }
            // An observer may deliver a terminal event synchronously while open() is running.
            if (active && epoch == attempt) handle = opened else opened.close()
        } catch (_: Exception) {
            if (epoch == attempt) {
                retire()
                mutableState.value = HostLoginState(HostLoginStatus.ERROR)
            }
        }
    }

    override fun close() {
        checkThread()
        retire()
        if (state.value.status != HostLoginStatus.SUCCESS) mutableState.value = HostLoginState()
    }

    private fun retire() {
        active = false
        epoch++
        val previous = handle
        handle = null
        previous?.close()
    }

    private fun checkThread() = check(Thread.currentThread() === owner) { "Login requires its owning thread" }

    private companion object {
        val terminalStates = setOf(HostLoginStatus.EXPIRED, HostLoginStatus.ERROR, HostLoginStatus.SUCCESS)
    }
}
