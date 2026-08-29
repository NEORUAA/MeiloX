package com.ljyh.mei.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.ljyh.mei.constants.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class BeatNetTrackAnalysis(
    val bpm: Double,
    val confidence: Double,
    val beatsMs: LongArray,
    val downbeatsMs: LongArray,
)

data class BeatNetPairAnalysis(
    val outgoing: BeatNetTrackAnalysis,
    val incoming: BeatNetTrackAnalysis,
)

internal object BeatNetNative {
    init {
        System.loadLibrary("beatnet_native")
    }

    @JvmStatic
    external fun predict(features: FloatArray): FloatArray?
}

data class SmartAutoMixPlan(
    val outgoingStartMs: Long,
    val incomingStartMs: Long,
    val durationMs: Long,
    val outgoingEndRate: Float,
    val incomingStartRate: Float,
    val confidence: Double,
)

/** Android port of MeloX's BeatNet feature, inference, and temporal-decoding pipeline. */
class BeatNetAutoMixAnalyzer(private val context: Context) : AutoCloseable {
    private data class CacheKey(val mediaId: String, val startMs: Long)

    private val featureExtractor = BeatNetFeatureExtractor()
    private val cache = object : LinkedHashMap<CacheKey, BeatNetTrackAnalysis>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, BeatNetTrackAnalysis>?) = size > 8
    }

    suspend fun analyzePair(
        outgoingId: String,
        outgoingUri: Uri,
        outgoingDurationMs: Long,
        incomingId: String,
        incomingUri: Uri,
    ): BeatNetPairAnalysis = withContext(Dispatchers.Default) {
        val outgoingStart = (outgoingDurationMs - WINDOW_DURATION_MS).coerceAtLeast(0)
        val outgoing = analyze(outgoingId, outgoingUri, outgoingStart)
        val incoming = analyze(incomingId, incomingUri, 0)
        BeatNetPairAnalysis(outgoing, incoming)
    }

    private suspend fun analyze(mediaId: String, uri: Uri, startMs: Long): BeatNetTrackAnalysis {
        val key = CacheKey(mediaId, startMs)
        synchronized(cache) { cache[key] }?.let { return it }
        val decoded = withContext(Dispatchers.IO) {
            AndroidAudioDecoder(context).decode(uri, startMs, WINDOW_DURATION_MS)
        }
        val features = featureExtractor.extract(decoded.samples, decoded.sampleRate)
        val activations = predict(features)
        return BeatNetTemporalDecoder.decode(activations, startMs).also {
            synchronized(cache) { cache[key] = it }
        }
    }

    private fun predict(features: FloatArray): Array<FloatArray> {
        require(features.size == BeatNetFeatureExtractor.FRAME_COUNT * BeatNetFeatureExtractor.FEATURE_COUNT)
        val flattened = checkNotNull(BeatNetNative.predict(features)) {
            "Native BeatNet inference failed"
        }
        check(flattened.size == BeatNetFeatureExtractor.FRAME_COUNT * 2) {
            "Native BeatNet returned ${flattened.size} values"
        }
        return Array(BeatNetFeatureExtractor.FRAME_COUNT) { frame ->
            floatArrayOf(flattened[frame * 2], flattened[frame * 2 + 1])
        }
    }

    override fun close() {
        synchronized(cache) { cache.clear() }
    }

    companion object {
        const val WINDOW_DURATION_MS = 32_000L

        fun makePlan(
            pair: BeatNetPairAnalysis,
            outgoingDurationMs: Long,
            transitionBars: Int,
            tailCutBars: Int,
            tempoMatching: Boolean,
            maximumTempoAdjustmentPercent: Float,
        ): SmartAutoMixPlan? {
            val confidence = min(pair.outgoing.confidence, pair.incoming.confidence)
            if (confidence < 0.08 || pair.outgoing.beatsMs.isEmpty() || pair.incoming.beatsMs.isEmpty()) return null
            val outgoingBpm = pair.outgoing.bpm.takeIf { it.isFinite() && it > 0 } ?: return null
            val incomingBpm = pair.incoming.bpm.takeIf { it.isFinite() && it > 0 } ?: return null
            val alignedIncomingBpm = listOf(incomingBpm / 2, incomingBpm, incomingBpm * 2)
                .minBy { abs(it - outgoingBpm) }
            val requestedRate = outgoingBpm / alignedIncomingBpm
            val mayMatch = tempoMatching && abs(requestedRate - 1) * 100 <= maximumTempoAdjustmentPercent
            val incomingRate = if (mayMatch) requestedRate.coerceIn(0.92, 1.08).toFloat() else 1f
            val outgoingRate = if (mayMatch) (alignedIncomingBpm / outgoingBpm).coerceIn(0.92, 1.08).toFloat() else 1f
            val outgoingBeatMs = 60_000.0 / outgoingBpm
            val incomingBeatMs = 60_000.0 / alignedIncomingBpm
            val requestedDuration = (transitionBars.coerceAtLeast(1) * 4 * (outgoingBeatMs + incomingBeatMs) / 2)
                .roundToInt().toLong().coerceIn(3_000L, 32_000L)
            val desiredEnd = (outgoingDurationMs - tailCutBars.coerceAtLeast(0) * 4 * outgoingBeatMs)
                .roundToInt().toLong().coerceAtLeast(requestedDuration)
            val targetStart = (desiredEnd - requestedDuration).coerceAtLeast(0L)
            val outgoingCandidates = if (pair.outgoing.downbeatsMs.isNotEmpty()) {
                pair.outgoing.downbeatsMs
            } else pair.outgoing.beatsMs
            val outgoingStart = outgoingCandidates.minByOrNull { abs(it - targetStart) } ?: targetStart
            val incomingCandidates = if (pair.incoming.downbeatsMs.isNotEmpty()) {
                pair.incoming.downbeatsMs
            } else pair.incoming.beatsMs
            val incomingStart = incomingCandidates.firstOrNull { it in 0L..8_000L } ?: 0L
            val duration = min(requestedDuration, (outgoingDurationMs - outgoingStart).coerceAtLeast(1_000L))
            return SmartAutoMixPlan(
                outgoingStartMs = outgoingStart,
                incomingStartMs = incomingStart,
                durationMs = duration,
                outgoingEndRate = outgoingRate,
                incomingStartRate = incomingRate,
                confidence = confidence,
            )
        }
    }
}

