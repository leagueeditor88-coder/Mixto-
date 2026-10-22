package com.example.automix

import android.content.Context
import android.os.Build
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File

/** Clasificador local de género mediante Qwen2.5-0.5B-Instruct en GGUF/llama.cpp. */
class LocalAiGenreClassifier(context: Context) {
    private val appContext = context.applicationContext
    private var unavailable = false

    data class DiagnosticResult(
        val modelFound: Boolean,
        val inferenceExecuted: Boolean,
        val rawResponse: String = "",
        val normalizedGenre: String? = null,
        val error: String? = null
    )

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
        return try {
            val activeModel = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(contextSize = 512, threads = 2)
            )
            try {
                val result = Llama.complete(
                    activeModel,
                    prompt = buildPrompt(title, artist, album),
                    systemPrompt = systemPrompt(),
                    maxTokens = 24
                )
                normalizeGenre(result.text)
            } finally {
                Llama.releaseModel(activeModel)
            }
        } catch (_: UnsatisfiedLinkError) {
            unavailable = true
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Ejecuta una inferencia real y devuelve la respuesta original para diagnosticar el modelo. */
    suspend fun testInference(title: String, artist: String, album: String): DiagnosticResult {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            return DiagnosticResult(false, false, error = "El motor instalado no es compatible con la ABI de este dispositivo (se requiere arm64-v8a).")
        }
        val file = modelFile() ?: return DiagnosticResult(false, false, error = "No se encontró un archivo GGUF completo y válido en la carpeta local del modelo.")
        return try {
            val activeModel = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(contextSize = 512, threads = 2)
            )
            try {
                val result = Llama.complete(
                    activeModel,
                    prompt = buildPrompt(title.ifBlank { "Billie Jean" }, artist.ifBlank { "Michael Jackson" }, album.ifBlank { "Thriller" }),
                    systemPrompt = systemPrompt(),
                    maxTokens = 24
                )
                val raw = result.text.trim()
                DiagnosticResult(
                    modelFound = true,
                    inferenceExecuted = raw.isNotBlank(),
                    rawResponse = raw,
                    normalizedGenre = normalizeGenre(raw),
                    error = if (raw.isBlank()) "El motor respondió con texto vacío." else null
                )
            } finally {
                Llama.releaseModel(activeModel)
            }
        } catch (e: UnsatisfiedLinkError) {
            unavailable = true
            DiagnosticResult(true, false, error = "No se pudo iniciar el motor nativo: ${e.message ?: "UnsatisfiedLinkError"}")
        } catch (e: Exception) {
            DiagnosticResult(true, false, error = "Falló la carga/inferencia: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildPrompt(title: String, artist: String, album: String): String =
        "Clasifica el género musical probable usando exclusivamente el título, artista y álbum. " +
            "Sé conservador: si esos datos no bastan para saberlo, responde exactamente Desconocido. " +
            "No supongas que el género habitual del artista aplica a todas sus canciones. " +
            "Devuelve una sola etiqueta exacta de esta lista: Vallenato, Salsa, Merengue, Bachata, " +
            "Reguetón, Cumbia, Champeta, Porro, Rock, Pop, Jazz, Blues, Reggae, Hip-hop, Rap, " +
            "Electrónica, House, Techno, Metal, Punk, Bolero, Ranchera, Banda, Norteño, " +
            "Regional mexicano, Música llanera, Joropo, Tango, Flamenco, Folk, Country, Gospel, " +
            "Clásica, Otro o Desconocido. No añadas explicaciones ni puntuación.\n" +
            "Título: $title\nArtista: $artist\nÁlbum: $album\nEtiqueta:"

    private fun systemPrompt() =
        "Eres un clasificador conservador de géneros musicales. No inventes datos. " +
            "Responde con una sola etiqueta permitida; si no puedes inferirlo razonablemente, responde Desconocido."

    private fun normalizeGenre(raw: String): String? {
        val text = raw.trim()
            .trim('"', '\'', '.', ':', '-', ' ', '\n', '\r')
            .substringBefore('\n')
            .substringBefore('.')
            .trim()
        val allowed = listOf(
            "Vallenato", "Salsa", "Merengue", "Bachata", "Reguetón", "Cumbia", "Champeta", "Porro",
            "Rock", "Pop", "Jazz", "Blues", "Reggae", "Hip-hop", "Rap", "Electrónica", "House", "Techno",
            "Metal", "Punk", "Bolero", "Ranchera", "Banda", "Norteño", "Regional mexicano", "Música llanera",
            "Joropo", "Tango", "Flamenco", "Folk", "Country", "Gospel", "Clásica", "Otro", "Desconocido"
        )
        // Solo coincidencia exacta: evita aceptar respuestas como "Vallenato porque...".
        return allowed.firstOrNull { it.equals(text, ignoreCase = true) }
    }

    suspend fun close() { /* cada inferencia libera el modelo nativo al terminar */ }
}
