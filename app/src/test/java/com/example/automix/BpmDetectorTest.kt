package com.example.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class BpmDetectorTest {
    @Test fun detectsSynthetic120BpmPulseTrain() {
        val frames = 3000
        val period = 50 // 100 Hz envelope => 120 BPM
        val signal = MutableList(frames) { 10.0 }
        for (i in 0 until frames step period) {
            signal[i] = 2000.0
            if (i + 1 < frames) signal[i + 1] = 500.0
        }
        val bpm = BpmDetector.estimate(signal)
        assertEquals(true, bpm != null && abs(bpm - 120) <= 3)
    }

    @Test fun rejectsTooShortSignal() {
        assertNull(BpmDetector.estimate(List(100) { 0.0 }))
    }
}
