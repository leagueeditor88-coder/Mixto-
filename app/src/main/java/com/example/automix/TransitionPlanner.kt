package com.example.automix

/**
 * Elige una transición conservadora a partir de datos rítmicos existentes.
 * No pretende detectar voces: BPM/fase no permiten saber si una frase vocal sigue activa.
 */
internal object TransitionPlanner {
    data class Plan(val style: String, val fadeMs: Int)

    fun plan(
        outgoing: BeatGridDetector.BeatGrid?,
        incoming: BeatGridDetector.BeatGrid?,
        requestedFadeSec: Int,
        manualFallbackStyle: String
    ): Plan {
        val requested = requestedFadeSec.coerceIn(2, 16)
        if (outgoing == null || incoming == null ||
            outgoing.confidence < 0.12 || incoming.confidence < 0.12 ||
            !outgoing.bpm.isFinite() || !incoming.bpm.isFinite() ||
            outgoing.bpm !in 60.0..180.0 || incoming.bpm !in 60.0..180.0) {
            // Sin análisis fiable, no inventar una decisión musical: conserva estilo y duración.
            return Plan(manualFallbackStyle, requested * 1000)
        }

        val ratio = maxOf(outgoing.bpm, incoming.bpm) / minOf(outgoing.bpm, incoming.bpm)
        val tempoCompatible = ratio <= 1.06
        val barsReliable = outgoing.barConfidence >= 0.25 && incoming.barConfidence >= 0.25
        val style = if (tempoCompatible && barsReliable) "Desvanecer" else "Ascender"

        // Ritmos compatibles toleran una mezcla más larga; diferencias de tempo piden
        // una entrada/salida más breve para limitar la sensación de dos ritmos compitiendo.
        val targetSeconds = when {
            tempoCompatible && barsReliable -> requested.coerceIn(8, 16)
            tempoCompatible -> requested.coerceIn(6, 10)
            ratio <= 1.12 -> requested.coerceIn(4, 8)
            else -> requested.coerceIn(3, 6)
        }
        return Plan(style, targetSeconds * 1000)
    }
}
