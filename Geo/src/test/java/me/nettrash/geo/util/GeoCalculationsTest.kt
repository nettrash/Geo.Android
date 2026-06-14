package me.nettrash.geo.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sanity tests for [GeoCalculations]. These are pure-Kotlin / pure-math
 * functions, so they don't need Android — but the suite uses Robolectric
 * across the board so any future test that touches `android.*` Just Works
 * without changing the runner.
 *
 * Reference values come from a couple of well-known landmarks:
 *   • London (Big Ben):       51.5007°N, -0.1246°E
 *   • Paris (Eiffel Tower):   48.8584°N,  2.2945°E
 *   • Great-circle distance:  ~343 km, initial bearing ~149° (SE-ish)
 *   • Sydney Opera House:    -33.8568°S, 151.2153°E
 */
@RunWith(RobolectricTestRunner::class)
class GeoCalculationsTest {

    private val bigBenLat = 51.5007
    private val bigBenLon = -0.1246
    private val eiffelLat = 48.8584
    private val eiffelLon = 2.2945

    @Test fun distanceLondonToParisIsRoughly343Km() {
        val meters = GeoCalculations.distanceBetween(
            bigBenLat, bigBenLon, eiffelLat, eiffelLon
        )
        // Great-circle Big Ben <-> Eiffel Tower is ~340.5 km (the previous
        // 343 km expectation was simply wrong); +/-2 km covers sphere/WGS-84 drift.
        assertThat(meters).isWithin(2_000.0).of(340_500.0)
    }

    @Test fun distanceToSelfIsZero() {
        val meters = GeoCalculations.distanceBetween(
            bigBenLat, bigBenLon, bigBenLat, bigBenLon
        )
        assertThat(meters).isWithin(0.001).of(0.0)
    }

    @Test fun bearingLondonToParisIsSouthEast() {
        val deg = GeoCalculations.bearing(bigBenLat, bigBenLon, eiffelLat, eiffelLon)
        // Initial great-circle bearing from London to Paris ≈ 149°
        // (between East 90° and South 180° — the south-east quadrant).
        assertThat(deg).isGreaterThan(135.0)
        assertThat(deg).isLessThan(165.0)
    }

    @Test fun bearingDueNorthIsZero() {
        // North-pole-ward from any equatorial point is bearing 0°.
        val deg = GeoCalculations.bearing(0.0, 0.0, 1.0, 0.0)
        assertThat(deg).isWithin(0.001).of(0.0)
    }

    @Test fun bearingDueEastIsNinety() {
        val deg = GeoCalculations.bearing(0.0, 0.0, 0.0, 1.0)
        assertThat(deg).isWithin(0.001).of(90.0)
    }

    @Test fun bearingDueWestIsTwoSeventy() {
        // The formula normalises negatives to 0..360°.
        val deg = GeoCalculations.bearing(0.0, 0.0, 0.0, -1.0)
        assertThat(deg).isWithin(0.001).of(270.0)
    }

    @Test fun gpsToENUIsZeroForSamePoint() {
        val enu = GeoCalculations.gpsToENU(
            bigBenLat, bigBenLon, 0.0,
            bigBenLat, bigBenLon, 0.0
        )
        assertThat(enu.east).isWithin(0.001).of(0.0)
        assertThat(enu.north).isWithin(0.001).of(0.0)
        assertThat(enu.up).isWithin(0.001).of(0.0)
    }

    @Test fun gpsToENUEastIsPositiveForEastTarget() {
        // Move 0.001° east at the equator → east ≈ 111.32 m, north ≈ 0.
        val enu = GeoCalculations.gpsToENU(
            0.0, 0.0, 0.0,
            0.0, 0.001, 0.0
        )
        assertThat(enu.east).isWithin(0.5).of(111.32)
        assertThat(enu.north).isWithin(0.001).of(0.0)
    }

    @Test fun gpsToENUNorthIsPositiveForNorthTarget() {
        val enu = GeoCalculations.gpsToENU(
            0.0, 0.0, 0.0,
            0.001, 0.0, 0.0
        )
        assertThat(enu.east).isWithin(0.001).of(0.0)
        assertThat(enu.north).isWithin(0.5).of(111.32)
    }

