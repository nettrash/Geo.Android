package me.nettrash.geo.ar

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import me.nettrash.geo.util.GeoCalculations
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Tests for the geometry the terrain-aware skyline relies on: the
 * refraction-corrected Earth radius, the density of the distance
 * schedule the picker samples along each bearing, the adaptive
 * bearing-refinement pair selection, and the screen-space weld-anchor
 * interpolation. Mirrors the iOS `SkylineGeometryTests` so the two
 * ports stay locked to the same silhouette math.
 */
@RunWith(RobolectricTestRunner::class)
class SkylineGeometryTest {

    // ─── Refraction ────────────────────────────────────────────────

    @Test fun effectiveRadiusFoldsInStandardRefraction() {
        // R_eff = R / (1 − k) with k = 0.13 → ~7 323 km.
        assertThat(GeoCalculations.EFFECTIVE_EARTH_RADIUS)
            .isWithin(1.0)
            .of(GeoCalculations.EARTH_RADIUS / (1 - 0.13))
        assertThat(GeoCalculations.EFFECTIVE_EARTH_RADIUS)
            .isGreaterThan(GeoCalculations.EARTH_RADIUS)
    }

    @Test fun refractionLiftsDistantTerrain() {
        // A 2 800 m ridge 185 km from an observer at 200 m sits below
        // the pure-geometry sightline (curvature drop ~2 686 m) but is
        // genuinely visible in standard atmosphere (refracted drop
        // ~2 337 m). The skyline must be computed with the refracted
        // radius or ranges like this vanish from the silhouette.
        val geometric = GeoCalculations.apparentAltitudeAngle(
            observerAltitude = 200.0,
            targetAltitude = 2_800.0,
            distance = 185_000.0
        )
        val refracted = GeoCalculations.apparentAltitudeAngle(
            observerAltitude = 200.0,
            targetAltitude = 2_800.0,
            distance = 185_000.0,
            radius = GeoCalculations.EFFECTIVE_EARTH_RADIUS
        )
        assertThat(geometric).isLessThan(0.0)
        assertThat(refracted).isGreaterThan(0.0)
    }

    // ─── Distance schedule density ─────────────────────────────────

    @Test fun skylineDistanceScheduleHasNoRidgeSwallowingGaps() {
        // The silhouette can only ever be as good as the sampling: any
        // consecutive pair with a big ratio is a distance band whose
        // terrain is invisible to the picker. Pin the schedule to a
        // ≤1.16× ratio at every step (≈ ±7 % miss window after the
        // refinement round halves it), full coverage 100 m → 200 km,
        // strictly increasing, and a bounded query budget.
        val ds = SkylineCalculator.DISTANCES_METERS
        assertThat(ds.first()).isEqualTo(100.0)
        assertThat(ds.last()).isEqualTo(SkylineCalculator.MAX_RANGE_METERS)
        // Schedule ballooning would blow the per-pass query budget.
        assertThat(ds.size).isLessThan(60)
        for (i in 1 until ds.size) {
            // Schedule must be strictly increasing.
            assertThat(ds[i]).isGreaterThan(ds[i - 1])
            if (ds[i - 1] >= 1_000.0) {
                // A bigger ratio gap can swallow a ridge.
                assertThat(ds[i] / ds[i - 1]).isAtMost(1.16)
            } else {
                // Close-in sampling must stay ~200 m.
                assertThat(ds[i] - ds[i - 1]).isAtMost(250.0)
            }
        }
    }

    // ─── Adaptive bearing refinement (pair selection) ──────────────

    @Test fun adaptiveBearingsPicksAngleJumpPair() {
        // Flat silhouette except a 2° apparent-angle cliff between the
        // samples at 10° and 12° → exactly one midpoint, at 11°.
        val samples = ArrayList<SkylineCalculator.AdaptiveSample>()
        var b = 0.0
        while (b <= 20.0) {
            samples.add(
                SkylineCalculator.AdaptiveSample(
                    bearing = b, angleDeg = if (b >= 12) 3.0 else 1.0, distance = 10_000.0
                )
            )
            b += 2.0
        }
        val mids = SkylineCalculator.adaptiveRefinementBearings(samples)
        assertThat(mids).isEqualTo(listOf(11.0))
    }

