package me.nettrash.geo.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sanity check that the Watch's barometric altitude calculation
 * matches the phone module's and the iOS code:
 *
 *   h = 44330 * (1 − (P / P0)^(1/5.255)), where P0 = 101.325 kPa
 *
 * (the lapse-rate / standard-atmosphere formula, #10 — replaces the
 * old `ln(P0 / Ph) / 0.00012` approximation). Pressure is clamped to
 * 30–110 kPa before the calculation (#11).
 *
 * Lives here (rather than in the main module) so the Wear APK's
 * test suite has a non-trivial regression to run against. The phone
 * module already covers the same formula via the live
 * `BarometerManager`; this is the watch's matching guard rail.
 */
class AltitudeFormulaTest {

    @Test fun altitudeAtSeaLevelIsZero() {
        assertEquals(0.0, Atmosphere.altitude(Atmosphere.SEA_LEVEL_KPA), 0.001)
    }

    @Test fun altitudeAtEverestPressureIsRoughlyEverest() {
        // Pressure at 8848 m by the lapse-rate formula, inverted:
        //   P = P0 * (1 − h / 44330)^5.255
        val everestPressureKpa =
            Atmosphere.SEA_LEVEL_KPA * Math.pow(1.0 - 8848.0 / 44330.0, 5.255)
        assertEquals(8848.0, Atmosphere.altitude(everestPressureKpa), 0.5)
    }

    @Test fun pressureIsClampedToPlausibleRange() {
        // A spurious low reading is clamped to 30 kPa before use (#11),
        // so it can't produce an absurd altitude.
        assertEquals(
            Atmosphere.altitude(30.0),
            Atmosphere.altitude(0.0),
            0.001
        )
        // …and a spurious high reading is clamped to 110 kPa.
        assertEquals(
            Atmosphere.altitude(110.0),
            Atmosphere.altitude(500.0),
            0.001
        )
    }
}
