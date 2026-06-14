package me.nettrash.geo.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-history tests for the de-trended pressure-tendency fit (M5a).
 * Mirrors iOS `StormWarningTests` — identical scenarios and thresholds,
 * so a divergence in either platform's math shows up as a failing test
 * on that side.
 */
class StormWarningTest {

    private val nowMs = 1_700_000_000_000L
    private val fifteenMinMs = 15L * 60L * 1000L

    /** Standard-atmosphere pressure ratio P(alt)/P(0); mirrors the helper
     *  inside [StormWarning] so the synthetic histories are generated with
     *  the same physics the fit removes. */
    private fun ratio(altM: Double): Double = Math.pow(1.0 - altM / 44330.0, 5.255)

    /** 13 samples at 15-minute spacing ending at [nowMs] (a 3-hour window). */
    private fun history(
        pressureKpa: (Int) -> Double,
        altitudeM: (Int) -> Double
    ): List<PressureSample> = (0..12).map { idx ->
        PressureSample(
            dateMs = nowMs + (idx - 12) * fifteenMinMs,
            pressureKpa = pressureKpa(idx),
            altitudeM = altitudeM(idx)
        )
    }

    @Test
    fun constantAltitudeFallFiresFallingFast() {
        // 1013 → 1007 hPa over 3 h at a fixed altitude: −6 hPa, alert.
        val samples = history(
            pressureKpa = { 101.3 - it * (0.6 / 12.0) },
            altitudeM = { 0.0 }
        )
        val trend = StormWarning.tendency(samples, nowMs)
        assertEquals(PressureTrendClass.FALLING_FAST, trend.classification)
        assertTrue(trend.isAlert)
        assertEquals(-6.0, trend.changeHpaOver3h, 0.2)
    }

    @Test
    fun altitudeExplainedFallIsSteady() {
        // Pressure drops only because the user climbs 0 → 500 m at a
        // constant weather (1013 hPa sea-level): de-trend must cancel it.
        val samples = history(
            pressureKpa = { 101.3 * ratio(it * (500.0 / 12.0)) },
            altitudeM = { it * (500.0 / 12.0) }
        )
        val trend = StormWarning.tendency(samples, nowMs)
        assertEquals(PressureTrendClass.STEADY, trend.classification)
        assertFalse(trend.isAlert)
        assertEquals(0.0, trend.changeHpaOver3h, 0.2)
    }

    @Test
    fun risingFiresNothing() {
        val samples = history(
            pressureKpa = { 101.3 + it * (0.6 / 12.0) },
            altitudeM = { 0.0 }
        )
        val trend = StormWarning.tendency(samples, nowMs)
        assertEquals(PressureTrendClass.RISING, trend.classification)
        assertFalse(trend.isAlert)
    }

    @Test
    fun insufficientSamplesIsUnknown() {
        val samples = listOf(
            PressureSample(nowMs - 3_600_000L, 101.3, 0.0),
            PressureSample(nowMs, 100.7, 0.0)
        )
        val trend = StormWarning.tendency(samples, nowMs)
        assertEquals(PressureTrendClass.UNKNOWN, trend.classification)
        assertFalse(trend.isAlert)
    }

    @Test
    fun shortSpanIsUnknown() {
        // Four samples but only a 30-minute span (< 1.5 h minimum).
        val samples = (0..3).map { idx ->
            PressureSample(
                dateMs = nowMs + (idx - 3) * 10L * 60L * 1000L,
                pressureKpa = 101.3 - idx * 0.2,
                altitudeM = 0.0
            )
        }
        val trend = StormWarning.tendency(samples, nowMs)
        assertEquals(PressureTrendClass.UNKNOWN, trend.classification)
    }
}
