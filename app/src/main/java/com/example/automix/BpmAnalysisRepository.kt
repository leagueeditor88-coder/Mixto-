package com.example.automix

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Single in-memory source of truth for BPM/beat-grid analysis.
 * The library derives its integer BPM from this result; Automix reuses the
 * same grid for beat alignment and transition planning.
 */
object BpmAnalysisRepository {
    private val cache = ConcurrentHashMap<String, BeatGridDetector.BeatGrid>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun getCached(uri: Uri): BeatGridDetector.BeatGrid? = cache[uri.toString()]

    fun getOrDetect(context: Context, uri: Uri): BeatGridDetector.BeatGrid? {
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
            val result = BeatGridDetector.detect(appContext, uri)
            if (result != null) {
                cache[key] = result
                persist(appContext, key, result)
            }
            return result
        }
    }

    /** Lee la rejilla guardada para no volver a decodificar una pista en cada sesión. */
    private fun readPersisted(context: Context, uriKey: String): BeatGridDetector.BeatGrid? {
        val prefs = context.getSharedPreferences("automix_beat_grid", Context.MODE_PRIVATE)
        val prefix = "grid_${uriKey.hashCode()}"
        if (prefs.getString("$prefix.uri", null) != uriKey) return null
        return try {
            BeatGridDetector.BeatGrid(
                bpm = prefs.getString("$prefix.bpm", null)?.toDouble() ?: return null,
                firstBeatMs = prefs.getLong("$prefix.firstBeatMs", 0L),
                confidence = prefs.getString("$prefix.confidence", null)?.toDouble() ?: 0.0,
                barOffsetBeats = prefs.getInt("$prefix.barOffset", 0),
                barConfidence = prefs.getString("$prefix.barConfidence", null)?.toDouble() ?: 0.0
            ).takeIf { it.bpm.isFinite() && it.bpm > 0.0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(context: Context, uriKey: String, grid: BeatGridDetector.BeatGrid) {
        val prefix = "grid_${uriKey.hashCode()}"
        context.getSharedPreferences("automix_beat_grid", Context.MODE_PRIVATE).edit()
            .putString("$prefix.uri", uriKey)
            .putString("$prefix.bpm", grid.bpm.toString())
            .putLong("$prefix.firstBeatMs", grid.firstBeatMs)
            .putString("$prefix.confidence", grid.confidence.toString())
            .putInt("$prefix.barOffset", grid.barOffsetBeats)
            .putString("$prefix.barConfidence", grid.barConfidence.toString())
            .apply()
    }

    fun clear() {
        cache.clear()
    }
}
