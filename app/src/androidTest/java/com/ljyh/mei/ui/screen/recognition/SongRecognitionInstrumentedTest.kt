package com.ljyh.mei.ui.screen.recognition

import android.media.AudioManager
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.MainActivity
import com.ljyh.mei.source.shared.SourceRecognitionRepository
import com.ljyh.mei.source.script.ScriptPluginManager
import com.ljyh.mei.source.api.ProviderIds
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.ljyh.mei.recognition.RecognitionDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongRecognitionInstrumentedTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audio = context.getSystemService(AudioManager::class.java)

    @Test
    fun actualCaptureFingerprintAndMatchingFinishAndStopReleasesTheMicrophone() = runBlocking {
        val dependencies = EntryPointAccessors.fromApplication(context, RecognitionTestDependencies::class.java)
        dependencies.plugins().loadInstalled()
        val model = SongRecognitionViewModel(context, dependencies.repository())
        val store = ViewModelStore()
        instrumentation.runOnMainSync { store.put("recognition", model) }
        try {
            instrumentation.runOnMainSync { model.open(ProviderIds.NETEASE) }
            await { model.state.value.isAvailable || model.state.value.phase == RecognitionPhase.Failed }
            assertTrue("Recognition source unavailable: ${model.state.value.error}", model.state.value.isAvailable)
            instrumentation.runOnMainSync {
                model.selectDuration(RecognitionDuration.Quick)
                model.start()
            }
            await(35_000) { !model.state.value.isWorking }
            assertTrue(
                "Recognition failed: ${model.state.value.error}",
                model.state.value.phase in setOf(RecognitionPhase.NoMatch, RecognitionPhase.Results),
            )
            await { audio.activeRecordingConfigurations.isEmpty() }
            val retained = model.state.value.results
            instrumentation.runOnMainSync {
                model.selectDuration(RecognitionDuration.Continuous)
                model.start()
            }
            await { audio.activeRecordingConfigurations.isNotEmpty() }
            instrumentation.runOnMainSync {
                model.stop()
                model.start()
            }
            await { audio.activeRecordingConfigurations.isNotEmpty() }
            instrumentation.runOnMainSync { model.stop() }
            await { audio.activeRecordingConfigurations.isEmpty() }
            assertEquals(retained, model.state.value.results)
            assertEquals(
                if (retained.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results,
                model.state.value.phase,
            )
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            await { audio.activeRecordingConfigurations.isEmpty() }
        }
    }

    private suspend fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(40)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RecognitionTestDependencies {
    fun repository(): SourceRecognitionRepository
    fun plugins(): ScriptPluginManager
}
