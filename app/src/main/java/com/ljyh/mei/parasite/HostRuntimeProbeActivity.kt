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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

class HostRuntimeProbeActivity : ComponentActivity() {
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
                var count by rememberSaveable { mutableIntStateOf(0) }
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
                        }
                    },
                    overlayContent = {
                        Row(Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp)) {
                            GlassIconButton(onClick = { finish() }) {
                                SfIcon(SfSymbol.ChevronBack, contentDescription = "Back")
                            }
                        }
                        Box(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 40.dp)) {
                            GlassIconButton(onClick = {
                                count += 1
                                HostRuntimeProbe.report("runtime_interaction count=$count")
                            }) {
                                SfIcon(SfSymbol.ArrowClockwise, contentDescription = "Refresh")
                            }
                        }
                    },
                )
                LaunchedEffect(Unit) {
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
