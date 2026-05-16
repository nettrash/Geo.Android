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
     * Includes Earth curvature compensation for points >5km away.
     */
    data class ENUOffset(val east: Double, val north: Double, val up: Double)

    fun gpsToENU(
        fromLat: Double, fromLon: Double, fromAlt: Double,
        toLat: Double, toLon: Double, toAlt: Double
    ): ENUOffset {
        val latRef = Math.toRadians(fromLat)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(latRef)

        val dLat = toLat - fromLat
        val dLon = toLon - fromLon

        val north = dLat * METERS_PER_DEGREE_LAT
        val east = dLon * metersPerDegreeLon

        val horizontalDist = sqrt(north * north + east * east)
        val curvatureDrop = (horizontalDist * horizontalDist) / (2.0 * EARTH_RADIUS)

        val up = (toAlt - fromAlt) - if (horizontalDist > 5000) curvatureDrop else 0.0

        return ENUOffset(east, north, up)
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
}
