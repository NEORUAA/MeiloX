package com.ljyh.mei.playback

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BeatNetAutoMixAnalyzerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun highRateStereoDecodeKeepsOnlyTheAnalysisSampleCount() = runBlocking {
        withFixture { file ->
            val samples = AndroidAudioDecoder(context).decode(Uri.fromFile(file), 0L, WINDOW_MS)

            assertEquals(705_600, samples.size)
            assertTrue("Decoded pulses must survive downsampling", samples.any { abs(it) > 0.25f })
            assertTrue("Decoded PCM must contain finite, normalized samples", samples.all { it.isFinite() && abs(it) <= 1f })
        }
    }

    @Test
    fun highRatePairRunsTailSeekAndNativeInferenceWithoutStartingPlayback() = runBlocking {
        withFixture { file ->
            val analyzer = BeatNetAutoMixAnalyzer(context)
            try {
                val uri = Uri.fromFile(file)
                val pair = withTimeout(120_000) {
                    analyzer.analyzePair(
                        outgoingId = "beatnet-high-rate-outgoing",
                        outgoingUri = uri,
                        outgoingDurationMs = FIXTURE_DURATION_MS,
                        incomingId = "beatnet-high-rate-incoming",
                        incomingUri = uri,
                    )
                }

                assertAnalysis(pair.outgoing, FIXTURE_DURATION_MS - WINDOW_MS)
                assertAnalysis(pair.incoming, 0L)
            } finally {
                analyzer.close()
            }
        }
    }

    private fun assertAnalysis(analysis: BeatNetTrackAnalysis, startMs: Long) {
        assertTrue("Native inference must produce a finite tempo", analysis.bpm.isFinite() && analysis.bpm in 50.0..250.0)
        assertTrue("Native inference must produce finite confidence", analysis.confidence.isFinite() && analysis.confidence in 0.0..1.0)
        assertTrue("Temporal decoding must produce beat candidates", analysis.beatsMs.isNotEmpty())
        assertTrue("Beat candidates must stay inside the requested window", analysis.beatsMs.all { it in startMs until startMs + WINDOW_MS })
        assertTrue("Beat candidates must remain ordered", (1 until analysis.beatsMs.size).all { analysis.beatsMs[it] > analysis.beatsMs[it - 1] })
        assertTrue("Downbeats must be beat candidates", analysis.downbeatsMs.all { it in analysis.beatsMs })
    }

    private suspend fun withFixture(block: suspend (File) -> Unit) {
        // The 36-second fixture contains 10 ms square pulses every 4 seconds.
        // ffmpeg -f lavfi -i 'aevalsrc=if(lt(mod(t\,4)\,0.01)\,0.5\,0):s=192000:d=36'
        //   -ac 2 -sample_fmt s16 -c:a flac -compression_level 12 -frame_size 16384 -map_metadata -1 fixture.flac
        val file = File.createTempFile("beatnet-high-rate-", ".flac", context.cacheDir)
        try {
            instrumentation.context.assets.open("beatnet-192khz-stereo.flac").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                // Some platform FLAC extractors decode the track and expose PCM directly.
                assertTrue(format.getString(MediaFormat.KEY_MIME) in setOf("audio/flac", "audio/raw"))
                assertEquals(192_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertEquals(FIXTURE_DURATION_MS * 1_000, format.getLong(MediaFormat.KEY_DURATION))
            } finally {
                extractor.release()
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    companion object {
        private const val WINDOW_MS = 32_000L
        private const val FIXTURE_DURATION_MS = 36_000L
    }
}
