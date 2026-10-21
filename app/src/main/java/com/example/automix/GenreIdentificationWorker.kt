package com.example.automix

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.Locale

/** Escanea la biblioteca, prioriza metadatos locales y guarda diagnóstico por canción. */
class GenreIdentificationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val UNIQUE_WORK_NAME = "automix_genre_scan"
        private const val PREFS = "automix"
        private const val CACHE_KEY = "genre_cache"
        private const val EVIDENCE_KEY = "genre_evidence"
        private const val LAST_REPORT_KEY = "genre_last_report"
    }

    private data class Item(val uri: Uri, val title: String, val artist: String, val album: String, val genre: String)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val startedAt = System.currentTimeMillis()
        val diagnostics = JSONArray()
        val providerHistory = linkedMapOf<String, MutableList<String>>()
        var processed = 0
        var identified = 0
        var localMetadata = 0
        var webAttempts = 0
        var probable = 0
        var insufficient = 0
        var errors = 0
        var cached = 0
        var currentTrack = ""
        var lastLookupLine = "Aún no se ha ejecutado ninguna consulta web."
        var total = 0
        val cache = readCache(prefs)
        val evidence = try { JSONObject(prefs.getString(EVIDENCE_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

        fun report(status: String): String {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0)
            val lines = mutableListOf(
                "Resultado: $status",
                "Canciones revisadas: $processed/$total",
                "Géneros disponibles: $identified · metadatos locales: $localMetadata · web probables: $probable",
                "Sin evidencia suficiente: $insufficient · errores de búsqueda: $errors · ya guardadas/omitidas: $cached",
                "Consultas web: $webAttempts · duración: ${"%.1f".format(Locale.ROOT, seconds)} s",
                "Última consulta: $lastLookupLine"
            )
            if (providerHistory.isNotEmpty()) {
                lines += "Motores (éxito / respuesta vacía / error-bloqueo):"
                providerHistory.toSortedMap().forEach { (provider, entries) ->
                    val success = entries.count { it.contains(": éxito;") }
                    val empty = entries.count { it.contains("respuesta HTTP sin resultados interpretables") || it.contains("HTML vacío/no interpretable") }
                    val failed = entries.size - success - empty
                    lines += "$provider: $success / $empty / $failed"
                }
            }
            if (currentTrack.isNotBlank()) lines.add(1, "Canción actual: ${currentTrack.take(110)}")
            val recent = (maxOf(0, diagnostics.length() - 4) until diagnostics.length()).mapNotNull { i ->
                try { diagnostics.getJSONObject(i).optString("line").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
            }
            if (recent.isNotEmpty()) lines += "Diagnóstico reciente:\n" + recent.joinToString("\n")
            return lines.joinToString("\n").take(3500)
        }

        suspend fun publish(status: String) {
            val text = report(status)
            prefs.edit().putString(CACHE_KEY, cache.toString()).putString(EVIDENCE_KEY, evidence.toString())
                .putString(LAST_REPORT_KEY, text).apply()
            setProgress(workDataOf("status" to status, "report" to text, "processed" to processed,
                "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))
        }

        try {
            val items = readLibrary()
            total = items.size
            identified = items.count { it.genre.isNotBlank() || cache.optString(it.uri.toString(), "").isNotBlank() }
            localMetadata = items.count { it.genre.isNotBlank() }
            publish("Iniciando análisis…")

            for (item in items) {
                if (isStopped) {
                    publish("Cancelado por el usuario")
                    return@withContext Result.failure(workDataOf("status" to "Pausado", "report" to report("Pausado"),
                        "processed" to processed, "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))
                }
                currentTrack = "${item.artist} — ${item.title}"
                val key = item.uri.toString()
                try {
                    val cachedGenre = cache.optString(key, "")
                    if (item.genre.isNotBlank()) {
                        if (cachedGenre != item.genre) cache.put(key, item.genre)
                        evidence.put(key, JSONObject().put("source", "Metadatos locales").put("confidence", "Alta")
                            .put("status", "Confirmado").put("genre", item.genre))
                    } else if (cachedGenre.isNotBlank()) {
                        cached++
                        evidence.put(key, evidence.optJSONObject(key) ?: JSONObject().put("source", "Caché local")
                            .put("status", "Guardado").put("genre", cachedGenre))
                    } else {
                        webAttempts++
                        if (!hasInternet()) {
                            errors++
                            lastLookupLine = "No hay conexión validada; ningún buscador se consultó."
                            diagnostics.put(JSONObject().put("track", currentTrack).put("line", "$currentTrack → $lastLookupLine"))
                        } else {
                            val lookup = GenreWebLookup.lookupDetailed(item.title, item.artist, item.album)
                            lookup.engineDiagnostics.forEach { entry ->
                                val provider = entry.substringBefore(": ").ifBlank { "Motor desconocido" }
                                providerHistory.getOrPut(provider) { mutableListOf() }.add(entry)
                            }
                            val engines = if (lookup.engineDiagnostics.isEmpty()) "sin diagnóstico de motores" else lookup.engineDiagnostics.joinToString(" | ")
                            lastLookupLine = "${lookup.status}; motores: $engines; páginas coincidentes=${lookup.matchedResults}; respuestas HTTP procesadas=${lookup.queriesUsed}; duración=${lookup.durationMs} ms"
                            diagnostics.put(JSONObject().put("track", currentTrack).put("line", "$currentTrack → $lastLookupLine"))
                            when {
                                !lookup.genre.isNullOrBlank() -> {
                                    cache.put(key, lookup.genre)
                                    evidence.put(key, JSONObject().put("source", "Búsqueda web multibuscador")
                                        .put("confidence", lookup.confidence).put("status", "Probable").put("genre", lookup.genre)
                                        .put("diagnostic", lastLookupLine).put("sources", JSONArray(lookup.sources)))
                                    probable++
                                }
                                lookup.status == GenreWebLookup.STATUS_UNAVAILABLE -> errors++
                                else -> insufficient++
                            }
                            delay(500)
                        }
                    }
                } catch (e: Exception) {
                    errors++
                    lastLookupLine = "Error procesando canción: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
                    diagnostics.put(JSONObject().put("track", currentTrack).put("line", "$currentTrack → $lastLookupLine"))
                } finally {
                    // Cada canción incrementa el progreso incluso si falla la consulta web.
                    processed++
                    identified = items.count { it.genre.isNotBlank() || cache.optString(it.uri.toString(), "").isNotBlank() }
                    publish(if (processed == total) "Finalizando informe…" else "Revisando $processed de $total")
                }
            }

            val finalStatus = when {
                total == 0 -> "Biblioteca vacía"
                errors > 0 && identified == 0 -> "Falló la búsqueda web"
                errors > 0 || insufficient > 0 -> "Completado parcialmente"
                else -> "Completado correctamente"
            }
            currentTrack = ""
            val finalReport = report(finalStatus)
            prefs.edit().putString(CACHE_KEY, cache.toString()).putString(EVIDENCE_KEY, evidence.toString())
                .putString(LAST_REPORT_KEY, finalReport).apply()
            Result.success(workDataOf("status" to finalStatus, "report" to finalReport, "processed" to processed,
                "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))
        } catch (e: SecurityException) {
            val text = "Resultado: Falló el análisis\nCausa: falta permiso para leer la biblioteca.\nCanciones revisadas: $processed/$total"
            prefs.edit().putString(LAST_REPORT_KEY, text).apply()
            Result.failure(workDataOf("status" to "Error: falta permiso para leer la biblioteca", "report" to text,
                "processed" to processed, "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))
        } catch (e: Exception) {
            val text = "Resultado: Falló el análisis\nCausa: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}\nCanciones revisadas: $processed/$total"
            prefs.edit().putString(LAST_REPORT_KEY, text).apply()
            Result.failure(workDataOf("status" to "Error: ${e.javaClass.simpleName}", "report" to text,
                "processed" to processed, "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))
        }
    }

    private fun readLibrary(): List<Item> {
        val result = mutableListOf<Item>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.GENRE)
        applicationContext.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 60000", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val genreCol = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val title = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                val artist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty().takeUnless { it == "<unknown>" }.orEmpty() else ""
                val album = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""
                val genre = if (genreCol >= 0) cursor.getString(genreCol).orEmpty().takeUnless { it == "<unknown>" }.orEmpty() else ""
                result += Item(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title, artist, album, genre)
            }
        }
        return result
    }

    private fun readCache(prefs: android.content.SharedPreferences): JSONObject = try {
        JSONObject(prefs.getString(CACHE_KEY, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun hasInternet(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
