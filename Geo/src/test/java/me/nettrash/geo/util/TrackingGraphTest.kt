package me.nettrash.geo.util

import me.nettrash.geo.data.model.DataPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-time tracking-graph tests. The Stat tab's top "TRACKING ALTITUDE" chart
 * keeps its barometer series live when GPS is unavailable by recording a NaN
 * gap for the missing series (rather than collapsing that line to sea level).
 *
 * Mirrored by iOS `TrackingGraphTests` — same scenarios, so a divergence in
 * either platform's behaviour fails its tests.
 */
class TrackingGraphTest {

    private val slots = TrackingGraph.AMOUNT_OF_VALUES

    private fun emptySet() = mutableListOf<DataPoint>()

    @Test
    fun `barometer only records a gap for gps`() {
        val (data, min, max) = TrackingGraph.addPoint(emptySet(), 1200f, null, "12:00")
        val last = data.last()
        assertEquals(1200f, last.values[0], 1e-6f)   // barometer drawn
        assertTrue(last.values[1].isNaN())           // GPS = gap
        // Auto-scale keys off the finite barometer sample and ignores the NaN.
        assertTrue(min <= 1200f)
        assertTrue(max >= 1200f)
    }

    @Test
    fun `gps only records a gap for barometer`() {
        // e.g. a device with no pressure sensor
        val (data, _, _) = TrackingGraph.addPoint(emptySet(), null, 800f, "12:00")
        val last = data.last()
        assertTrue(last.values[0].isNaN())           // barometer = gap
        assertEquals(800f, last.values[1], 1e-6f)    // GPS drawn
    }

    @Test
    fun `both series recorded`() {
        val (data, _, _) = TrackingGraph.addPoint(emptySet(), 1000f, 1010f, "12:00")
        val last = data.last()
        assertEquals(1000f, last.values[0], 1e-6f)
        assertEquals(1010f, last.values[1], 1e-6f)
    }

    @Test
    fun `seed placeholders are NaN`() {
        val (data, _, _) = TrackingGraph.addPoint(emptySet(), 500f, null, "12:00")
        assertEquals(slots, data.size)               // seeded to full width
        // Every slot but the last real sample is a NaN placeholder (drawn as
        // nothing), so the chart fills from the right instead of showing a flat
        // sea-level line on launch.
        assertTrue(data.dropLast(1).all { it.values[0].isNaN() && it.values[1].isNaN() })
    }

    @Test
    fun `all-gap sample keeps the default scale`() {
        // No finite sample at all: the window falls back to the default altitude
        // range rather than producing NaN bounds.
        val (_, min, max) = TrackingGraph.addPoint(emptySet(), null, null, "12:00")
        assertEquals(TrackingGraph.ALTITUDE_MIN_DEFAULT, min, 1e-6f)
        assertEquals(TrackingGraph.ALTITUDE_MAX_DEFAULT, max, 1e-6f)
        assertFalse(min.isNaN())
        assertFalse(max.isNaN())
    }

    @Test
    fun `ring buffer stays at width`() {
        val set = emptySet()
        var data: List<DataPoint> = emptyList()
        for (i in 0 until slots * 2) {
            data = TrackingGraph.addPoint(set, (100 + i).toFloat(), null, "12:00").first
        }
        assertEquals(slots, data.size)               // oldest scrolled off
        assertEquals((100 + slots * 2 - 1).toFloat(), data.last().values[0], 1e-6f)
    }
}
