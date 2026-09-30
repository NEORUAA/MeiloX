package com.ljyh.mei.runtime

import android.content.Context
import com.ljyh.mei.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object RuntimeBootstrap {
    fun initialize(context: Context) {
        AppGraph.initialize(context)
        val pendingCookie = runBlocking(Dispatchers.IO) { AppGraph.component.standaloneSessions().initialize() }
        com.ljyh.mei.standalone.StandaloneDownloadRuntime.start(context, AppGraph.component.standaloneSessions())
        if (pendingCookie != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                AppGraph.component.standaloneAccounts().login(pendingCookie)
            }
        }
    }
}
