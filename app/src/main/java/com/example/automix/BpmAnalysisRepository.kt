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
        val key = uri.toString()
        cache[key]?.let { return it }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            cache[key]?.let { return it }
            val result = BeatGridDetector.detect(context.applicationContext, uri)
            if (result != null) cache[key] = result
            return result
        }
    }

    fun clear() {
        cache.clear()
    }
}
