package com.example.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Estima una rejilla de beats usando energía PCM y periodicidad de los ataques.
 * Es una estimación offline; no altera el audio ni garantiza downbeats perfectos.
 */
object BeatGridDetector {
    data class BeatGrid(
        val bpm: Double,
        /** Fase estimada del primer beat dentro del audio, en milisegundos. */
        val firstBeatMs: Long,
        /** Confianza relativa del patrón periódico (0..1). */
        val confidence: Double
    ) {
        val beatPeriodMs: Double get() = 60_000.0 / bpm
    }

    private const val WINDOW_MS = 50
    private const val ANALYSIS_SECONDS = 30

    fun detect(context: Context, uri: Uri): BeatGrid? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val levels = ArrayList<Double>(ANALYSIS_SECONDS * 1000 / WINDOW_MS)
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; inputFormat = f; break }
            }
            if (trackIndex < 0 || inputFormat == null) return null
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(trackIndex)
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME) ?: return null)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var samplesPerWindow = max(1, sampleRate * channels * WINDOW_MS / 1000)
            var samples = 0
            var sumSquares = 0.0
            val maxUs = minOf(if (durationUs > 0L) durationUs else ANALYSIS_SECONDS * 1_000_000L, ANALYSIS_SECONDS * 1_000_000L)

            while (!outputDone && levels.size < ANALYSIS_SECONDS * 1000 / WINDOW_MS) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        samplesPerWindow = max(1, sampleRate * channels * WINDOW_MS / 1000)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset); output.limit(info.offset + info.size); output.order(ByteOrder.nativeOrder())
                            val encoding = if (decoder.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoder.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                            if (encoding == 4) {
                                while (output.remaining() >= 4 && levels.size < ANALYSIS_SECONDS * 1000 / WINDOW_MS) {
                                    val v = output.float.toDouble().coerceIn(-1.0, 1.0)
                                    sumSquares += v * v; samples++
                                    if (samples >= samplesPerWindow) { levels.add(sqrt(sumSquares / samples)); samples = 0; sumSquares = 0.0 }
                                }
                            } else {
                                while (output.remaining() >= 2 && levels.size < ANALYSIS_SECONDS * 1000 / WINDOW_MS) {
                                    val v = output.short.toDouble() / 32768.0
                                    sumSquares += v * v; samples++
                                    if (samples >= samplesPerWindow) { levels.add(sqrt(sumSquares / samples)); samples = 0; sumSquares = 0.0 }
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (samples > 0) levels.add(sqrt(sumSquares / samples))
            return estimate(levels)
        } catch (_: Exception) {
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    internal fun estimate(levels: List<Double>): BeatGrid? {
        if (levels.size < 200) return null
        val onset = DoubleArray(levels.size)
        var onsetMean = 0.0
        for (i in 1 until levels.size) {
            onset[i] = max(0.0, levels[i] - levels[i - 1])
            onsetMean += onset[i]
        }
        onsetMean /= levels.size
        if (onsetMean < 0.00001) return null
        for (i in onset.indices) onset[i] -= onsetMean

        // Ventana de 50 ms: BPM 60..180 equivale aproximadamente a 7..20 frames.
        val minLag = (60_000.0 / 180.0 / WINDOW_MS).toInt().coerceAtLeast(1)
        val maxLag = (60_000.0 / 60.0 / WINDOW_MS).toInt()
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var cross = 0.0; var normA = 0.0; var normB = 0.0
            for (i in lag until onset.size) {
                val a = onset[i]; val b = onset[i - lag]
                cross += a * b; normA += a * a; normB += b * b
            }
            val score = cross / (sqrt(normA * normB) + 1e-12)
            if (score > bestScore) { secondScore = bestScore; bestScore = score; bestLag = lag }
            else if (score > secondScore) secondScore = score
        }
        if (bestLag <= 0 || bestScore < 0.08) return null

        // Busca la fase que concentra más ataques en la rejilla periódica.
        var bestPhase = 0
        var bestPhaseScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until bestLag) {
            var score = 0.0
            var i = phase
            while (i < onset.size) { score += max(0.0, onset[i]); i += bestLag }
            if (score > bestPhaseScore) { bestPhaseScore = score; bestPhase = phase }
        }
        val confidence = ((bestScore - max(0.0, secondScore)) / (abs(bestScore) + 1e-9)).coerceIn(0.0, 1.0)
        if (confidence < 0.015) return null
        val bpm = 60_000.0 / (bestLag * WINDOW_MS)
        return BeatGrid(bpm, bestPhase.toLong() * WINDOW_MS, confidence)
    }
}