    @Test fun adaptiveBearingsPicksDistanceRatioPair() {
        // Same apparent angle everywhere, but the winning distance jumps
        // 5 km → 40 km between 100° and 102° (a near/far transition the
        // angle threshold alone can miss) → midpoint at 101°.
        val samples = ArrayList<SkylineCalculator.AdaptiveSample>()
        var b = 90.0
        while (b <= 110.0) {
            samples.add(
                SkylineCalculator.AdaptiveSample(
                    bearing = b, angleDeg = 1.0, distance = if (b >= 102) 40_000.0 else 5_000.0
                )
            )
            b += 2.0
        }
        val mids = SkylineCalculator.adaptiveRefinementBearings(samples)
        assertThat(mids).isEqualTo(listOf(101.0))
    }

    @Test fun adaptiveBearingsRespectsCapAndKeepsWorstPairs() {
        // Quadratically growing angles: every adjacent jump past i = 4
        // exceeds the 0.8° threshold and the jumps grow with bearing, so a
        // cap of 5 must keep exactly the five LARGEST discontinuities —
        // the midpoints of the last five pairs.
        val samples = (0 until 30).map { i ->
            SkylineCalculator.AdaptiveSample(
                bearing = 2.0 * i, angleDeg = 0.1 * i * i, distance = 10_000.0
            )
        }
        val mids = SkylineCalculator.adaptiveRefinementBearings(samples, maxExtra = 5)
        assertThat(mids).hasSize(5)
        assertThat(mids.toSet()).isEqualTo(setOf(49.0, 51.0, 53.0, 55.0, 57.0))
        // Worst first: severity ordering puts the biggest jump (the 56°↔58°
        // pair, midpoint 57°) at the front.
        assertThat(mids.first()).isEqualTo(57.0)
    }

    @Test fun adaptiveBearingsDetectsWrapPair() {
        // The 358°↔2° adjacency wraps through north: its midpoint is 0°.
        // The sample at 2° also jumps against its 6° neighbour → mid 4°.
        val samples = listOf(
            SkylineCalculator.AdaptiveSample(bearing = 2.0, angleDeg = 5.0, distance = 10_000.0),
            SkylineCalculator.AdaptiveSample(bearing = 6.0, angleDeg = 1.0, distance = 10_000.0),
            SkylineCalculator.AdaptiveSample(bearing = 350.0, angleDeg = 1.0, distance = 10_000.0),
            SkylineCalculator.AdaptiveSample(bearing = 354.0, angleDeg = 1.0, distance = 10_000.0),
            SkylineCalculator.AdaptiveSample(bearing = 358.0, angleDeg = 1.0, distance = 10_000.0)
        )
        val mids = SkylineCalculator.adaptiveRefinementBearings(samples)
        // Wrap pair 358°↔2° must yield midpoint 0°.
        assertThat(mids).contains(0.0)
        assertThat(mids).contains(4.0)
        // The 6°↔350° gap is too wide to subdivide.
        assertThat(mids).hasSize(2)
    }

    // ─── Welded-anchor screen interpolation ────────────────────────

    @Test fun weldedAnchorScreenPointFraction() {
        // Query 25 % of the way from lo (10°) to hi (12°) → quarter point
        // of the screen segment.
        val p = weldedAnchorScreenPoint(
            bearing = 10.5,
            loBearing = 10.0, hiBearing = 12.0,
            loScreen = Offset(100f, 200f),
            hiScreen = Offset(200f, 240f)
        )
        assertThat(p.x).isWithin(1e-4f).of(125f)
        assertThat(p.y).isWithin(1e-4f).of(210f)
    }

