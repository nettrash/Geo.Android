package me.nettrash.geo.util

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, testable geometry helpers used by AR overlays, skyline
 * computation, and unit tests. Direct port of iOS
 * `Geo/Core/Geometry.swift` — keeping the same helpers in the same
 * order so the two sides stay easy to compare line-for-line.
 */
object GeoCalculations {

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    /** Earth's mean radius in metres. Exposed for use by callers that
     *  need to do their own ray/curvature math (e.g. skyline). */
    const val EARTH_RADIUS = 6_371_000.0

    /** Standard terrestrial refraction coefficient (k ≈ 0.13). Light
     *  grazing the surface bends *down* toward the Earth, so distant
     *  terrain appears HIGHER than pure geometry suggests. Surveyors
     *  model this by replacing the Earth radius with an effective
     *  radius R/(1−k) wherever a curvature drop is computed. */
    const val REFRACTION_COEFFICIENT = 0.13

    /** Effective Earth radius with standard refraction folded in
     *  (~7 323 km). Every curvature-drop term along the AR sightline —
     *  the skyline picker, the horizon overlay, the peak welds and the
     *  AR markers — must use THIS radius, and the same one everywhere,
     *  or distant ranges render visibly too low (and the pieces detach
     *  from each other). Mirrors iOS `Geometry.effectiveEarthRadius`. */
    const val EFFECTIVE_EARTH_RADIUS = EARTH_RADIUS / (1 - REFRACTION_COEFFICIENT)

    /** Horizontal-only ENU pair returned by [rotateENU]. */
    data class ENUHorizontal(val east: Double, val north: Double)

    /**
     * Rotate a local ENU horizontal offset about the vertical axis by
     * [clockwiseDegrees], in the COMPASS sense: positive degrees move a
     * point at bearing θ to bearing θ + degrees (clockwise when viewed
     * from above — N→E→S→W). Backs the manual compass-alignment knob
     * (`ArSceneController.userAlignmentDeg`): rotating all drawn content
     * to larger bearings shifts the overlay RIGHT on screen. (The
     * controller's own ARCore-frame rotation is the INVERSE sense — it
     * maps β to β − yaw — which is why it composes the knob by
     * SUBTRACTING it from `frameYawOffsetDeg`; see `appliedYawOffsetDeg`.)
     *
     *     east'  = east·cos + north·sin
     *     north' = north·cos − east·sin
     *
     * (Check: +90° maps due-North (0, d) to due-East (d, 0).) Pure so
     * the sign convention is pinned by unit tests. Mirrors iOS
     * `Geometry.rotateENU`.
     */
    fun rotateENU(east: Double, north: Double, clockwiseDegrees: Double): ENUHorizontal {
        if (clockwiseDegrees == 0.0) return ENUHorizontal(east, north)
        val r = Math.toRadians(clockwiseDegrees)
        val c = cos(r)
        val s = sin(r)
        return ENUHorizontal(east * c + north * s, north * c - east * s)
    }

    /** Observer eye height (m) above the DEM ground cell the user is
     *  standing on ([effectiveObserverAltitude]). */
    const val OBSERVER_EYE_HEIGHT = 1.7

    /** Sensor-vs-DEM disagreement (metres) beyond which we stop trusting
     *  the DEM anchor and believe the sensor instead (the user may be on
     *  a tower, cable car, aircraft, …). */
    const val OBSERVER_ALTITUDE_TOLERANCE = 10.0

    /**
     * The observer altitude every AR consumer (skyline picker, horizon
     * overlay, welded pills, markers, occlusion, tap hit-tests) should
     * use, reconciling the barometer/GPS sensor value with the DEM cell
     * the observer is standing on.
     *
     * Rationale: the silhouette is drawn FROM the DEM, so when the user
     * is standing on the terrain being drawn, self-consistency with that
     * terrain beats absolute sensor accuracy — a 10–30 m GPS/baro error
     * tilts the whole near silhouette up or down. Decision:
     *
     *  - no DEM value → [sensor] unchanged (the baro>0-else-GPS input);
     *  - sensor within ±[tolerance] of `demGround + eyeHeight` → snap to
     *    `demGround + eyeHeight` (standing on the modelled terrain);
     *  - sensor MORE than [tolerance] ABOVE `demGround + eyeHeight` →
     *    keep [sensor] (genuinely elevated: tower, cable car, aircraft);
     *  - otherwise (at/below eye level, incl. >[tolerance] below DEM
     *    ground — underground is impossible, that's sensor drift) → snap
     *    to `demGround + eyeHeight`.
     *
     * Pure and total so it unit-tests deterministically. Mirrors iOS
     * `Geometry.effectiveObserverAltitude`.
     */
    fun effectiveObserverAltitude(
        sensor: Double,
        demGround: Double?,
        eyeHeight: Double = OBSERVER_EYE_HEIGHT,
        tolerance: Double = OBSERVER_ALTITUDE_TOLERANCE
    ): Double {
        if (demGround == null) return sensor
        val demEye = demGround + eyeHeight
        // Only a sensor reading well ABOVE the terrain eye line survives;
        // everything else (within tolerance, below eye level, underground)
        // snaps to the DEM-consistent eye altitude.
        return if (sensor > demEye + tolerance) sensor else demEye
    }

