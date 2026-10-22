package com.example.automix

import android.content.Context
import android.os.Build
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File
import java.util.Locale

/**
 * Clasificador local de género usando Qwen2.5-0.5B-Instruct en GGUF y llama.cpp.
 * El modelo se mantiene cargado durante el escaneo y se libera al terminar.
 * Solo intenta cargar el motor en dispositivos arm64-v8a.
 */
class LocalAiGenreClassifier(context: Context) {
    private val appContext = context.applicationContext
    private var unavailable = false

    private fun modelFile(): File? = LocalAiModelManager(appContext).status()
        .takeIf { it.isComplete }?.path?.let(::File)
        ?.takeIf { it.exists() && it.length() > 100_000_000L }

    suspend fun classify(title: String, artist: String, album: String): String? {
        if (title.isBlank() || artist.isBlank() || unavailable) return null
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            unavailable = true
            return null
        }
        val file = modelFile() ?: return null
        try {
            // Contexto pequeño y dos hilos para reducir presión de RAM en teléfonos modestos.
            val activeModel = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(contextSize = 512, threads = 2)
            )
            try {
                val prompt = "Clasifica el género musical probable de esta canción usando solo título, artista y álbum. " +
                    "Devuelve exactamente una etiqueta de esta lista cuando sea posible: Vallenato, Salsa, Merengue, " +
                    "Bachata, Reguetón, Cumbia, Champeta, Porro, Rock, Pop, Jazz, Blues, Reggae, Hip-hop, Rap, " +
                    "Electrónica, House, Techno, Metal, Punk, Bolero, Ranchera, Banda, Norteño, Regional mexicano, " +
                    "Música llanera, Joropo, Tango, Flamenco, Folk, Country, Gospel, Clásica, Otro o Desconocido.\n" +
                    "Título: $title\nArtista: $artist\nÁlbum: $album\nEtiqueta:"
                val result = Llama.complete(
                    activeModel,
                    prompt = prompt,
                    systemPrompt = "Eres un clasificador conservador de géneros musicales. No inventes datos. Responde con una sola etiqueta y nada más.",
                    maxTokens = 24
                )
                return normalizeGenre(result.text)
            } finally {
                Llama.releaseModel(activeModel)
            }
        } catch (_: UnsatisfiedLinkError) {
            unavailable = true
            return null
        } catch (_: Exception) {
            // Un fallo de inferencia no debe detener el escaneo de la biblioteca.
            return null
        }
    }

    private fun normalizeGenre(raw: String): String? {
        val text = raw.trim()
            .trim('"', '\'', '.', ':', '-', ' ', '\n', '\r')
            .substringBefore('\n')
            .substringBefore('.')
        val allowed = listOf(
            "Vallenato", "Salsa", "Merengue", "Bachata", "Reguetón", "Cumbia", "Champeta", "Porro",
            "Rock", "Pop", "Jazz", "Blues", "Reggae", "Hip-hop", "Rap", "Electrónica", "House", "Techno",
            "Metal", "Punk", "Bolero", "Ranchera", "Banda", "Norteño", "Regional mexicano", "Música llanera",
            "Joropo", "Tango", "Flamenco", "Folk", "Country", "Gospel", "Clásica", "Otro", "Desconocido"
        )
        return allowed.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: allowed.firstOrNull { text.lowercase(Locale.ROOT).startsWith(it.lowercase(Locale.ROOT) + " ") }
                ?.takeUnless { it == "Otro" || it == "Desconocido" }
    }

    suspend fun close() { /* cada inferencia libera su modelo nativo */ }
}
