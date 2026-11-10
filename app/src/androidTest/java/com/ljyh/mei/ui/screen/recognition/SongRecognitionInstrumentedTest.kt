package com.ljyh.mei.ui.screen.recognition

import android.Manifest
import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.AppContext
import com.ljyh.mei.MainActivity
import com.ljyh.mei.recognition.RecognitionDuration
import com.ljyh.mei.recognition.RecognitionPhase
import com.ljyh.mei.recognition.RecognitionSource
import com.ljyh.mei.recognition.SongRecognitionSession
import com.ljyh.mei.source.api.ProviderIds
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
    private val audio = checkNotNull(context.getSystemService(AudioManager::class.java))

    @Test
    fun microphoneServiceMatchesAudioAndReleasesRecordingOnStopAndScreenClose() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        (context.applicationContext as AppContext).sourcePlugins.loadInstalled()
        val store = ViewModelStore()
        lateinit var session: SongRecognitionSession
        lateinit var model: SongRecognitionViewModel
        activity.scenario.onActivity { screen ->
            session = screen.recognitionSession
            model = ViewModelProvider.create(
                store, screen.defaultViewModelProviderFactory, screen.defaultViewModelCreationExtras,
            )[SongRecognitionViewModel::class]
        }
        try {
            instrumentation.runOnMainSync { model.open(ProviderIds.NETEASE) }
            await { model.state.value.isAvailable || model.state.value.phase == RecognitionPhase.Failed }
            assertTrue("Recognition source unavailable: ${model.state.value.error}", model.state.value.isAvailable)
            instrumentation.runOnMainSync {
                model.selectDuration(RecognitionDuration.Quick)
                model.start(RecognitionSource.Microphone)
            }
            await(35_000) { !session.state.value.isWorking }
            assertTrue(
                "Recognition failed: ${session.state.value.error}",
                session.state.value.phase in setOf(RecognitionPhase.NoMatch, RecognitionPhase.Results),
            )
            await { audio.activeRecordingConfigurations.isEmpty() }
            val retained = session.state.value.results
            instrumentation.runOnMainSync {
                model.selectDuration(RecognitionDuration.Continuous)
                model.start(RecognitionSource.Microphone)
            }
            await { audio.activeRecordingConfigurations.isNotEmpty() }
            instrumentation.runOnMainSync { model.stop() }
            await { !session.state.value.isWorking && audio.activeRecordingConfigurations.isEmpty() }
            instrumentation.runOnMainSync { model.start(RecognitionSource.Microphone) }
            await { audio.activeRecordingConfigurations.isNotEmpty() }
            instrumentation.runOnMainSync { store.clear() }
            await { !session.state.value.isWorking && audio.activeRecordingConfigurations.isEmpty() }
            assertEquals(retained, session.state.value.results)
            assertEquals(
                if (retained.isEmpty()) RecognitionPhase.Ready else RecognitionPhase.Results,
                session.state.value.phase,
            )
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            await { !session.state.value.isWorking && audio.activeRecordingConfigurations.isEmpty() }
        }
    }

    private suspend fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(40)
    }
}
