package com.ljyh.mei.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.data.session.AccountState

@Composable
fun rememberAccount(): State<AccountState> = AppGraph.component.account().state.collectAsState()