private data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

private class AndroidAudioDecoder(private val context: Context) {
    fun decode(uri: Uri, startMs: Long, durationMs: Long): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                extractor.setDataSource(uri.toString(), mapOf("User-Agent" to UserAgent))
            } else {
                extractor.setDataSource(context, uri, emptyMap())
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track is available for BeatNet analysis")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio codec is unknown")
            extractor.selectTrack(trackIndex)
            val startUs = startMs * 1_000
            val endUs = (startMs + durationMs) * 1_000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val chunks = ArrayList<FloatArray>()
            var totalSamples = 0
            var outputSampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var outputChannels = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input is unavailable")
                        val sampleTime = extractor.sampleTime
                        val size = if (sampleTime < 0 || sampleTime >= endUs) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputSampleRate = format.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                        outputChannels = format.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                        pcmEncoding = format.integerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0 && info.presentationTimeUs < endUs) {
                            val output = codec.getOutputBuffer(outputIndex)
                            if (output != null) {
                                val mono = output.toMonoFloat(info, outputChannels, pcmEncoding, outputSampleRate, startUs, endUs)
                                if (mono.isNotEmpty()) {
                                    chunks += mono
                                    totalSamples += mono.size
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(totalSamples > 0) { "Audio decoder returned no samples" }
            val joined = FloatArray(totalSamples)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(joined, offset)
                offset += chunk.size
            }
            return DecodedAudio(joined, outputSampleRate)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}

private fun ByteBuffer.toMonoFloat(
    info: MediaCodec.BufferInfo,
    channels: Int,
    encoding: Int,
    sampleRate: Int,
    requestedStartUs: Long,
    requestedEndUs: Long,
): FloatArray {
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 2
    }
    val channelCount = channels.coerceAtLeast(1)
    val frameCount = info.size / (bytesPerSample * channelCount)
    if (frameCount <= 0) return FloatArray(0)
    val skipFrames = if (info.presentationTimeUs < requestedStartUs) {
        ((requestedStartUs - info.presentationTimeUs) * sampleRate / 1_000_000).toInt().coerceIn(0, frameCount)
    } else 0
    val endFrames = if (info.presentationTimeUs + frameCount * 1_000_000L / sampleRate > requestedEndUs) {
        ((requestedEndUs - info.presentationTimeUs) * sampleRate / 1_000_000).toInt().coerceIn(skipFrames, frameCount)
    } else frameCount
    val source = duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
        position(info.offset + skipFrames * bytesPerSample * channelCount)
        limit(info.offset + endFrames * bytesPerSample * channelCount)
    }
    return FloatArray(endFrames - skipFrames) {
        var sum = 0f
        repeat(channelCount) {
            sum += when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> source.float.coerceIn(-1f, 1f)
                AudioFormat.ENCODING_PCM_8BIT -> (source.get().toInt() and 0xff).minus(128) / 128f
                else -> source.short / 32768f
            }
        }
        sum / channelCount
    }
}

