package com.example.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Analizador offline de nivel medio y pico PCM.
 * No modifica el audio ni el reproductor. Su salida sirve para una compensación
 * moderada de ganancia; no pretende ser una medición LUFS conforme a EBU R128.
 */
object AudioLoudnessAnalyzer {
    data class Result(
        val rms: Double,
        val peak: Double,
        /** Ganancia lineal limitada para no amplificar excesivamente pistas silenciosas. */
        val gain: Float
    )

    private const val ANALYSIS_SECONDS = 30
    private const val WINDOW_MS = 100
    private const val TARGET_RMS = 0.12
    private const val PEAK_CEILING = 0.88
    private const val MIN_RMS = 0.003
    private const val MIN_GAIN = 0.65
    private const val MAX_GAIN = 1.0

    fun analyze(context: Context, uri: Uri): Result? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var totalSquares = 0.0
        var totalSamples = 0L
        var peak = 0.0
        var durationUs = 0L
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) return null
            durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) else 0L
            if (durationUs <= 0L) return null

            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var inputDone = false
            var outputDone = false
            val maxUs = min(durationUs, ANALYSIS_SECONDS * 1_000_000L)
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, maxUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            input.clear()
                            val timeUs = extractor.sampleTime
                            if (timeUs < 0L || timeUs >= maxUs) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, maxUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val size = extractor.readSampleData(input, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(inputIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inputIndex, 0, size, timeUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            val encoding = if (decoder.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                decoder.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                            if (encoding == 4) {
                                while (output.remaining() >= 4) {
                                    val sample = output.float.toDouble().coerceIn(-1.0, 1.0)
                                    totalSquares += sample * sample
                                    peak = max(peak, kotlin.math.abs(sample))
                                    totalSamples++
                                }
                            } else {
                                while (output.remaining() >= 2) {
                                    val sample = output.short.toDouble() / 32768.0
                                    totalSquares += sample * sample
                                    peak = max(peak, kotlin.math.abs(sample))
                                    totalSamples++
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (totalSamples < 1000L || !peak.isFinite() || peak <= 0.0001) return null
            val rms = sqrt(totalSquares / totalSamples)
            if (!rms.isFinite() || rms < MIN_RMS) return null

            // Este primer bloque solo atenúa pistas fuertes. ExoPlayer.volume no permite
            // amplificación > 1.0 de forma segura; las pistas silenciosas no se amplifican.
            val rmsGain = TARGET_RMS / rms
            val peakGain = if (peak > 0.0) PEAK_CEILING / peak else MAX_GAIN
            val gain = min(min(rmsGain, peakGain), MAX_GAIN).coerceIn(MIN_GAIN, MAX_GAIN)
            return Result(rms, peak, gain.toFloat())
        } catch (_: Exception) {
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
