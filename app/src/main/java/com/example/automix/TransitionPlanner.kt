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
        // En Automix, el ajuste del usuario es un techo, no una duración obligatoria.
        // Limitarlo evita que una mezcla se prolongue innecesariamente.
        val requested = requestedFadeSec.coerceIn(3, 7)
        if (outgoing == null || incoming == null ||
            outgoing.confidence < 0.12 || incoming.confidence < 0.12 ||
            !outgoing.bpm.isFinite() || !incoming.bpm.isFinite() ||
            outgoing.bpm !in 60.0..180.0 || incoming.bpm !in 60.0..180.0) {
            // Sin datos fiables, preferir un fundido corto y seguro.
            return Plan(manualFallbackStyle, minOf(requested, 4) * 1000)
        }

        // Compara también las equivalencias de medio/doble tiempo; 70 y 140 BPM
        // pueden describir el mismo pulso musical y no deben penalizarse como 2:1.
        val outgoingTempos = listOf(outgoing.bpm, outgoing.bpm * 2.0, outgoing.bpm / 2.0)
            .filter { it.isFinite() && it in 60.0..180.0 }
        val incomingTempos = listOf(incoming.bpm, incoming.bpm * 2.0, incoming.bpm / 2.0)
            .filter { it.isFinite() && it in 60.0..180.0 }
        val ratios = outgoingTempos.flatMap { a ->
            incomingTempos.map { b -> maxOf(a, b) / minOf(a, b) }
        }
        val ratio = ratios.minOrNull() ?: Double.MAX_VALUE
        val tempoCompatible = ratio <= 1.06
        val barsReliable = outgoing.barConfidence >= 0.25 && incoming.barConfidence >= 0.25
        val style = if (tempoCompatible && barsReliable) "Desvanecer" else "Ascender"

        // Ritmos compatibles toleran una mezcla más larga; diferencias de tempo piden
        // una entrada/salida más breve para limitar la sensación de dos ritmos compitiendo.
        val targetSeconds = when {
            tempoCompatible && barsReliable -> requested.coerceAtMost(7)
            tempoCompatible -> requested.coerceAtMost(6)
            ratio <= 1.12 -> requested.coerceAtMost(5)
            else -> requested.coerceAtMost(4)
        }
        return Plan(style, targetSeconds * 1000)
    }
}
