package com.example.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Planificador adaptativo de salida para AUTOMIX.
 *
 * Combina tres fuentes de decisión: candidatos de energía/estructura, alineación
 * rítmica (si la rejilla es fiable) y un límite de recuperación para no dejar que
 * la canción llegue al final esperando una oportunidad musical perfecta.
 *
 * La envolvente RMS no detecta voces ni identifica con certeza versos/estribillos;
 * por eso la decisión usa puntuaciones heurísticas, no etiquetas musicales absolutas.
 */
internal object SmartTransitionDecisionEngine {
    enum class Reason { STRUCTURE_CANDIDATE, BEAT_ALIGNED_CANDIDATE, SAFE_FALLBACK, SEARCH_WINDOW_EXPIRED }

    data class Decision(
        val transitionAtMs: Int,
        val fadeMs: Int,
        val score: Double,
        val reason: Reason,
        val safetyDeadlineMs: Long,
        val consideredCandidates: Int
    )

    fun decide(
        durationMs: Long,
        audibleStartMs: Long,
        audibleEndMs: Long,
        currentPositionMs: Long,
        searchStartMs: Long,
        requestedFadeMs: Int,
        safetyMarginMs: Long = 1200L,
        structure: TrackStructureAnalyzer.Analysis?,
        outgoingGrid: BeatGridDetector.BeatGrid?,
        incomingGrid: BeatGridDetector.BeatGrid?
    ): Decision {
        val duration = durationMs.coerceAtLeast(1L)
        val start = audibleStartMs.coerceIn(0L, duration)
        val end = audibleEndMs.coerceIn(start, duration)
        val activeLength = (end - start).coerceAtLeast(1L)
        val fade = min(requestedFadeMs.coerceAtLeast(0).toLong(), activeLength / 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val now = currentPositionMs.coerceIn(start, end)
        val earliest = max(searchStartMs, now + 150L).coerceAtMost(end)
        val deadline = (end - fade.toLong() - safetyMarginMs).coerceAtLeast(earliest)

        val analysis = structure?.takeIf { it.durationMs > 0L }
        val candidates = analysis?.transitionCandidatesMs.orEmpty()
            .asSequence()
            .map { it.coerceIn(start, end) }
            .filter { it >= earliest && it <= deadline }
            .distinct()
            .toList()

        if (candidates.isEmpty()) {
            val reason = if (now >= searchStartMs) Reason.SEARCH_WINDOW_EXPIRED else Reason.SAFE_FALLBACK
            return Decision(deadline.toIntSafe(), fade, 0.0, reason, deadline, 0)
        }

        val maxEnergy = analysis?.regions?.maxOfOrNull { it.meanEnergy }?.coerceAtLeast(1e-9) ?: 1.0
        val tempoRatio = if (validGrid(outgoingGrid) && validGrid(incomingGrid)) {
            max(outgoingGrid!!.bpm, incomingGrid!!.bpm) / min(outgoingGrid.bpm, incomingGrid.bpm)
        } else null
        val tempoBonus = when {
            tempoRatio == null -> 0.0
            tempoRatio <= 1.06 -> 0.55
            tempoRatio <= 1.12 -> 0.15
            else -> -0.35
        }

        var bestAt = candidates.first()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestBeatAligned = false
        for (candidate in candidates) {
            val region = analysis?.regions?.firstOrNull { candidate >= it.startMs && candidate <= it.endMs }
            var score = when (region?.type) {
                TrackStructureAnalyzer.RegionType.LOW_ENERGY_BREAK -> 2.8 + region.confidence * 1.5
                TrackStructureAnalyzer.RegionType.ENERGY_CHANGE -> 1.7 + region.confidence
                TrackStructureAnalyzer.RegionType.STABLE -> 0.15
                TrackStructureAnalyzer.RegionType.BUILD_UP -> -0.65
                TrackStructureAnalyzer.RegionType.HIGH_ENERGY -> -1.45
                null -> 0.0
            }
            if (region != null) {
                // Favorece energía relativamente baja sin depender de un umbral absoluto.
                score += ((maxEnergy - region.meanEnergy.coerceAtLeast(0.0)) / maxEnergy).coerceIn(0.0, 1.0) * 0.8
            }
            score += tempoBonus

            val beatAligned = beatAlignmentBonus(candidate, outgoingGrid)
            score += beatAligned

            // Un candidato más temprano deja más margen para recuperar si la estimación
            // de estructura es imperfecta; evita favorecer el último milisegundo disponible.
            val slack = (deadline - candidate).coerceAtLeast(0L).toDouble()
            score += (slack / max(1.0, (deadline - earliest).toDouble())).coerceIn(0.0, 1.0) * 0.25

            if (score > bestScore || (abs(score - bestScore) < 1e-6 && candidate < bestAt)) {
                bestScore = score
                bestAt = candidate
                bestBeatAligned = beatAligned >= 0.6
            }
        }

        return Decision(
            transitionAtMs = bestAt.toIntSafe(),
            fadeMs = fade,
            score = bestScore,
            reason = if (bestBeatAligned) Reason.BEAT_ALIGNED_CANDIDATE else Reason.STRUCTURE_CANDIDATE,
            safetyDeadlineMs = deadline,
            consideredCandidates = candidates.size
        )
    }

    private fun validGrid(grid: BeatGridDetector.BeatGrid?): Boolean =
        grid != null && grid.confidence >= 0.12 && grid.bpm.isFinite() && grid.bpm in 60.0..180.0 &&
            grid.beatPeriodMs.isFinite() && grid.beatPeriodMs > 0.0

    /** Puntuación por cercanía a beat/compás, sin desplazar el punto aquí. */
    private fun beatAlignmentBonus(candidateMs: Long, grid: BeatGridDetector.BeatGrid?): Double {
        if (!validGrid(grid)) return 0.0
        grid!!
        val useBar = grid.barConfidence >= 0.25 && grid.barOffsetBeats in 0..3
        val unit = if (useBar) grid.beatPeriodMs * 4.0 else grid.beatPeriodMs
        val anchor = grid.firstBeatMs.toDouble() + if (useBar) grid.barOffsetBeats * grid.beatPeriodMs else 0.0
        if (!unit.isFinite() || unit <= 0.0 || !anchor.isFinite()) return 0.0
        val nearest = anchor + kotlin.math.round((candidateMs - anchor) / unit) * unit
        val distance = abs(candidateMs - nearest)
        val tolerance = if (useBar) unit * 0.10 else unit * 0.12
        if (distance > tolerance) return 0.0
        return if (useBar) 0.9 * (1.0 - distance / tolerance) else 0.45 * (1.0 - distance / tolerance)
    }

    private fun Long.toIntSafe(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
