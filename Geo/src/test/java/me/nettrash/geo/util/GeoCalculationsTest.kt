package me.nettrash.geo.util

import com.google.common.truth.Truth.assertThat
import me.nettrash.geo.ui.nature.alignmentOffsetDegrees
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
        // gpsToENU's default radius folds in standard refraction (it feeds AR
        // sight-lines that must agree with the skyline), so the drop at ~10 km
        // is d² / (2·R_eff) ≈ 10019² / 14_647_000 ≈ 6.85 m — not the
        // un-refracted 7.85 m.
        assertThat(enu.up).isLessThan(0.0)
        assertThat(enu.up).isWithin(2.0).of(-6.85)
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


    // ─── Manual compass alignment (ENU rotation + pan conversion) ────
    //     Mirrors iOS GeometryTests.

    @Test fun rotateENUZeroDegreesIsIdentity() {
        val r = GeoCalculations.rotateENU(east = 123.4, north = -56.7, clockwiseDegrees = 0.0)
        assertThat(r.east).isWithin(1e-9).of(123.4)
        assertThat(r.north).isWithin(1e-9).of(-56.7)
    }

    @Test fun rotateENUPlus90MapsNorthToEast() {
        // Compass-sense rotation: +90° takes a due-North point (bearing 0)
        // to due East (bearing 90) — the overlay shifts clockwise/right.
        val r = GeoCalculations.rotateENU(east = 0.0, north = 100.0, clockwiseDegrees = 90.0)
        assertThat(r.east).isWithin(1e-9).of(100.0)
        assertThat(r.north).isWithin(1e-9).of(0.0)
    }

    @Test fun rotateENUMinus90MapsNorthToWest() {
        // −90° takes due North (bearing 0) to due West (bearing 270).
        val r = GeoCalculations.rotateENU(east = 0.0, north = 100.0, clockwiseDegrees = -90.0)
        assertThat(r.east).isWithin(1e-9).of(-100.0)
        assertThat(r.north).isWithin(1e-9).of(0.0)
    }

    @Test fun alignmentPanConversionSignAndScale() {
        // 8 dp of rightward pan = +1° of alignment; sign follows the finger.
        assertThat(alignmentOffsetDegrees(base = 0.0, panTranslationDp = 8.0))
            .isWithin(1e-9).of(1.0)
        assertThat(alignmentOffsetDegrees(base = 0.0, panTranslationDp = -16.0))
            .isWithin(1e-9).of(-2.0)
        // Pan continues from the latched base, it doesn't restart at zero.
        assertThat(alignmentOffsetDegrees(base = 5.0, panTranslationDp = 24.0))
            .isWithin(1e-9).of(8.0)
    }

    @Test fun alignmentPanConversionClampsToPlusMinus30() {
        // A screen-crossing fling can't exceed the ±30° clamp in either
        // direction, whatever the starting base.
        assertThat(alignmentOffsetDegrees(base = 0.0, panTranslationDp = 10_000.0))
            .isWithin(1e-9).of(30.0)
        assertThat(alignmentOffsetDegrees(base = 0.0, panTranslationDp = -10_000.0))
            .isWithin(1e-9).of(-30.0)
        assertThat(alignmentOffsetDegrees(base = 29.0, panTranslationDp = 80.0))
            .isWithin(1e-9).of(30.0)
        assertThat(alignmentOffsetDegrees(base = -29.0, panTranslationDp = -80.0))
            .isWithin(1e-9).of(-30.0)
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

    // ─── cardinalDirection (8-point compass) — mirrors iOS GeometryTests ──

    @Test fun cardinalDirectionBoundaries() {
        assertThat(GeoCalculations.cardinalDirection(0.0)).isEqualTo("N")
        assertThat(GeoCalculations.cardinalDirection(45.0)).isEqualTo("NE")
        assertThat(GeoCalculations.cardinalDirection(90.0)).isEqualTo("E")
        assertThat(GeoCalculations.cardinalDirection(117.0)).isEqualTo("SE") // spec example
        assertThat(GeoCalculations.cardinalDirection(135.0)).isEqualTo("SE")
        assertThat(GeoCalculations.cardinalDirection(180.0)).isEqualTo("S")
        assertThat(GeoCalculations.cardinalDirection(225.0)).isEqualTo("SW")
        assertThat(GeoCalculations.cardinalDirection(270.0)).isEqualTo("W")
        assertThat(GeoCalculations.cardinalDirection(315.0)).isEqualTo("NW")
    }

    @Test fun cardinalDirectionWrapsAndSnaps() {
        assertThat(GeoCalculations.cardinalDirection(360.0)).isEqualTo("N")
        assertThat(GeoCalculations.cardinalDirection(350.0)).isEqualTo("N")  // 337.5–360 → N
        assertThat(GeoCalculations.cardinalDirection(-45.0)).isEqualTo("NW") // negative normalises
        assertThat(GeoCalculations.cardinalDirection(405.0)).isEqualTo("NE") // >360 normalises (45°)
    }

    @Test fun cardinalDirectionSectorBoundaries() {
        // Lower sector edge inclusive; just below snaps to the previous
        // sector. Locked in lockstep with iOS GeometryTests.
        assertThat(GeoCalculations.cardinalDirection(22.5)).isEqualTo("NE")
        assertThat(GeoCalculations.cardinalDirection(67.5)).isEqualTo("E")
        assertThat(GeoCalculations.cardinalDirection(112.5)).isEqualTo("SE")
        assertThat(GeoCalculations.cardinalDirection(157.5)).isEqualTo("S")
        assertThat(GeoCalculations.cardinalDirection(202.5)).isEqualTo("SW")
        assertThat(GeoCalculations.cardinalDirection(247.5)).isEqualTo("W")
        assertThat(GeoCalculations.cardinalDirection(292.5)).isEqualTo("NW")
        assertThat(GeoCalculations.cardinalDirection(337.5)).isEqualTo("N")
        assertThat(GeoCalculations.cardinalDirection(22.4999)).isEqualTo("N")
        assertThat(GeoCalculations.cardinalDirection(337.4999)).isEqualTo("NW")
    }

    @Test fun cardinalDirectionNonFiniteIsN() {
        assertThat(GeoCalculations.cardinalDirection(Double.NaN)).isEqualTo("N")
        assertThat(GeoCalculations.cardinalDirection(Double.POSITIVE_INFINITY)).isEqualTo("N")
        assertThat(GeoCalculations.cardinalDirection(Double.NEGATIVE_INFINITY)).isEqualTo("N")
    }

    // ── Horizon visibility ── mirrors iOS `HorizonVisibilityTests`. ──────────

    @Test fun tallNearbyPeakIsVisible() {
        // 3000 m peak 20 km away, observer at 500 m: comfortably over the horizon.
        assertThat(GeoCalculations.isAboveHorizon(500.0, 3000.0, 20_000.0)).isTrue()
    }

    @Test fun lowFarPeakBelowSeaLevelObserverIsHidden() {
        // A 200 m hill 120 km away, observer at the shore (0 m): hidden.
        assertThat(GeoCalculations.isAboveHorizon(0.0, 200.0, 120_000.0)).isFalse()
    }

    @Test fun heightExtendsVisibility() {
        // The same far hill becomes visible from a high vantage.
        assertThat(GeoCalculations.isAboveHorizon(0.0, 500.0, 150_000.0)).isFalse()
        assertThat(GeoCalculations.isAboveHorizon(3000.0, 500.0, 150_000.0)).isTrue()
    }

    @Test fun exactlyAtCombinedHorizonIsVisible() {
        val r = GeoCalculations.EFFECTIVE_EARTH_RADIUS
        val d = Math.sqrt(2 * r * 100.0) + Math.sqrt(2 * r * 100.0)
        assertThat(GeoCalculations.isAboveHorizon(100.0, 100.0, d, r)).isTrue()
        assertThat(GeoCalculations.isAboveHorizon(100.0, 100.0, d + 1, r)).isFalse()
    }
}
