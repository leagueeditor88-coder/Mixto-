package com.example.automix

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitionPlannerTest {
    @Test fun treatsHalfAndDoubleTempoAsEquivalentWhenPlanning() {
        val outgoing = BeatGridDetector.BeatGrid(70.0, 0L, 0.9, 0, 0.8)
        val incoming = BeatGridDetector.BeatGrid(140.0, 0L, 0.9, 0, 0.8)

        val plan = TransitionPlanner.plan(outgoing, incoming, 8, "Desvanecer")

        assertEquals("Desvanecer", plan.style)
        assertEquals(8_000, plan.fadeMs)
    }

    @Test fun keepsFallbackWhenBeatGridIsMissing() {
        val plan = TransitionPlanner.plan(null, null, 8, "Ascender")

        assertEquals("Ascender", plan.style)
        assertEquals(8_000, plan.fadeMs)
    }
}
