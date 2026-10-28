package com.ljyh.mei.ui.component.sheet

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.ui.GlasmioSheetState
import io.github.neoruaa.glasmio.ui.GlasmioSheetValue
import io.github.neoruaa.glasmio.ui.rememberGlasmioSheetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dispatch a replacement panel or navigation only after the native host finishes dismissal. */
@Stable
class MeiloXSheetDismissal internal constructor(
    val state: GlasmioSheetState,
    private val scope: CoroutineScope,
    private val onDismiss: State<() -> Unit>,
) {
    var isDismissing by mutableStateOf(false)
        private set
    private var delivered = false
    private var afterDismiss: (() -> Unit)? = null

    fun requestDismiss(afterDismiss: () -> Unit = {}) {
        if (isDismissing || delivered) return
        this.afterDismiss = afterDismiss
        isDismissing = true
        scope.launch {
            try {
                state.hide()
                if (state.currentValue != GlasmioSheetValue.Hidden) {
                    isDismissing = false
                    this@MeiloXSheetDismissal.afterDismiss = null
                }
            } catch (cancelled: CancellationException) {
                isDismissing = false
                this@MeiloXSheetDismissal.afterDismiss = null
                throw cancelled
            }
        }
    }

    fun onDismissed() {
        if (delivered) return
        delivered = true
        isDismissing = true
        val action = afterDismiss
        afterDismiss = null
        onDismiss.value()
        action?.invoke()
    }

    internal fun reset() {
        delivered = false
        isDismissing = false
        afterDismiss = null
    }
}

@Composable
fun rememberMeiloXSheetDismissal(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (GlasmioSheetValue) -> Boolean = { true },
    sheetState: GlasmioSheetState = rememberGlasmioSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded,
        confirmValueChange = confirmValueChange,
    ),
    visible: Boolean = true,
): MeiloXSheetDismissal {
    val currentDismiss = rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val dismissal = remember(sheetState, scope) { MeiloXSheetDismissal(sheetState, scope, currentDismiss) }
    LaunchedEffect(dismissal, visible) { if (visible) dismissal.reset() }
    return dismissal
}

@Composable
fun rememberMeiloXSheetScrollEdge(state: ScrollState): Float {
    val distance = with(LocalDensity.current) { 56.dp.toPx() }
    val progress by remember(state, distance) {
        derivedStateOf { (state.value / distance).coerceIn(0f, 1f) }
    }
    return progress
}
