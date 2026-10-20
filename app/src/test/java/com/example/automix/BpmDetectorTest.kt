package com.example.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class BpmDetectorTest {
    @Test fun detectsSynthetic120BpmPulseTrain() {
        // BeatGridDetector analyses 50 ms energy windows, so 120 BPM is one
        // pulse every 10 frames (500 ms). Vary pulse strength deterministically
        // to avoid an artificial half-tempo harmonic winning the estimator.
        val frames = 600
        val period = 10
        val levels = MutableList(frames) { 0.01 }
        for (i in 0 until frames step period) {
            val beat = i / period
            levels[i] = 0.1 + ((beat * 17 + beat * beat * 7) % 89) / 100.0
        }

        val grid = BeatGridDetector.estimate(levels)
        assertEquals(true, grid != null && abs(grid.bpm - 120.0) <= 3.0)
    }

    @Test fun rejectsTooShortSignal() {
        assertNull(BeatGridDetector.estimate(List(100) { 0.0 }))
    }
}