    @Test fun weldedAnchorScreenPointLandsOnSegment() {
        // For any bearing inside the bracket the anchor must be collinear
        // with — and between — the two projected sample points; that's what
        // guarantees the pill's dot sits exactly on the drawn polyline.
        val lo = Offset(40f, 300f)
        val hi = Offset(220f, 180f)
        var q = 100.0
        while (q <= 104.0) {
            val p = weldedAnchorScreenPoint(
                bearing = q, loBearing = 100.0, hiBearing = 104.0,
                loScreen = lo, hiScreen = hi
            )
            // Collinear: cross product of (p−lo) × (hi−lo) is zero.
            val cross = (p.x - lo.x).toDouble() * (hi.y - lo.y).toDouble() -
                (p.y - lo.y).toDouble() * (hi.x - lo.x).toDouble()
            assertThat(cross).isWithin(1e-3).of(0.0)
            assertThat(p.x).isAtLeast(lo.x)
            assertThat(p.x).isAtMost(hi.x)
            q += 0.5
        }
        // Degenerate zero-span bracket returns the lo point.
        val z = weldedAnchorScreenPoint(
            bearing = 100.0, loBearing = 100.0, hiBearing = 100.0,
            loScreen = lo, hiScreen = hi
        )
        assertThat(z).isEqualTo(lo)
    }

    @Test fun weldedAnchorScreenPointWrapCase() {
        // Bracket spans the 0° seam: lo 354°, hi 0° (span 6°), query 358°
        // → 4/6 of the way along the screen segment.
        val p = weldedAnchorScreenPoint(
            bearing = 358.0,
            loBearing = 354.0, hiBearing = 0.0,
            loScreen = Offset(0f, 0f),
            hiScreen = Offset(60f, 30f)
        )
        assertThat(p.x).isWithin(1e-4f).of(40f)
        assertThat(p.y).isWithin(1e-4f).of(20f)
    }

    // ─── Peak-label importance score + slot selection ──────────────
    //     Mirrors iOS SkylineGeometryTests.

    @Test fun peakLabelScoreBigDistantSummitBeatsSmallNearBump() {
        // The regression the score fixes: nearest-first labelled a 400 m
        // bump 3 km away over a 4 000 m summit 30 km away. With
        // altitude − 8 m/km the summit wins by an order of magnitude.
        val summit = peakLabelScore(altitude = 4_000.0, distance = 30_000.0)
        val bump = peakLabelScore(altitude = 400.0, distance = 3_000.0)
        assertThat(summit).isWithin(1e-9).of(3_760.0)
        assertThat(bump).isWithin(1e-9).of(376.0)
        assertThat(summit).isGreaterThan(bump)
    }

    @Test fun peakLabelScoreBetweenEqualSummitsNearerWins() {
        // Same altitude → the distance penalty is the tie-breaker, so the
        // nearer of two similar summits keeps its slot.
        val near = peakLabelScore(altitude = 1_000.0, distance = 5_000.0)
        val far = peakLabelScore(altitude = 1_000.0, distance = 20_000.0)
        assertThat(near).isGreaterThan(far)
    }

    @Test fun selectWeldedPeakLabelsRespectsCapAndSpacingInScoreOrder() {
        fun label(name: String, x: Float): PeakLabelInfo =
            PeakLabelInfo(UUID.randomUUID(), name, 1_000.0, Offset(x, 100f))

        // Three candidates within one 54 px spacing slot: the HIGHEST score
        // must win it (not insertion order, not the nearest), and the two
        // losers are dropped; a fourth candidate far enough away survives.
        val crowdedWinner = label("big", x = 100f)
        val crowdedLoserA = label("smallA", x = 110f)
        val crowdedLoserB = label("smallB", x = 130f)
        val separate = label("separate", x = 400f)
        val kept = selectWeldedPeakLabels(
            candidates = listOf(
                crowdedLoserA to 500.0,
                crowdedWinner to 4_000.0,
                crowdedLoserB to 900.0,
                separate to 100.0
            ),
            minSpacing = 54f, maxCount = 16
        )
        assertThat(kept.map { it.name }).isEqualTo(listOf("big", "separate"))

        // Cap respected — and it keeps the TOP-scored labels when all are
        // spaced far enough apart to qualify.
        val many = (0 until 10).map { i ->
            label("p$i", x = i * 100f) to i.toDouble()
        }
        val capped = selectWeldedPeakLabels(candidates = many, minSpacing = 54f, maxCount = 3)
        assertThat(capped).hasSize(3)
        assertThat(capped.map { it.name }.toSet()).isEqualTo(setOf("p9", "p8", "p7"))
    }
}
