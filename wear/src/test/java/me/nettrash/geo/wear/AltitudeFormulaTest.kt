package me.nettrash.geo.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

/**
 * Sanity check that the Watch's barometric altitude calculation
 * matches the phone module's and the iOS code:
 *
 *   h = ln(P0 / Ph) / 0.00012, where P0 = 101.325 kPa
 *
 * Lives here (rather than in the main module) so the Wear APK's
 * test suite has a non-trivial regression to run against. The phone
 * module already covers the same formula via the live
 * `BarometerManager`; this is the watch's matching guard rail.
 */
class AltitudeFormulaTest {

    @Test fun altitudeAtSeaLevelIsRoughlyZero() {
        val pressureKpa = 101.325
        val altitude = ln(101.325 / pressureKpa) / 0.00012
        assertEquals(0.0, altitude, 0.001)
    }

    @Test fun altitudeAtEverestPressureIsRoughlyEverest() {
        // ~33.7 kPa at 8848 m by the same approximation.
        val pressureKpa = 101.325 * Math.exp(-0.00012 * 8848.0)
        val altitude = ln(101.325 / pressureKpa) / 0.00012
        assertEquals(8848.0, altitude, 0.5)
    }
}
