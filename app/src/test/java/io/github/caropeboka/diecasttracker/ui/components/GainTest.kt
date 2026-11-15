package io.github.caropeboka.diecasttracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GainTest {
    @Test
    fun `a gain needs both prices and has no percent when it was free`() {
        assertEquals(Gain(5_000, 25.0), Gain.of(20_000, 25_000))
        assertEquals(Gain(-3_000, -10.0), Gain.of(30_000, 27_000))
        assertEquals(Gain(0, 0.0), Gain.of(15_000, 15_000))
        assertEquals(Gain(12_000, null), Gain.of(0, 12_000))
        assertNull(Gain.of(null, 25_000))
        assertNull(Gain.of(20_000, null))
    }
}