private fun MediaFormat.integerOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private class BeatNetFeatureExtractor {
    private data class WeightedBin(val index: Int, val weight: Float)

    private val window = FloatArray(FRAME_SIZE) { index ->
        (0.5 - 0.5 * cos(2 * PI * index / FRAME_SIZE)).toFloat()
    }
    private val filters = makeFilters()

    fun extract(input: FloatArray, sourceRate: Int): FloatArray {
        val resampled = resample(input, sourceRate, SAMPLE_RATE)
        val requiredSamples = SAMPLE_RATE * WINDOW_SECONDS
        val samples = if (resampled.size == requiredSamples) resampled else FloatArray(requiredSamples).also {
            resampled.copyInto(it, endIndex = min(resampled.size, requiredSamples))
        }
        val values = FloatArray(FRAME_COUNT * FEATURE_COUNT)
        val previousBands = FloatArray(BAND_COUNT)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        for (frameIndex in 0 until FRAME_COUNT) {
            real.fill(0.0)
            imaginary.fill(0.0)
            val start = frameIndex * HOP_SIZE - FRAME_SIZE / 2
            for (windowIndex in 0 until FRAME_SIZE) {
                val sampleIndex = start + windowIndex
                if (sampleIndex in samples.indices) real[windowIndex] = (samples[sampleIndex] * window[windowIndex]).toDouble()
            }
            fft(real, imaginary)
            val magnitudes = DoubleArray(FFT_BIN_COUNT) { index ->
                sqrt(real[index] * real[index] + imaginary[index] * imaginary[index]) * 0.5
            }
            val offset = frameIndex * FEATURE_COUNT
            for (bandIndex in 0 until BAND_COUNT) {
                var magnitude = 0.0
                for (weighted in filters[bandIndex]) magnitude += magnitudes[weighted.index] * weighted.weight
                val logarithmic = log10(magnitude + 1).toFloat()
                values[offset + bandIndex] = logarithmic
                values[offset + BAND_COUNT + bandIndex] = max(logarithmic - previousBands[bandIndex], 0f)
                previousBands[bandIndex] = logarithmic
            }
        }
        return values
    }

    private fun makeFilters(): List<List<WeightedBin>> {
        val originalBinCount = 705
        val originalSpacing = SAMPLE_RATE.toDouble() / (originalBinCount * 2)
        val left = floor(log2(30.0 / 440.0) * 24).toInt()
        val right = ceil(log2(17_000.0 / 440.0) * 24).toInt()
        val originalBins = ArrayList<Int>()
        for (exponent in left until right) {
            val frequency = 440 * 2.0.pow(exponent / 24.0)
            if (frequency !in 30.0..17_000.0) continue
            val bin = (frequency / originalSpacing).roundToInt().coerceIn(1, originalBinCount - 1)
            if (originalBins.lastOrNull() != bin) originalBins += bin
        }
        val fftSpacing = SAMPLE_RATE.toDouble() / FFT_SIZE
        val result = (0 until originalBins.size - 2).map { index ->
            val start = (originalBins[index] * originalSpacing / fftSpacing).roundToInt()
            val center = (originalBins[index + 1] * originalSpacing / fftSpacing).roundToInt()
            val stop = (originalBins[index + 2] * originalSpacing / fftSpacing).roundToInt()
            val raw = (start until min(stop, FFT_BIN_COUNT)).mapNotNull { bin ->
                val weight = if (bin < center) (bin - start).toFloat() / max(center - start, 1)
                else (stop - bin).toFloat() / max(stop - center, 1)
                weight.takeIf { it > 0 }?.let { WeightedBin(bin, it) }
            }
            val sum = raw.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(Float.MIN_VALUE)
            raw.map { WeightedBin(it.index, it.weight / sum) }
        }
        check(result.size == BAND_COUNT) { "BeatNet filter bank has ${result.size} bands instead of $BAND_COUNT" }
        return result
    }

    private fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate) return input
        val count = floor(input.size.toDouble() * targetRate / sourceRate).toInt()
        return FloatArray(count) { index ->
            val position = index.toDouble() * sourceRate / targetRate
            val left = floor(position).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (position - left).toFloat()
            input[left] * (1 - fraction) + input[right] * fraction
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = ti
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImaginary = sin(angle)
            var start = 0
            while (start < FFT_SIZE) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wr - imaginary[odd] * wi
                    val oddImaginary = real[odd] * wi + imaginary[odd] * wr
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextWr = wr * wLengthReal - wi * wLengthImaginary
                    wi = wr * wLengthImaginary + wi * wLengthReal
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val FRAME_COUNT = 1_600
        const val FEATURE_COUNT = 272
        private const val WINDOW_SECONDS = 32
        private const val FRAME_SIZE = 1_411
        private const val HOP_SIZE = 441
        private const val FFT_SIZE = 2_048
        private const val FFT_BIN_COUNT = FFT_SIZE / 2
        private const val BAND_COUNT = FEATURE_COUNT / 2
        private fun log2(value: Double) = ln(value) / ln(2.0)
    }
}

