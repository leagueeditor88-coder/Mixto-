package com.example.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline estimate of musical key from decoded PCM using FFT chroma and major/minor profiles. */
object KeyDetector {
    private const val ANALYSIS_SECONDS = 25
    private const val FFT_SIZE = 4096
    private const val TARGET_RATE = 11025
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val majorProfile = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minorProfile = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun detect(context: Context, uri: Uri): String? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val mono = ArrayList<Double>(TARGET_RATE * ANALYSIS_SECONDS)
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val maxUs = (extractor.sampleTime.coerceAtLeast(0L)) + ANALYSIS_SECONDS * 1_000_000L
            var sampleCounter = 0
            val downsample = max(1, sampleRate / TARGET_RATE)
            while (!outputDone && mono.size < TARGET_RATE * ANALYSIS_SECONDS) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        val timeUs = extractor.sampleTime
                        if (timeUs < 0 || timeUs >= maxUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) { decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(inputIndex, 0, size, timeUs, 0); extractor.advance() }
                        }
                    }
                }
                when (val oi = decoder.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (oi >= 0) {
                        val output = decoder.getOutputBuffer(oi)
                        if (output != null && info.size > 0) {
                            output.position(info.offset); output.limit(info.offset + info.size); output.order(ByteOrder.nativeOrder())
                            var channelSum = 0.0; var channelCount = 0
                            while (output.remaining() >= 2 && mono.size < TARGET_RATE * ANALYSIS_SECONDS) {
                                channelSum += output.short.toDouble(); channelCount++
                                if (channelCount >= channels) {
                                    if (sampleCounter % downsample == 0) mono.add(channelSum / channelCount)
                                    sampleCounter++; channelSum = 0.0; channelCount = 0
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(oi, false)
                    }
                }
            }
            return estimate(mono, sampleRate / max(1, sampleRate / TARGET_RATE))
        } catch (_: Exception) { return null }
        finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun estimate(samples: List<Double>, actualRate: Int): String? {
        if (samples.size < FFT_SIZE * 3) return null
        val chroma = DoubleArray(12)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val hop = FFT_SIZE / 2
        var frames = 0
        var start = 0
        while (start + FFT_SIZE <= samples.size) {
            var mean = 0.0
            for (i in 0 until FFT_SIZE) mean += samples[start + i]
            mean /= FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
                real[i] = (samples[start + i] - mean) * window
                imag[i] = 0.0
            }
            fft(real, imag)
            for (bin in 2 until FFT_SIZE / 2) {
                val hz = bin.toDouble() * actualRate / FFT_SIZE
                if (hz < 55.0 || hz > 1600.0) continue
                val mag = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                val midi = (69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)).toInt()
                val note = ((midi % 12) + 12) % 12
                chroma[note] += mag
            }
            frames++; start += hop
        }
        if (frames < 2 || (chroma.maxOrNull() ?: 0.0) < 1.0) return null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestNote = -1
        var bestMajor = true
        for (root in 0 until 12) {
            for (isMajor in listOf(true, false)) {
                val profile = if (isMajor) majorProfile else minorProfile
                var score = 0.0
                for (i in 0 until 12) score += profile[i] * chroma[(i + root) % 12]
                // Prefer a normalized profile score so loudness does not dominate.
                val norm = sqrt(profile.sumOf { it * it } * chroma.sumOf { it * it }) + 1e-9
                score /= norm
                if (score > bestScore) { bestScore = score; bestNote = root; bestMajor = isMajor }
            }
        }
        if (bestNote < 0) return null
        return "${noteNames[bestNote]} ${if (bestMajor) "major" else "minor"}"
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle); val wLenI = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]; val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vI = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = uR + vR; imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR; imag[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR; wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
