package com.example.automix

import android.content.Context
import android.net.Uri
import kotlin.math.roundToInt

/**
 * BPM exposed to the library/UI is derived from the same beat-grid analysis
 * that AUTOMIX uses for rhythm alignment. This avoids maintaining two
 * independent tempo estimators that could disagree for the same track.
 *
 * Returns null when the beat-grid detector cannot establish a sufficiently
 * reliable tempo; the UI should display an unknown/analyzing state instead
 * of inventing a BPM value.
 */
object BpmDetector {
    fun detect(context: Context, uri: Uri): Int? {
        val grid = BeatGridDetector.detect(context, uri) ?: return null
        val bpm = grid.bpm
        if (!bpm.isFinite() || bpm !in 60.0..180.0) return null
        if (!grid.confidence.isFinite() || grid.confidence < 0.015) return null
        return bpm.roundToInt().takeIf { it in 60..180 }
    }
}
