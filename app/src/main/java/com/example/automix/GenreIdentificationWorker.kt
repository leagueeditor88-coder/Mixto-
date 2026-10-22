package com.example.automix

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Investiga el género musical usando Gemini + Google Search grounding.
 * Solo guarda una etiqueta cuando Gemini devuelve al menos una fuente web verificable.
 * No utiliza el campo Genre de MediaStore ni analiza el audio.
 */
class GenreIdentificationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val UNIQUE_WORK_NAME = "automix_genre_scan"
        private const val PREFS = "automix"
        private const val CACHE_KEY = "genre_cache"
        private const val EVIDENCE_KEY = "genre_evidence"
        private const val KEY_PREF = "gemini_api_key"
        private const val AI_SOURCE = "Gemini Google Search"
        private const val CLASSIFIER_VERSION = "gemini-grounded-v1"
        // Se puede cambiar sin modificar el resto del worker si Google retira/actualiza un modelo.
        private const val MODEL = "gemini-3.8-flash"
    }

    private data class Item(val uri: Uri, val title: String, val artist: String, val album: String)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_PREF, "").orEmpty().trim()
        if (apiKey.isBlank()) return@withContext Result.failure(workDataOf("status" to "Falta configurar la clave de Gemini"))

        try {
            val items = readLibrary()
            val cache = try { JSONObject(prefs.getString(CACHE_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val evidence = try { JSONObject(prefs.getString(EVIDENCE_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            var identified = items.count { item ->
                val ev = evidence.optJSONObject(item.uri.toString())
                cache.optString(item.uri.toString()).isNotBlank() && ev?.optString("source") == AI_SOURCE && ev.optString("classifierVersion") == CLASSIFIER_VERSION
            }
            var processed = 0
            setProgress(workDataOf("status" to "Preparando Gemini con búsqueda web…", "processed" to 0, "total" to items.size, "identified" to identified, "pending" to (items.size - identified).coerceAtLeast(0)))

            for (item in items) {
                if (isStopped) return@withContext Result.failure(workDataOf("status" to "Pausado"))
                val key = item.uri.toString()
                val prior = evidence.optJSONObject(key)
                val alreadyCurrent = cache.optString(key).isNotBlank() && prior?.optString("source") == AI_SOURCE && prior.optString("classifierVersion") == CLASSIFIER_VERSION
                if (!alreadyCurrent) {
                    cache.remove(key)
                    evidence.remove(key)
                    try {
                        val result = researchGenre(apiKey, item)
                        val genre = result.optString("genre", "").trim()
                        val sources = result.optJSONArray("sources") ?: JSONArray()
                        if (genre.isNotBlank() && !genre.equals("Desconocido", true) && !genre.equals("Otro", true) && sources.length() > 0) {
                            cache.put(key, genre)
                            evidence.put(key, JSONObject()
                                .put("source", AI_SOURCE)
                                .put("classifierVersion", CLASSIFIER_VERSION)
                                .put("genre", genre)
                                .put("status", "Investigado con Google Search")
                                .put("reason", result.optString("reason", ""))
                                .put("sources", sources)
                                .put("timestamp", System.currentTimeMillis()))
                            identified++
                        } else {
                            evidence.put(key, JSONObject().put("source", AI_SOURCE).put("classifierVersion", CLASSIFIER_VERSION)
                                .put("status", "Sin clasificar: fuentes insuficientes").put("sources", sources))
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        evidence.put(key, JSONObject().put("source", AI_SOURCE).put("classifierVersion", CLASSIFIER_VERSION)
                            .put("status", "Error de consulta").put("error", msg.take(300)))
                        if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
                            prefs.edit().putString(CACHE_KEY, cache.toString()).putString(EVIDENCE_KEY, evidence.toString()).apply()
                            return@withContext Result.failure(workDataOf("status" to "Clave de Gemini inválida o sin permiso (HTTP 401/403)", "processed" to processed, "total" to items.size, "identified" to identified, "pending" to (items.size - identified).coerceAtLeast(0)))
                        }
                        if (msg.contains("HTTP 429")) {
                            prefs.edit().putString(CACHE_KEY, cache.toString()).putString(EVIDENCE_KEY, evidence.toString()).apply()
                            return@withContext Result.failure(workDataOf("status" to "Cuota/límite de Gemini alcanzado (HTTP 429). Espera y reintenta.", "processed" to processed, "total" to items.size, "identified" to identified, "pending" to (items.size - identified).coerceAtLeast(0)))
                        }
                    }
                    prefs.edit().putString(CACHE_KEY, cache.toString()).putString(EVIDENCE_KEY, evidence.toString()).apply()
                    delay(700) // limita la velocidad de peticiones y reduce errores por cuota.
                }
                processed++
                setProgress(workDataOf("status" to "Gemini: investigando canciones…", "processed" to processed, "total" to items.size, "identified" to identified, "pending" to (items.size - identified).coerceAtLeast(0)))
            }
            prefs.edit().putString(CACHE_KEY, cache.toString()).putString(EVIDENCE_KEY, evidence.toString()).apply()
            Result.success(workDataOf("status" to "Análisis con Gemini completado", "processed" to processed, "total" to items.size, "identified" to identified, "pending" to (items.size - identified).coerceAtLeast(0)))
        } catch (_: SecurityException) {
            Result.failure(workDataOf("status" to "Falta permiso para leer la biblioteca"))
        } catch (e: Exception) {
            Result.failure(workDataOf("status" to "Error en Gemini: ${(e.message ?: e.javaClass.simpleName).take(200)}"))
        }
    }

    private fun researchGenre(apiKey: String, item: Item): JSONObject {
        val prompt = """
            Investiga esta grabación musical mediante Google Search. No infieras por el título solamente.
            Título: ${item.title}
            Artista: ${item.artist}
            Álbum: ${item.album}

            Busca fuentes que identifiquen esta grabación o al artista y su estilo musical. Distingue el género de la canción concreta del género general del artista. Si el título/artista es ambiguo, es una canción de marca/publicidad, o no encuentras evidencia fiable, devuelve genre como Desconocido.
            Responde exclusivamente con un objeto JSON: {"genre":"género principal en español, máximo 3 palabras o Desconocido","reason":"justificación breve basada en fuentes","sources":[{"title":"título de fuente","url":"URL"}]}. No inventes URLs. Usa solo fuentes que aparezcan en los resultados de búsqueda.
        """.trimIndent()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$encodedKey").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 45000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${responseText.take(500)}")
            val response = JSONObject(responseText)
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: throw IllegalStateException("Gemini no devolvió candidato")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
            val parsed = parseJsonObject(text)
            val sources = collectSources(candidate.optJSONObject("groundingMetadata"))
            parsed.put("sources", sources)
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonObject(text: String): JSONObject {
        try { return JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) } catch (_: Exception) { }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return JSONObject(text.substring(start, end + 1))
        throw IllegalStateException("Respuesta de Gemini no contiene JSON válido")
    }

    private fun collectSources(grounding: JSONObject?): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        val chunks = grounding?.optJSONArray("groundingChunks") ?: JSONArray()
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val url = web.optString("uri", "").trim()
            if (url.startsWith("http") && seen.add(url)) out.put(JSONObject().put("title", web.optString("title", url)).put("url", url))
        }
        return out
    }

    private fun readLibrary(): List<Item> {
        val result = mutableListOf<Item>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
        applicationContext.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 60000",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol).orEmpty().trim()
                val artist = cursor.getString(artistCol).orEmpty().trim()
                val album = cursor.getString(albumCol).orEmpty().trim()
                if (title.isNotBlank() && title != "<unknown>") result.add(Item(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title, artist, album))
            }
        }
        return result
    }
}
