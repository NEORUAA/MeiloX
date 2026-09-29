package com.ljyh.mei.parasite

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.playback.BeatNetNative
import com.ljyh.mei.ui.glass.GlassBackdropHost
import com.ljyh.mei.ui.glass.GlassIconButton
import com.ljyh.mei.ui.glass.SfSymbol
import com.ljyh.mei.ui.glass.SfIcon
import com.ljyh.mei.ui.theme.MusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ljyh.mei.di.AppGraph
import com.ljyh.mei.ui.screen.about.AboutViewModel
import com.ljyh.mei.ui.screen.log.LogViewModel
import com.ljyh.mei.ui.screen.setting.StorageManagementViewModel
import com.ljyh.mei.ui.screen.account.NeteaseLoginScreen
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets

class HostRuntimeProbeActivity : ComponentActivity() {
    override val defaultViewModelProviderFactory: androidx.lifecycle.ViewModelProvider.Factory
        get() = AppGraph.component.viewModelFactory()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(HostRuntimeProbe.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.classLoader = javaClass.classLoader
        intent?.setExtrasClassLoader(javaClass.classLoader)
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        HostRuntimeProbe.report("runtime_activity_created restored=${savedInstanceState != null}")
        setContent {
            MusicTheme(seedColor = Color(0xFFFA233B)) {
                if (intent.getBooleanExtra("meilox.login", false)) {
                    CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets.systemBars) {
                        GlassBackdropHost(
                            modifier = Modifier.fillMaxSize(),
                            sampledContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) },
                            overlayContent = { NeteaseLoginScreen(onNavigateBack = ::finish) },
                        )
                    }
                    return@MusicTheme
                }
                val about: AboutViewModel = viewModel()
                val logs: LogViewModel = viewModel()
                val storage: StorageManagementViewModel = viewModel()
                var count by rememberSaveable { mutableIntStateOf(0) }
                val playback by HostRuntimeProbe.playbackState.collectAsState()
                GlassBackdropHost(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    sampledContent = {
                        Column(
                            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Image(painterResource(R.drawable.logo), contentDescription = "MeiloX", modifier = Modifier.size(160.dp))
                            Spacer(Modifier.height(24.dp))
                            Text("MeiloX", style = MaterialTheme.typography.headlineLarge)
                            Text(count.toString(), style = MaterialTheme.typography.bodyLarge)
                            Text("${playback.positionMs / 1000}s", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    overlayContent = {
                        Row(Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp)) {
                            GlassIconButton(onClick = { finish() }) {
                                SfIcon(SfSymbol.ChevronBack, contentDescription = "Back")
                            }
                        }
                        Row(
                            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            GlassIconButton(onClick = {
                                count += 1
                                HostRuntimeProbe.report("runtime_interaction count=$count")
                            }) {
                                SfIcon(SfSymbol.ArrowClockwise, contentDescription = "Refresh")
                            }
                            GlassIconButton(enabled = playback.ready, onClick = {
                                startForegroundService(HostRuntimeProbe.playbackIntent(
                                    this@HostRuntimeProbeActivity,
                                    if (playback.playing) HostRuntimeProbeService.PAUSE else HostRuntimeProbeService.PLAY,
                                ))
                            }) {
                                SfIcon(if (playback.playing) SfSymbol.PauseFilled else SfSymbol.PlayFilled, contentDescription = "Play or pause")
                            }
                            GlassIconButton(enabled = playback.ready, onClick = {
                                stopService(HostRuntimeProbe.playbackIntent(this@HostRuntimeProbeActivity, HostRuntimeProbeService.STOP))
                            }) {
                                SfIcon(SfSymbol.Close, contentDescription = "Stop")
                            }
                        }
                    },
                )
                LaunchedEffect(Unit) {
                    val storageState = storage.state.first { it.hasLoaded || it.error != null }
                    val localModelsCreated = about.javaClass == AboutViewModel::class.java && logs.javaClass == LogViewModel::class.java
                    HostRuntimeProbe.report("runtime_graph models=${AppGraph.component.viewModelFactory().registeredModels.size} scoped_context=${AppGraph.component.context() === applicationContext} local_viewmodels=$localModelsCreated storage_loaded=${storageState.hasLoaded} storage_error=${storageState.error != null}")
                    HostRuntimeProbe.report("runtime_composition_ready density=${resources.displayMetrics.density}")
                    withContext(Dispatchers.Default) {
                        try {
                            val rejectedInvalidShape = BeatNetNative.predict(floatArrayOf()) == null
                            HostRuntimeProbe.report("runtime_native_loaded invalid_shape_rejected=$rejectedInvalidShape")
                        } catch (error: Throwable) {
                            HostRuntimeProbe.report("runtime_native_failed type=${error.javaClass.name}")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        HostRuntimeProbe.report("runtime_activity_destroyed changing_config=$isChangingConfigurations")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.classLoader = javaClass.classLoader
        super.onRestoreInstanceState(savedInstanceState)
    }
}
