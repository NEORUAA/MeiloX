package com.ljyh.mei.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import com.ljyh.mei.constants.MusicQuality
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQualityRecoveryTest {
    @Test
    fun confirmedLoadingAndPlayingStallsRecoverForBothMedia3ErrorCodes() {
        val stuckTypes = listOf(
            StuckPlayerException.STUCK_BUFFERING_NOT_LOADING,
            StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
            StuckPlayerException.STUCK_PLAYING_NO_PROGRESS,
        )
        val errorCodes = listOf(
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        )

        errorCodes.forEach { errorCode ->
            stuckTypes.forEach { stuckType ->
                val cause = StuckPlayerException(stuckType, 30_000)
                assertTrue(shouldRecoverStalledPlayback(errorCode, cause))
                assertTrue(shouldRecoverStalledPlayback(errorCode, IllegalStateException(cause)))
            }
        }
    }

    @Test
    fun suppressedAndNotEndingStallsMustNotRestartTheCurrentSource() {
        listOf(
            StuckPlayerException.STUCK_SUPPRESSED,
            StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
        ).forEach { stuckType ->
            listOf(
                PlaybackException.ERROR_CODE_TIMEOUT,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            ).forEach { errorCode ->
                assertFalse(shouldRecoverStalledPlayback(
                    errorCode, StuckPlayerException(stuckType, 30_000),
                ))
            }
        }
    }

    @Test
    fun ordinaryTimeoutsRuntimeChecksAndNetworkFailuresMustNotTriggerStallRecovery() {
        val ordinaryCauses = listOf(
            null,
            SocketTimeoutException("Player stuck buffering with no progress"),
            IllegalStateException("STUCK_PLAYING_NO_PROGRESS"),
            IllegalStateException(IOException("Player stuck buffering and not loading")),
        )
        listOf(
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        ).forEach { errorCode ->
            ordinaryCauses.forEach { cause ->
                assertFalse(shouldRecoverStalledPlayback(errorCode, cause))
            }
        }
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        ).forEach { errorCode ->
            assertFalse(shouldRecoverStalledPlayback(
                errorCode,
                StuckPlayerException(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, 30_000),
            ))
        }
    }

    @Test
    fun masterSourceDecoderFailuresCanRecoverWithAnotherQuality() {
        val sourceFailures = listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )

        sourceFailures.forEach { errorCode ->
            assertTrue("A source decoder failure must allow recovery: $errorCode", shouldTryLowerPlaybackQuality(errorCode))
        }
    }

    @Test
    fun malformedAudioAndUnsupportedOutputCanRecoverWithoutAdvancingTheQueue() {
        val sourceFailures = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        )

        sourceFailures.forEach { errorCode ->
            assertTrue("A source format or output failure must allow recovery: $errorCode", shouldTryLowerPlaybackQuality(errorCode))
        }
    }

    @Test
    fun networkAndAuthenticationFailuresMustNotSilentlyReduceQuality() {
        val transientOrAccessFailures = listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )

        transientOrAccessFailures.forEach { errorCode ->
            assertFalse("A network or access failure must retain the selected quality: $errorCode", shouldTryLowerPlaybackQuality(errorCode))
        }
    }

    @Test
    fun staleRangeFailureRefreshesTheSourceInsteadOfDowngrading() {
        val errorCode = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

        assertTrue(shouldRefreshPlaybackSource(errorCode))
        assertFalse(shouldTryLowerPlaybackQuality(errorCode))
        assertFalse(shouldTryLowerPlaybackQuality(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun fallbackAttemptsAreBoundedAndCannotRepeatTheRequestedLevel() {
        MusicQuality.entries.forEach { quality ->
            val fallbacks = playbackQualityFallbacks(" ${quality.text.uppercase()} ")

            assertEquals("The user selection must be attempted first", quality.text, fallbacks.first())
            assertEquals("Every attempt must be unique", fallbacks.toSet().size, fallbacks.size)
            assertTrue("Recovery must stop after the finite quality catalog", fallbacks.size <= MusicQuality.entries.size)
            assertEquals("The final full-source fallback must be standard", MusicQuality.STANDARD.text, fallbacks.last())
        }
    }
}