    /**
     * Calculate bearing (degrees) from one coordinate to another
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        var b = Math.toDegrees(atan2(y, x))
        if (b < 0) b += 360.0
        return b
    }

    /**
     * Convert GPS to local ENU (East-North-Up) offset in meters.
     * Includes Earth curvature compensation for points >5km away —
     * without it distant peaks visibly "float" above the horizon. The
     * default [radius] folds in standard refraction: every caller is an
     * AR sight-line projection (peak markers, occlusion, tap hit-tests),
     * and those must agree with the skyline, which is
     * refraction-corrected too.
     */
    data class ENUOffset(val east: Double, val north: Double, val up: Double)

    fun gpsToENU(
        fromLat: Double, fromLon: Double, fromAlt: Double,
        toLat: Double, toLon: Double, toAlt: Double,
        radius: Double = EFFECTIVE_EARTH_RADIUS
    ): ENUOffset {
        val latRef = Math.toRadians(fromLat)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(latRef)

        val dLat = toLat - fromLat
        val dLon = toLon - fromLon

        val north = dLat * METERS_PER_DEGREE_LAT
        val east = dLon * metersPerDegreeLon

        val horizontalDist = sqrt(north * north + east * east)
        val curvatureDrop = (horizontalDist * horizontalDist) / (2.0 * radius)

        val up = (toAlt - fromAlt) - if (horizontalDist > 5000) curvatureDrop else 0.0

        return ENUOffset(east, north, up)
    }

    /**
     * Whether a peak of height [targetAltitude] is above the visible horizon for
     * an observer at [observerAltitude], [distance] metres away — the classic
     * two-tangent test: the peak clears the Earth's bulge when the distance is no
     * more than the sum of the two horizon distances
     * `√(2·R·h_obs) + √(2·R·h_peak)`. Uses the refraction-corrected effective
     * radius so the cut matches the drawn geometric horizon. Ignores intervening
     * terrain (which would need a DEM). Kept identical to iOS
     * `Geometry.isAboveHorizon`.
     */
    fun isAboveHorizon(
        observerAltitude: Double,
        targetAltitude: Double,
        distance: Double,
        radius: Double = EFFECTIVE_EARTH_RADIUS
    ): Boolean {
        val ho = maxOf(observerAltitude, 0.0)
        val hp = maxOf(targetAltitude, 0.0)
        return distance <= sqrt(2 * radius * ho) + sqrt(2 * radius * hp)
    }

    /**
     * Distance between two coordinates in meters (haversine)
     */
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }

    /**
     * Distance (metres) from an observer at altitude `h` above mean
     * sea level to the geometric horizon on a smooth Earth.
     *
     *   d = sqrt(2·R·h + h²)
     *
     * The `h²` term is negligible at typical observer altitudes but
     * matters for high mountaintops / aircraft.
     */
    fun horizonDistance(observerAltitude: Double, radius: Double = EARTH_RADIUS): Double {
        val safeH = max(observerAltitude, 0.0)
        return sqrt(2.0 * radius * safeH + safeH * safeH)
    }

    /**
     * Project a coordinate forward along a great-circle from `origin`
     * at the given [bearingDeg] (0 = N, clockwise) by [distance]
     * metres. Spherical-Earth approximation — accurate to < 0.1 %
     * over the 1–200 km ranges the skyline calculator uses.
     *
     * Returns `(lat, lon)`.
     */
    data class LatLon(val latitude: Double, val longitude: Double)

    fun project(
        originLat: Double, originLon: Double,
        bearingDeg: Double, distance: Double,
        radius: Double = EARTH_RADIUS
    ): LatLon {
        val theta = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(originLat)
        val lon1 = Math.toRadians(originLon)
        val dR = distance / radius

        val lat2 = asin(sin(lat1) * cos(dR) + cos(lat1) * sin(dR) * cos(theta))
        val lon2 = lon1 + atan2(
            sin(theta) * sin(dR) * cos(lat1),
            cos(dR) - sin(lat1) * sin(lat2)
        )
        // Normalize longitude to (-180, +180].
        val lonDeg = ((Math.toDegrees(lon2) + 540) % 360) - 180
        return LatLon(Math.toDegrees(lat2), lonDeg)
    }

    /**
     * Apparent altitude angle (radians, positive = above horizontal)
     * of a target at [targetAltitude] and horizontal [distance] from
     * an observer at [observerAltitude]. Includes Earth-curvature
     * drop, which makes a distant peak appear lower than its raw
     * `(target − observer)` height suggests.
     */
    fun apparentAltitudeAngle(
        observerAltitude: Double,
        targetAltitude: Double,
        distance: Double,
        radius: Double = EARTH_RADIUS
    ): Double {
        if (distance <= 0.0) {
            return if (targetAltitude > observerAltitude) PI / 2 else -PI / 2
        }
        val curvatureDrop = (distance * distance) / (2.0 * radius)
        val apparentRise = (targetAltitude - observerAltitude) - curvatureDrop
        return atan2(apparentRise, distance)
    }

    /**
     * 8-point compass abbreviation (N / NE / E / SE / S / SW / W / NW) for a
     * bearing in degrees from true north. Kept identical to iOS
     * `Geometry.cardinalDirection`.
     */
    /** 8-point compass labels, hoisted so [cardinalDirection] doesn't
     *  re-allocate the array on every (per-heading-update) call. */
    private val CARDINAL_LABELS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun cardinalDirection(bearingDeg: Double): String {
        // Guard non-finite input explicitly so behaviour is defined and
        // identical to iOS (which would otherwise trap on Int(NaN)).
        if (!bearingDeg.isFinite()) return "N"
        val normalized = bearingDeg % 360
        val positive = (normalized + 360) % 360
        val index = ((positive + 22.5) % 360 / 45).toInt()
        return CARDINAL_LABELS[index % 8]
    }
}
