package me.nettrash.geo.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Manual-calibration math tests (M5b). Mirrors iOS
 * `AtmosphereCalibrationTests` — identical scenarios and tolerances, so a
 * divergence in either platform's inverse/decay math shows up as a failing
 * test on that side.
 */
class AtmosphereCalibrationTest {

    /**
     * TM5b.1 acceptance: the solved reference pressure fed back into the
     * forward helper reproduces the entered altitude (<0.5 m). The live
     * pressure is derived from the altitude + a plausible QNH so the pair is
     * realistic (and stays inside the 30–110 kPa clamp window).
     */
    @Test
    fun solveReferencePressureRoundTrip() {
        for (known in listOf(-200.0, 0.0, 500.0, 1500.0, 3000.0, 5500.0, 8000.0)) {
            for (qnhTrue in listOf(98.0, 101.325, 103.0)) {
                val p = qnhTrue * Math.pow(1.0 - known / 44330.0, 5.255)  // station pressure at `known`
                val solved = Atmosphere.solveReferencePressure(known, p)
                val back = Atmosphere.altitude(p, solved)
                assertEquals(known, back, 0.5)
            }
        }
    }

    @Test
    fun calibrationWeightDecaysLinearly() {
        val full = Atmosphere.CALIBRATION_DECAY_HOURS * 3600.0
        assertEquals(1.0, Atmosphere.calibrationWeight(0.0), 1e-9)
        assertEquals(0.5, Atmosphere.calibrationWeight(full / 2), 1e-9)
        assertEquals(0.0, Atmosphere.calibrationWeight(full), 1e-9)
        assertEquals(0.0, Atmosphere.calibrationWeight(full * 2), 1e-9)
        assertEquals(1.0, Atmosphere.calibrationWeight(-10.0), 1e-9)  // future-dated → fresh
    }
}
