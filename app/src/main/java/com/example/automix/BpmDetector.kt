package com.example.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lightweight offline BPM estimate from decoded audio energy/onset autocorrelation.
 * Returns null when the format cannot be decoded or there is not enough rhythmic signal.
 */
object BpmDetector {
    private const val WINDOW_SECONDS = 30
    private const val ENVELOPE_HZ = 100

    fun detect(context: Context, uri: Uri): Int? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val envelope = ArrayList<Double>(WINDOW_SECONDS * ENVELOPE_HZ)
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
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
            var samplesPerWindow = max(1, sampleRate * channels / ENVELOPE_HZ)
            var inWindow = 0
            var energySum = 0.0
            val startUs = extractor.sampleTime.coerceAtLeast(0L)
            val maxUs = startUs + WINDOW_SECONDS * 1_000_000L

            while (!outputDone && envelope.size < WINDOW_SECONDS * ENVELOPE_HZ) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        val timeUs = extractor.sampleTime
                        if (timeUs < 0 || timeUs >= maxUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        samplesPerWindow = max(1, sampleRate * channels / ENVELOPE_HZ)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            // Most Android audio decoders output signed 16-bit PCM.
                            while (output.remaining() >= 2 && envelope.size < WINDOW_SECONDS * ENVELOPE_HZ) {
                                val sample = output.short.toInt()
                                energySum += abs(sample).toDouble()
                                inWindow++
                                if (inWindow >= samplesPerWindow) {
                                    envelope.add(energySum / inWindow)
                                    inWindow = 0
                                    energySum = 0.0
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (inWindow > 0 && envelope.size < WINDOW_SECONDS * ENVELOPE_HZ) envelope.add(energySum / inWindow)
            return estimate(envelope)
        } catch (_: Exception) {
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun estimate(energy: List<Double>): Int? {
        if (energy.size < 500) return null
        // Positive spectral-flux-like changes in short-window energy emphasize beat attacks.
        val onset = DoubleArray(energy.size)
        var mean = 0.0
        for (i in 1 until energy.size) {
            onset[i] = max(0.0, energy[i] - energy[i - 1])
            mean += onset[i]
        }
        mean /= onset.size
        if (mean < 1.0) return null
        for (i in onset.indices) onset[i] -= mean

        // Search 60-180 BPM at 100 envelope frames/second.
        val minLag = (ENVELOPE_HZ * 60.0 / 180.0).toInt()
        val maxLag = (ENVELOPE_HZ * 60.0 / 60.0).toInt()
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var score = 0.0
            var normA = 0.0
            var normB = 0.0
            var i = lag
            while (i < onset.size) {
                score += onset[i] * onset[i - lag]
                normA += onset[i] * onset[i]
                normB += onset[i - lag] * onset[i - lag]
                i++
            }
            val normalized = score / (kotlin.math.sqrt(normA * normB) + 1e-9)
            if (normalized > bestScore) {
                bestScore = normalized
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore < 0.02) return null
        val bpm = (60.0 * ENVELOPE_HZ / bestLag).toInt()
        return bpm.takeIf { it in 60..180 }
    }
}