    @Test fun gpsToENUAppliesEarthCurvatureBeyond5km() {
        // Far-away target whose horizontal distance > 5 km should subtract a
        // curvature drop from the Up component. We construct a target
        // ~10 km east at the same altitude, expect Up to be negative.
        val enu = GeoCalculations.gpsToENU(
            0.0, 0.0, 0.0,
            0.0, 0.09, 0.0  // ~10.02 km east at the equator
        )
        // Curvature drop at ~10 km ≈ d² / (2R) ≈ 10000² / 12_742_000 ≈ 7.85 m.
        assertThat(enu.up).isLessThan(0.0)
        assertThat(enu.up).isWithin(2.0).of(-7.85)
    }

    @Test fun gpsToENUSkipsCurvatureCorrectionUnder5km() {
        // 1 km away — curvature drop would be tiny (~0.08 m) but the
        // implementation skips it entirely under 5 km, so Up == altitude diff.
        val enu = GeoCalculations.gpsToENU(
            0.0, 0.0, 100.0,
            0.0, 0.009, 200.0  // ~1 km east, +100 m altitude
        )
        // Up should be exactly +100 m (no curvature subtraction).
        assertThat(enu.up).isWithin(0.001).of(100.0)
    }

    // ─── horizonDistance / project / apparentAltitudeAngle ────────

    @Test fun horizonDistanceAtSeaLevelIsZero() {
        assertThat(GeoCalculations.horizonDistance(0.0)).isEqualTo(0.0)
    }

    @Test fun horizonDistanceAt2MetresIsRoughly5Km() {
        // d = sqrt(2 · 6_371_000 · 2 + 4) ≈ 5048 m
        val d = GeoCalculations.horizonDistance(2.0)
        assertThat(d).isWithin(5.0).of(5048.0)
    }

    @Test fun horizonDistanceAt8848mIsCappedAndPositive() {
        // From Everest's summit the geometric horizon is ~336 km.
        val d = GeoCalculations.horizonDistance(8848.0)
        assertThat(d).isGreaterThan(330_000.0)
        assertThat(d).isLessThan(340_000.0)
    }

    @Test fun projectMovesEastAtTheEquator() {
        // 1 km east of (0,0) along a great circle should land very
        // close to (0, ~0.00898) — i.e. 1/111.32 degrees lon.
        val p = GeoCalculations.project(
            originLat = 0.0, originLon = 0.0,
            bearingDeg = 90.0, distance = 1000.0
        )
        assertThat(p.latitude).isWithin(0.0001).of(0.0)
        assertThat(p.longitude).isWithin(0.0001).of(0.00898)
    }

    @Test fun projectMovesNorthFromGreenwich() {
        // 1 km due north — latitude rises by ~0.00898°.
        val p = GeoCalculations.project(
            originLat = 51.5, originLon = -0.1,
            bearingDeg = 0.0, distance = 1000.0
        )
        assertThat(p.latitude).isWithin(0.0001).of(51.509)
        assertThat(p.longitude).isWithin(0.0001).of(-0.1)
    }

    @Test fun apparentAltitudeAngleNegativeForDistantSeaLevel() {
        // A "peak" at sea level 50 km away from a 100 m observer
        // sits well below the horizon — apparent angle must be < 0.
        val a = GeoCalculations.apparentAltitudeAngle(
            observerAltitude = 100.0,
            targetAltitude = 0.0,
            distance = 50_000.0
        )
        assertThat(a).isLessThan(0.0)
    }

    @Test fun apparentAltitudeAnglePositiveForCloseTallPeak() {
        // A 4000 m peak 2 km away from a sea-level observer is well
        // above the horizon — angle should be steeply positive.
        val a = GeoCalculations.apparentAltitudeAngle(
            observerAltitude = 0.0,
            targetAltitude = 4000.0,
            distance = 2_000.0
        )
        assertThat(a).isGreaterThan(1.0)  // > ~57°
    }
}
