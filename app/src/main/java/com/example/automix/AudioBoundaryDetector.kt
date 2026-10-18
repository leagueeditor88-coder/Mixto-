package com.example.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Estima el comienzo y el final musical a partir de ventanas de energía PCM.
 * Es análisis offline: no modifica ni controla el reproductor.
 * Devuelve null si el archivo no puede decodificarse o no hay señal suficiente.
 */
object AudioBoundaryDetector {
    data class SignalAnalysis(
        val boundaries: Boundaries,
        /** Envolvente RMS normalizada por ventanas de 50 ms. */
        val energyLevels: List<Double>,
        val windowMs: Int = WINDOW_MS
    )

    data class Boundaries(
        /** Primer punto con señal sostenida; sirve como estimación del intro silencioso. */
        val audibleStartMs: Long,
        /** Inicio estimado del silencio final; si no existe, queda cerca del final real. */
        val audibleEndMs: Long,
        /** Duración total conocida del audio. */
        val durationMs: Long
    ) {
        val leadingSilenceMs: Long get() = audibleStartMs.coerceAtLeast(0L)
        val trailingSilenceMs: Long get() = (durationMs - audibleEndMs).coerceAtLeast(0L)
    }

    private const val WINDOW_MS = 50
    private const val MAX_ANALYSIS_SECONDS = 15 * 60
    private const val SILENCE_FLOOR = 0.0008
    private const val RELATIVE_THRESHOLD = 0.025
    private const val REQUIRED_ACTIVE_WINDOWS = 3

    fun detect(context: Context, uri: Uri): Boundaries? = analyze(context, uri)?.boundaries

    /** Decodifica una sola vez y comparte RMS con el detector de límites y el analizador estructural. */
    fun analyze(context: Context, uri: Uri): SignalAnalysis? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val levels = ArrayList<Double>()
        var durationUs = 0L
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = candidate
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

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var samplesPerWindow = max(1, sampleRate * channels * WINDOW_MS / 1000)
            var samplesInWindow = 0
            var sumSquares = 0.0
            val maxUs = minOf(durationUs, MAX_ANALYSIS_SECONDS * 1_000_000L)

            while (!outputDone && levels.size < MAX_ANALYSIS_SECONDS * 1000 / WINDOW_MS) {
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
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        samplesPerWindow = max(1, sampleRate * channels * WINDOW_MS / 1000)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())
                            // Android decoders normally emit PCM 16-bit unless output format says otherwise.
                            val encoding = if (decoder.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                decoder.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                            if (encoding == 4) {
                                while (output.remaining() >= 4) {
                                    val sample = output.float.toDouble().coerceIn(-1.0, 1.0)
                                    sumSquares += sample * sample
                                    samplesInWindow++
                                    if (samplesInWindow >= samplesPerWindow) {
                                        levels.add(kotlin.math.sqrt(sumSquares / samplesInWindow))
                                        samplesInWindow = 0
                                        sumSquares = 0.0
                                    }
                                }
                            } else {
                                while (output.remaining() >= 2) {
                                    val sample = output.short.toDouble() / 32768.0
                                    sumSquares += sample * sample
                                    samplesInWindow++
                                    if (samplesInWindow >= samplesPerWindow) {
                                        levels.add(kotlin.math.sqrt(sumSquares / samplesInWindow))
                                        samplesInWindow = 0
                                        sumSquares = 0.0
                                    }
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (samplesInWindow > 0) levels.add(kotlin.math.sqrt(sumSquares / samplesInWindow))
            val durationMs = durationUs / 1000L
            val boundaries = estimate(levels, durationMs) ?: return null
            return SignalAnalysis(boundaries, levels.toList(), WINDOW_MS)
        } catch (_: Exception) {
            return null
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    internal fun estimate(levels: List<Double>, durationMs: Long): Boundaries? {
        if (levels.size < 10 || durationMs <= 0L) return null
        val peak = levels.maxOrNull() ?: return null
        if (!peak.isFinite() || peak < SILENCE_FLOOR) return null
        val threshold = max(SILENCE_FLOOR, peak * RELATIVE_THRESHOLD)
        val active = BooleanArray(levels.size) { levels[it] >= threshold }

        var first = -1
        var run = 0
        for (i in active.indices) {
            run = if (active[i]) run + 1 else 0
            if (run >= REQUIRED_ACTIVE_WINDOWS) {
                first = i - REQUIRED_ACTIVE_WINDOWS + 1
                break
            }
        }
        if (first < 0) return null

        var last = -1
        run = 0
        for (i in active.indices.reversed()) {
            run = if (active[i]) run + 1 else 0
            if (run >= REQUIRED_ACTIVE_WINDOWS) {
                last = i + REQUIRED_ACTIVE_WINDOWS - 1
                break
            }
        }
        if (last < first) return null

        val startMs = (first * WINDOW_MS.toLong()).coerceIn(0L, durationMs)
        val endMs = ((last + 1) * WINDOW_MS.toLong()).coerceIn(startMs, durationMs)
        return Boundaries(startMs, endMs, durationMs)
    }
}
