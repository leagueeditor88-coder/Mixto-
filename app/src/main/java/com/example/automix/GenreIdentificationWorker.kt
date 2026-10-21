package com.example.automix

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.text.Html
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Identificación de género sin depender de una API musical: prioriza el género local
 * y consulta páginas de resultados web solo cuando faltan metadatos. Los resultados
 * se guardan localmente para evitar búsquedas repetidas.
 */
class GenreIdentificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "automix_genre_scan"
        private const val PREFS = "automix"
        private const val CACHE_KEY = "genre_cache"
    }

    private data class Item(val uri: Uri, val title: String, val artist: String, val album: String, val genre: String)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val items = readLibrary()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cache = readCache(prefs)
            var identified = items.count { it.genre.isNotBlank() || cache.optString(it.uri.toString(), "").isNotBlank() }
            val evidence = try { JSONObject(prefs.getString("genre_evidence", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            var processed = 0
            val total = items.size
            setProgress(workDataOf("status" to "Revisando metadatos locales…", "processed" to 0,
                "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))

            for (item in items) {
                if (isStopped) return@withContext Result.failure(workDataOf("status" to "Pausado"))
                val key = item.uri.toString()
                val cached = cache.optString(key, "")
                if (item.genre.isNotBlank()) {
                    // El metadato local siempre tiene prioridad sobre una búsqueda previa.
                    if (cached != item.genre) cache.put(key, item.genre)
                    evidence.put(key, JSONObject().put("source", "Metadatos locales")
                        .put("confidence", "Alta").put("status", "Confirmado").put("genre", item.genre))
                } else if (cached.isBlank()) {
                    if (!hasInternet()) return@withContext Result.retry()
                    val found = lookupGenreOnWeb(item.title, item.artist, item.album)
                    if (!found.isNullOrBlank()) {
                        cache.put(key, found)
                        evidence.put(key, JSONObject().put("source", "Búsqueda web")
                            .put("confidence", "Media").put("status", "Probable").put("genre", found))
                        identified++
                        prefs.edit().putString(CACHE_KEY, cache.toString())
                            .putString("genre_evidence", evidence.toString()).apply()
                    }
                    // Evita martillar el buscador y da oportunidad al sistema de pausar.
                    delay(900)
                }
                processed++
                if (processed % 3 == 0 || processed == total) {
                    prefs.edit().putString(CACHE_KEY, cache.toString())
                        .putString("genre_evidence", evidence.toString()).apply()
                    setProgress(workDataOf(
                        "status" to "Analizando biblioteca…",
                        "processed" to processed,
                        "total" to total,
                        "identified" to identified,
                        "pending" to (total - identified).coerceAtLeast(0)
                    ))
                }
            }
            prefs.edit().putString(CACHE_KEY, cache.toString())
                .putString("genre_evidence", evidence.toString()).apply()
            Result.success(workDataOf("status" to "Análisis completado", "processed" to processed,
                "total" to total, "identified" to identified, "pending" to (total - identified).coerceAtLeast(0)))
        } catch (_: SecurityException) {
            Result.failure(workDataOf("status" to "Falta permiso para leer la biblioteca"))
        } catch (_: Exception) {
            if (isStopped) Result.failure(workDataOf("status" to "Pausado"))
            else Result.retry()
        }
    }

    private fun readLibrary(): List<Item> {
        val result = mutableListOf<Item>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.GENRE
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

    private fun lookupGenreOnWeb(title: String, artist: String, album: String): String? =
        GenreWebLookup.lookup(title, artist, album)

    private fun hasInternet(): Boolean {
        val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
