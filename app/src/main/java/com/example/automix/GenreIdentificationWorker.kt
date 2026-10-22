package com.example.automix

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Clasifica géneros exclusivamente con el modelo Qwen2.5 local.
 * No consulta metadatos de género ni buscadores web. Los resultados antiguos
 * procedentes de metadatos/web se vuelven a procesar con IA.
 */
class GenreIdentificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "automix_genre_scan"
        private const val PREFS = "automix"
        private const val CACHE_KEY = "genre_cache"
        private const val EVIDENCE_KEY = "genre_evidence"
        private const val AI_SOURCE = "IA local Qwen2.5-0.5B"
        private const val CLASSIFIER_VERSION = "qwen-local-v2"
    }

    private data class Item(val uri: Uri, val title: String, val artist: String, val album: String)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val localAi = LocalAiGenreClassifier(applicationContext)
        try {
            val items = readLibrary()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cache = readCache(prefs)
            val evidence = try { JSONObject(prefs.getString(EVIDENCE_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            // Solo cuentan como identificadas las etiquetas generadas por esta IA.
            var identified = items.count { item ->
                val key = item.uri.toString()
                cache.optString(key, "").isNotBlank() && evidence.optJSONObject(key)?.optString("source") == AI_SOURCE &&
                    evidence.optJSONObject(key)?.optString("classifierVersion") == CLASSIFIER_VERSION
            }
            var processed = 0
            val total = items.size
            setProgress(workDataOf(
                "status" to "Preparando IA local Qwen2.5…",
                "processed" to 0,
                "total" to total,
                "identified" to identified,
                "pending" to (total - identified).coerceAtLeast(0)
            ))

            for (item in items) {
                if (isStopped) return@withContext Result.failure(workDataOf("status" to "Pausado"))
                val key = item.uri.toString()
                val priorEvidence = evidence.optJSONObject(key)
                val priorIsAi = priorEvidence != null &&
                    priorEvidence.optString("source") == AI_SOURCE &&
                    priorEvidence.optString("classifierVersion") == CLASSIFIER_VERSION
                val cached = cache.optString(key, "")

                // Reutiliza solo resultados ya producidos por Qwen. Metadatos/web/caché antigua
                // no se aceptan como respuesta de IA y se vuelven a clasificar.
                if (cached.isNotBlank() && priorIsAi) {
                    // No hay trabajo para esta canción.
                } else {
                    cache.remove(key)
                    evidence.remove(key)
                    val found = try { localAi.classify(item.title, item.artist, item.album) } catch (_: Throwable) { null }
                    if (!found.isNullOrBlank() && found != "Desconocido" && found != "Otro") {
                        cache.put(key, found)
                        evidence.put(key, JSONObject()
                            .put("source", AI_SOURCE)
                            .put("classifierVersion", CLASSIFIER_VERSION)
                            .put("confidence", "Inferencia textual; no analiza el audio")
                            .put("status", "Inferido por IA; revisar")
                            .put("genre", found))
                        identified++
                    }
                }

                processed++
                prefs.edit().putString(CACHE_KEY, cache.toString())
                    .putString(EVIDENCE_KEY, evidence.toString()).apply()
                setProgress(workDataOf(
                    "status" to "IA local: analizando canciones…",
                    "processed" to processed,
                    "total" to total,
                    "identified" to identified,
                    "pending" to (total - identified).coerceAtLeast(0)
                ))
            }
            prefs.edit().putString(CACHE_KEY, cache.toString())
                .putString(EVIDENCE_KEY, evidence.toString()).apply()
            Result.success(workDataOf(
                "status" to "Análisis con IA local completado",
                "processed" to processed,
                "total" to total,
                "identified" to identified,
                "pending" to (total - identified).coerceAtLeast(0)
            ))
        } catch (_: SecurityException) {
            Result.failure(workDataOf("status" to "Falta permiso para leer la biblioteca"))
        } catch (e: Exception) {
            if (isStopped) Result.failure(workDataOf("status" to "Pausado"))
            else Result.failure(workDataOf("status" to "Error en IA local: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            localAi.close()
        }
    }

    private fun readLibrary(): List<Item> {
        val result = mutableListOf<Item>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        applicationContext.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 60000",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            while (cursor.moveToNext()) {
                if (idCol < 0) continue
                val id = cursor.getLong(idCol)
                val title = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                val artist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty().takeUnless { it == "<unknown>" }.orEmpty() else ""
                val album = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""
                result += Item(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title, artist, album)
            }
        }
        return result
    }

    private fun readCache(prefs: android.content.SharedPreferences): JSONObject = try {
        JSONObject(prefs.getString(CACHE_KEY, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }
}
