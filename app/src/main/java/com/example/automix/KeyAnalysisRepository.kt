package com.example.automix

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Repositorio compartido de tonalidad: la biblioteca y Automix reutilizan el mismo
 * resultado y coordinan las solicitudes concurrentes por URI.
 */
object KeyAnalysisRepository {
    private val cache = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun getCached(uri: Uri): String? = cache[uri.toString()]

    fun getOrDetect(context: Context, uri: Uri): String? {
        val appContext = context.applicationContext
        val key = uri.toString()
        cache[key]?.let { return it }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            cache[key]?.let { return it }
            readPersisted(appContext, key)?.let {
                cache[key] = it
                return it
            }
            val detected = KeyDetector.detect(appContext, uri)?.trim()?.takeIf { it.isNotEmpty() }
            if (detected != null) {
                cache[key] = detected
                persist(appContext, key, detected)
            }
            return detected
        }
    }

    private fun readPersisted(context: Context, uriKey: String): String? {
        val prefs = context.getSharedPreferences("automix_key_analysis", Context.MODE_PRIVATE)
        val prefix = "key_${uriKey.hashCode()}"
        if (prefs.getString("$prefix.uri", null) != uriKey) return null
        return prefs.getString("$prefix.value", null)?.takeIf { it.isNotBlank() }
    }

    private fun persist(context: Context, uriKey: String, value: String) {
        val prefix = "key_${uriKey.hashCode()}"
        context.getSharedPreferences("automix_key_analysis", Context.MODE_PRIVATE).edit()
            .putString("$prefix.uri", uriKey)
            .putString("$prefix.value", value)
            .apply()
    }

    fun clearMemoryCache() = cache.clear()
}
