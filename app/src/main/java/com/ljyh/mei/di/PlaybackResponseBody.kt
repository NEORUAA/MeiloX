package com.ljyh.mei.di

import okhttp3.ResponseBody
import java.io.IOException

internal const val NETEASE_EAPI_PROFILE_HEADER = "X-Netease-Eapi-Profile"
internal const val PLAYBACK_HISTORY_PROFILE = "playback-history"
internal const val MAX_PLAYBACK_HISTORY_RESPONSE_BYTES = 16_384

internal class PlaybackResponseBodyException(
    val httpStatus: Int,
    val failureKind: String,
    val failureReason: String,
    cause: IOException? = null,
) : IOException("Playback response $failureKind", cause)

internal fun readBoundedPlaybackResponseBody(
    body: ResponseBody,
    httpStatus: Int,
): ByteArray {
    return try {
        body.use {
            if (it.contentLength() > MAX_PLAYBACK_HISTORY_RESPONSE_BYTES) {
                throw playbackBodyTooLarge(httpStatus)
            }
            val source = it.source()
            if (source.request(MAX_PLAYBACK_HISTORY_RESPONSE_BYTES + 1L) ||
                source.buffer.size > MAX_PLAYBACK_HISTORY_RESPONSE_BYTES
            ) {
                throw playbackBodyTooLarge(httpStatus)
            }
            source.buffer.readByteArray(source.buffer.size)
        }
    } catch (error: PlaybackResponseBodyException) {
        throw error
    } catch (error: IOException) {
        throw PlaybackResponseBodyException(
            httpStatus = httpStatus,
            failureKind = "ResponseBodyReadFailure",
            failureReason = "response body read failed",
            cause = error,
        )
    }
}

internal fun playbackBodyTooLarge(httpStatus: Int) = PlaybackResponseBodyException(
    httpStatus = httpStatus,
    failureKind = "ResponseBodyTooLarge",
    failureReason = "response body exceeds ${MAX_PLAYBACK_HISTORY_RESPONSE_BYTES} bytes",
)