private object BeatNetTemporalDecoder {
    private const val FRAMES_PER_SECOND = 50.0

    fun decode(activations: Array<FloatArray>, regionStartMs: Long): BeatNetTrackAnalysis {
        require(activations.size == BeatNetFeatureExtractor.FRAME_COUNT)
        val beats = FloatArray(activations.size) { activations[it][0] }
        val downbeats = FloatArray(activations.size) { activations[it][1] }
        val lags = 12..55
        val scores = FloatArray(lags.count()) { autocorrelation(beats, lags.first + it) }
        var bestLag = lags.first + scores.indices.maxBy { scores[it] }
        val bpm = FRAMES_PER_SECOND * 60 / bestLag
        if (bpm > 180 && bestLag * 2 in lags && score(scores, bestLag * 2, lags) >= score(scores, bestLag, lags) * 0.86f) {
            bestLag *= 2
        } else if (bpm < 75 && bestLag / 2 in lags && score(scores, bestLag / 2, lags) >= score(scores, bestLag, lags) * 0.9f) {
            bestLag /= 2
        }
        val phase = (0 until bestLag).maxBy { candidate ->
            var sum = 0f
            var frame = candidate
            while (frame < beats.size) { sum += beats[frame]; frame += bestLag }
            sum
        }
        val beatFrames = ArrayList<Int>()
        var nominal = phase
        while (nominal < beats.size) {
            val frame = ((nominal - 2).coerceAtLeast(0)..(nominal + 2).coerceAtMost(beats.lastIndex)).maxBy { beats[it] }
            if (beatFrames.lastOrNull() != frame) beatFrames += frame
            nominal += bestLag
        }
        val downbeatPhase = (0 until 4).maxBy { candidate ->
            var sum = 0f
            var index = candidate
            while (index < beatFrames.size) { sum += downbeats[beatFrames[index]]; index += 4 }
            sum
        }
        val downbeatFrames = beatFrames.filterIndexed { index, _ -> index % 4 == downbeatPhase }
        val background = beats.average().toFloat()
        val beatMean = beatFrames.sumOf { beats[it].toDouble() }.toFloat() / beatFrames.size.coerceAtLeast(1)
        val downbeatMean = downbeatFrames.sumOf { downbeats[it].toDouble() }.toFloat() / downbeatFrames.size.coerceAtLeast(1)
        val contrast = max((beatMean - background) / max(1 - background, 0.01f), 0f)
        val correlation = score(scores, bestLag, lags).coerceAtLeast(0f)
        val confidence = (contrast * 0.5f + correlation * 0.35f + downbeatMean * 0.15f).coerceIn(0f, 1f).toDouble()
        fun frameToMs(frame: Int) = regionStartMs + (frame * 1_000 / FRAMES_PER_SECOND).roundToInt()
        return BeatNetTrackAnalysis(
            bpm = FRAMES_PER_SECOND * 60 / bestLag,
            confidence = confidence,
            beatsMs = beatFrames.map(::frameToMs).toLongArray(),
            downbeatsMs = downbeatFrames.map(::frameToMs).toLongArray(),
        )
    }

    private fun autocorrelation(values: FloatArray, lag: Int): Float {
        var numerator = 0f
        var leftEnergy = 0f
        var rightEnergy = 0f
        for (index in lag until values.size) {
            val left = values[index]
            val right = values[index - lag]
            numerator += left * right
            leftEnergy += left * left
            rightEnergy += right * right
        }
        return numerator / max(sqrt(leftEnergy * rightEnergy), Float.MIN_VALUE)
    }

    private fun score(scores: FloatArray, lag: Int, range: IntRange) = scores[lag - range.first]
}
