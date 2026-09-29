package com.ljyh.mei.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.parasite.HostAccountState

@Composable
fun rememberHostAccount(): State<HostAccountState> = AppGraph.component.hostAccount().state.collectAsState()
