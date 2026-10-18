package com.example.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Primera capa de estructura musical basada en una envolvente de energía.
 * No identifica voces ni puede afirmar que una región sea un puente/interludio:
 * devuelve candidatos que el planificador puede valorar junto con BPM/compases.
 *
 * La entrada son niveles RMS por ventana, en orden temporal. Esta función es pura,
 * por lo que puede probarse sin Android ni decodificar un archivo real.
 */
internal object TrackStructureAnalyzer {
    enum class RegionType { LOW_ENERGY_BREAK, BUILD_UP, HIGH_ENERGY, ENERGY_CHANGE, STABLE }

    data class Region(
        val startMs: Long,
        val endMs: Long,
        val type: RegionType,
        /** Confianza heurística 0..1; no equivale a probabilidad de ser una sección musical. */
        val confidence: Double,
        val meanEnergy: Double
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
        val isTransitionCandidate: Boolean
            get() = type == RegionType.LOW_ENERGY_BREAK || type == RegionType.ENERGY_CHANGE
    }

    data class Analysis(
        val durationMs: Long,
        val regions: List<Region>,
        /** Puntos temporales candidatos para reevaluar una transición. */
        val transitionCandidatesMs: List<Long>
    )

    fun estimate(levels: List<Double>, durationMs: Long, windowMs: Int = 50): Analysis? {
        if (levels.size < 20 || durationMs <= 0L || windowMs <= 0) return null
        val clean = levels.map { if (it.isFinite()) it.coerceAtLeast(0.0) else 0.0 }
        val sorted = clean.sorted()
        val peak = sorted.lastOrNull() ?: return null
        if (peak < 1e-6) return null
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-9)
        val lowThreshold = max(peak * 0.10, median * 0.30)
        val highThreshold = max(peak * 0.58, median * 1.15)
        val blockFrames = max(4, 2000 / windowMs) // bloques de aproximadamente 2 s
        val blocks = ArrayList<Double>()
        var i = 0
        while (i < clean.size) {
            val end = min(clean.size, i + blockFrames)
            var sum = 0.0
            for (j in i until end) sum += clean[j]
            blocks += sum / (end - i)
            i = end
        }
        if (blocks.size < 3) return null

        val raw = ArrayList<Region>(blocks.size)
        for (b in blocks.indices) {
            val prev = blocks[max(0, b - 1)]
            val next = blocks[min(blocks.lastIndex, b + 1)]
            val energy = blocks[b]
            val relativeChange = abs(next - prev) / max(max(next, prev), 1e-9)
            val rising = next > energy * 1.18 && energy >= prev * 0.88
            val type = when {
                energy <= lowThreshold -> RegionType.LOW_ENERGY_BREAK
                rising -> RegionType.BUILD_UP
                energy >= highThreshold -> RegionType.HIGH_ENERGY
                relativeChange >= 0.38 -> RegionType.ENERGY_CHANGE
                else -> RegionType.STABLE
            }
            val confidence = when (type) {
                RegionType.LOW_ENERGY_BREAK -> (1.0 - energy / max(lowThreshold, 1e-9)).coerceIn(0.0, 1.0)
                RegionType.BUILD_UP -> ((next - energy) / max(energy, 1e-9)).coerceIn(0.0, 1.0)
                RegionType.HIGH_ENERGY -> (energy / max(peak, 1e-9)).coerceIn(0.0, 1.0)
                RegionType.ENERGY_CHANGE -> relativeChange.coerceIn(0.0, 1.0)
                RegionType.STABLE -> 0.25
            }
            val start = (b.toLong() * blockFrames * windowMs).coerceAtMost(durationMs)
            val end = ((b + 1L) * blockFrames * windowMs).coerceAtMost(durationMs)
            raw += Region(start, end, type, confidence, energy)
        }

        // Une bloques contiguos del mismo tipo para que el resultado sea manejable.
        val merged = ArrayList<Region>()
        for (region in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.type == region.type && last.endMs == region.startMs) {
                val total = (last.durationMs + region.durationMs).coerceAtLeast(1L)
                merged[merged.lastIndex] = last.copy(
                    endMs = region.endMs,
                    confidence = ((last.confidence * last.durationMs + region.confidence * region.durationMs) / total).coerceIn(0.0, 1.0),
                    meanEnergy = (last.meanEnergy * last.durationMs + region.meanEnergy * region.durationMs) / total
                )
            } else merged += region
        }

        // Evita proponer pausas demasiado breves: exige al menos 1.2 s.
        val candidates = merged.asSequence()
            .filter { it.isTransitionCandidate && it.durationMs >= 1200L && it.confidence >= 0.22 }
            .map { it.startMs + it.durationMs / 2L }
            .distinct()
            .toList()
        return Analysis(durationMs, merged, candidates)
    }
}
